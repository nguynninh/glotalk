import { ask, open, save } from "@tauri-apps/plugin-dialog";
import { listen } from "@tauri-apps/api/event";
import { api, DEFAULT_SERVER, DubLine, DubProgress, formatTime, MediaInfo, OcrModel, OcrPartial, OcrProgress, OcrSegment, settingsStore, SpeakersProgress, SttProgress, Region, RenderAudio, RenderLayer, RenderProgress, VideoInfo } from "../lib/api";
import { Speaker, speakersFromLabels } from "../lib/speakers";
import { PronounRule, withPronouns } from "../lib/pronouns";
import { parseSrt, Segment, toSrt } from "../lib/srt";
import { captionText, Clip, clipFx, duckSpans, Track, trackAudible, withCaptionTracks } from "../lib/timeline";
import { drawCaption, drawText } from "../lib/graphics";
import { SubStyle } from "../lib/subStyle";
import { ColorGrade, gradeForExport } from "../lib/colorGrade";
import { defaultTracks, OcrResume, RegionKind, sequenceSize, timelineEnd, useEditor } from "./editor";
import { langName, t, TKey } from "../i18n";
import { useSession } from "../lib/auth";

const S = () => useEditor.getState();
const dirOf = (p: string) => p.replace(/[\\/][^\\/]+$/, "");
const baseName = (p: string) => p.replace(/^.*[\\/]/, "").replace(/\.[^.]+$/, "");

export const MEDIA_EXTS = ["mp4", "mkv", "mov", "avi", "webm", "flv", "m4v", "mp3", "wav", "m4a", "aac", "flac", "ogg", "opus", "png", "jpg", "jpeg", "webp", "bmp", "gif"];

/** Mở hộp thoại chọn media, đặt vào timeline tại playhead (hoặc 0 nếu timeline đang trống). */
export async function importMedia() {
  const files = await open({ multiple: true, filters: [{ name: "Media", extensions: MEDIA_EXTS }] });
  if (!files?.length) return;
  await addMediaFiles(files, { ms: S().clips.length ? S().time : 0 });
}

/**
 * Nhập file vào dự án. Có `at` thì đặt lên timeline, các file nối tiếp nhau từ `at.ms`.
 * Thumbnail/waveform chạy nền và chỉ được ghi nếu asset vẫn còn trong dự án.
 */
export async function addMediaFiles(paths: string[], at?: { ms: number; trackId?: string }) {
  let ms = at?.ms ?? 0;
  for (const file of paths) {
    try {
      const info = await api.probeMedia(file);
      const hadVideo = !!S().video;
      const asset = S().addAsset(info);
      if (!hadVideo && S().video) S().set({ outDir: S().outDir || dirOf(file) });
      S().log(t("act.imported", { name: baseName(file), w: info.width, h: info.height, fps: info.fps.toFixed(2), dur: formatTime(info.duration_ms) }));
      if (at) {
        const id = S().placeAsset(asset.id, ms, at.trackId);
        const c = S().clips.find((x) => x.id === id);
        if (c) ms = c.start + c.dur;
        else S().log(t("act.trackLocked"));
      }
      loadAssetData(asset.id);
    } catch (e) {
      S().log(t("act.videoError", { err: `${baseName(file)}: ${String(e)}` }));
    }
  }
}

function loadAssetData(id: string) {
  const a = S().assets.find((x) => x.id === id);
  if (!a || a.thumbs.length || a.peaks.length) return;
  const alive = () => S().assets.some((x) => x.id === id && x.path === a.path);
  if (a.kind === "image") S().setAsset(id, { thumbs: [a.path] });
  if (a.kind === "video") {
    const count = Math.min(240, Math.max(20, Math.round(a.duration_ms / 4000)));
    api.thumbnails(a.path, a.duration_ms, count, 72).then((th) => alive() && S().setAsset(id, { thumbs: th })).catch((e) => S().log(t("act.thumbError", { err: String(e) })));
  }
  if (a.has_audio) api.audioPeaks(a.path, S().peaksPerSec).then((p) => alive() && S().setAsset(id, { peaks: p })).catch((e) => S().log(t("act.waveError", { err: String(e) })));
}

/** File dự án `.glotalk` (JSON). Thumbnail/waveform không lưu, sinh lại khi mở (có cache trên đĩa). */
interface ProjectFile {
  version: 1;
  video: VideoInfo | null;
  assets: (MediaInfo & { id: string })[];
  tracks: Track[];
  clips: Clip[];
  segments: Segment[];
  speakers?: Speaker[];
  pronouns?: PronounRule[];
  subStyle: SubStyle;
  grade: ColorGrade;
  regions: Partial<Record<RegionKind, Region>>;
  uid: number;
  ocrResume?: OcrResume | null;
}
let projectPath = "";

/** Trả về false khi người dùng huỷ hộp thoại lưu hoặc ghi file lỗi. `quiet`: không ghi log khi lưu thành công (tự lưu). */
export async function saveProject(saveAs = false, quiet = false): Promise<boolean> {
  const s = S();
  let file = projectPath;
  if (!file || saveAs) {
    const name = s.video ? `${baseName(s.video.path)}.glotalk` : "project.glotalk";
    const picked = await save({ defaultPath: s.outDir ? `${s.outDir}/${name}` : name, filters: [{ name: "Glotalk", extensions: ["glotalk"] }] });
    if (!picked) return false;
    file = picked;
  }
  const data: ProjectFile = {
    version: 1,
    video: s.video,
    assets: s.assets.map(({ thumbs: _t, peaks: _p, ...a }) => a),
    tracks: s.tracks,
    clips: s.clips,
    segments: s.segments,
    speakers: s.speakers,
    pronouns: s.pronouns,
    subStyle: s.subStyle,
    grade: s.grade,
    regions: s.regions,
    uid: s.uid,
    ocrResume: s.ocrResume,
  };
  try {
    await api.writeText(file, JSON.stringify(data, null, 1));
    projectPath = file;
    addRecentProject(file);
    if (!quiet) S().log(t("act.projectSaved", { file }));
    return true;
  } catch (e) {
    S().log(t("act.projectError", { err: String(e) }));
    return false;
  }
}

/**
 * Tạo dự án trống trong thư mục riêng `<nơi lưu>/<tên>/` (kèm `work/`, `export/`) và ghi ngay file `.glotalk`.
 * Trả về true nếu đã tạo.
 */
export async function newProject(): Promise<boolean> {
  const picked = await save({ title: t("project.newTitle"), defaultPath: t("project.defaultName") });
  if (!picked) return false;
  try {
    const file = await api.createProject(picked.replace(/\.glotalk$/i, ""));
    const dir = dirOf(file);
    // Giữ log và trạng thái job đang chạy (OCR/STT/xuất tự kết thúc và cập nhật các trường này).
    const { logs: _l, ocr: _o, stt: _s, render: _r, speakersRun: _p, ...blank } = useEditor.getInitialState();
    S().set({ ...blank, tracks: defaultTracks(), outDir: `${dir}/export`, workDir: `${dir}/work` });
    projectPath = file;
    S().log(t("act.projectCreated", { dir }));
    await saveProject();
    return true;
  } catch (e) {
    S().log(t("act.projectError", { err: String(e) }));
    return false;
  }
}

let autosaving = Promise.resolve();
/** Tự lưu vào file dự án hiện tại (không hỏi nơi lưu, bỏ qua nếu chưa có file); các lần lưu xếp hàng để không ghi chồng nhau. */
export function autosaveProject(): Promise<void> {
  if (!projectPath) return autosaving;
  autosaving = autosaving.then(() => saveProject(false, true).then(() => {}));
  return autosaving;
}

/** Chờ job OCR kết thúc hẳn (đã nhận kết quả và cập nhật store). */
function ocrStopped(): Promise<void> {
  return new Promise((resolve) => {
    if (!S().ocr.running) return resolve();
    const off = useEditor.subscribe((s) => {
      if (s.ocr.running) return;
      off();
      resolve();
    });
  });
}

/**
 * Rời dự án về màn Bắt đầu: lưu vào file dự án rồi dừng các job đang chạy.
 * Chưa có file (vd. webview tải lại làm mất `projectPath`) mà đã có nội dung thì hỏi nơi lưu.
 * Trả về false khi chưa lưu được (huỷ hộp thoại/lỗi ghi) — khi đó ở lại dự án để không mất dữ liệu.
 */
export async function closeProject(): Promise<boolean> {
  // Dừng OCR trước để phần đã quét được giữ lại và lưu cùng dự án.
  if (S().ocr.running) {
    cancelOcr();
    await ocrStopped();
  }
  const s = S();
  const hasContent = !!s.video || s.assets.length > 0 || s.segments.length > 0;
  if ((projectPath || hasContent) && !(await saveProject())) return false;
  const { stt, render, speakersRun } = S();
  if (stt.running) cancelStt();
  if (speakersRun.running) cancelSpeakers();
  if (render.running) cancelRender();
  projectPath = "";
  return true;
}

/** Mở dự án qua hộp thoại. Trả về true nếu đã mở. */
export async function openProject(): Promise<boolean> {
  const file = await open({ filters: [{ name: "Glotalk", extensions: ["glotalk"] }] });
  return typeof file === "string" ? openProjectFile(file) : false;
}

export async function openProjectFile(file: string): Promise<boolean> {
  try {
    const d = JSON.parse(await api.readText(file)) as ProjectFile;
    if (d.version !== 1) throw new Error(`version ${d.version}`);
    // Dự án tạo bằng "Dự án mới" có `export/`, `work/`; file `.glotalk` cũ thì xuất cạnh file.
    const dir = dirOf(file);
    const [hasExport, hasWork] = await Promise.all([api.pathExists(`${dir}/export`), api.pathExists(`${dir}/work`)]);
    S().set({
      video: d.video,
      assets: d.assets.map((a) => ({ ...a, thumbs: [], peaks: [] })),
      tracks: withCaptionTracks(d.tracks),
      clips: d.clips,
      subStyle: d.subStyle,
      grade: d.grade,
      regions: d.regions ?? {},
      uid: d.uid,
      ocrResume: d.ocrResume ?? null,
      selectedClipId: null,
      time: 0,
      outDir: hasExport ? `${dir}/export` : dir,
      workDir: hasWork ? `${dir}/work` : "",
    });
    S().setSegments(d.segments ?? []);
    S().set({ speakers: d.speakers ?? [], pronouns: d.pronouns ?? [] });
    projectPath = file;
    addRecentProject(file);
    S().assets.forEach((a) => loadAssetData(a.id));
    S().log(t("act.projectOpened", { file }));
    return true;
  } catch (e) {
    S().log(t("act.projectError", { err: String(e) }));
    return false;
  }
}

const RECENT_KEY = "recentProjects";
const RECENT_MAX = 10;

/** Đường dẫn các file `.glotalk` mở/lưu gần đây, mới nhất trước. */
export function recentProjects(): string[] {
  try {
    const v = JSON.parse(settingsStore.get(RECENT_KEY, "[]"));
    return Array.isArray(v) ? v.filter((x): x is string => typeof x === "string") : [];
  } catch {
    return [];
  }
}

function addRecentProject(file: string) {
  settingsStore.set(RECENT_KEY, JSON.stringify([file, ...recentProjects().filter((f) => f !== file)].slice(0, RECENT_MAX)));
}

export function removeRecentProject(file: string) {
  settingsStore.set(RECENT_KEY, JSON.stringify(recentProjects().filter((f) => f !== file)));
}

export async function chooseOutDir() {
  const dir = await open({ directory: true });
  if (typeof dir === "string") S().set({ outDir: dir });
}

export async function importSrt() {
  const file = await open({ filters: [{ name: t("act.filterSubtitles"), extensions: ["srt"] }] });
  if (typeof file === "string") await importSrtFile(file);
}

export async function importSrtFile(file: string) {
  try {
    const segs = parseSrt(await api.readText(file));
    S().setSegments(segs);
    S().log(t("act.srtImported", { n: segs.length, name: baseName(file) }));
  } catch (e) {
    S().log(t("act.srtReadError", { err: String(e) }));
  }
}

export async function exportSrt(field: "vi" | "src" | "both" = "vi") {
  const { segments, video, outDir } = S();
  if (!segments.length) return S().log(t("act.noCaptionsToExport"));
  const name = video ? `${baseName(video.path)}.${field}.srt` : `${t("act.defaultSrtName")}.${field}.srt`;
  const file = await save({ defaultPath: outDir ? `${outDir}/${name}` : name, filters: [{ name: "SRT", extensions: ["srt"] }] });
  if (!file) return;
  try {
    await api.writeText(file, toSrt(segments, field));
    S().log(t("act.srtExported", { n: segments.length, file }));
  } catch (e) {
    S().log(t("act.srtWriteError", { err: String(e) }));
  }
}

export async function extractAudioForStt() {
  const { video, outDir, workDir } = S();
  if (!video) return S().log(t("act.noVideo"));
  const out = `${workDir || outDir || dirOf(video.path)}/${baseName(video.path)}.16k.wav`;
  S().log(t("act.extractingAudio"));
  try {
    await api.extractAudio(video.path, out);
    S().log(t("act.audioExtracted", { file: out }));
  } catch (e) {
    S().log(t("act.audioError", { err: String(e) }));
  }
}

/** Nhận dạng giọng nói của video chính qua server; câu gốc → `src`, bản dịch → `vi`. */
export async function runStt(opts: { sourceLanguage: string; targetLanguage: string; speakers: boolean }) {
  const s = S();
  const { video } = s;
  if (!video) return s.log(t("act.noVideo"));
  if (s.stt.running) return;
  if (s.segments.length && !(await confirmDialog(t("act.confirmReplace", { n: s.segments.length })))) return;

  const server = settingsStore.get("serverUrl", DEFAULT_SERVER);
  s.set({ stt: { running: true, phase: "audio", doneS: 0, totalS: 0, lastText: "" } });
  s.log(t("act.sttStart", { server }));
  const unlisten = await listen<SttProgress>("stt-progress", (e) => {
    const prev = S().stt;
    S().set({ stt: { ...e.payload, running: true, lastText: e.payload.lastText || prev.lastText } });
  });
  try {
    const out = await api.sttTranscribe({ server, path: video.path, ...opts });
    for (const msg of out.warnings) S().log(t("act.sttWarning", { msg }));
    // Người dùng có thể đã mở video khác trong lúc nhận dạng.
    if (S().video?.path !== video.path) return;
    S().setSegments(out.segments.map((x, i) => ({ id: i + 1, start: x.start, end: x.end, src: x.text, vi: x.translation, speaker: x.speaker > 0 ? String(x.speaker) : undefined })));
    S().setSpeakers(opts.speakers ? speakersFromLabels(out.segments.map((x) => x.speaker), (n) => t("spk.defaultName", { n })) : []);
    S().log(t("act.sttDone", { n: out.segments.length, audio: out.audioSeconds.toFixed(0), secs: out.seconds.toFixed(1) }));
  } catch (e) {
    S().log(t("act.sttStopped", { err: String(e) }));
  } finally {
    unlisten();
    S().set({ stt: { ...S().stt, running: false, phase: "idle" } });
  }
}

export function cancelStt() {
  api.sttCancel().catch(() => {});
}

/**
 * Phân biệt người nói theo mốc các câu hiện có (từ OCR, STT hay SRT), chạy trong Editor.
 * Thay toàn bộ nhãn và danh sách nhân vật; câu không xác định được để trống.
 */
export async function detectSpeakers(opts: { threshold: number; speakers: number }) {
  const s = S();
  const { video } = s;
  if (!video) return s.log(t("act.noVideo"));
  if (!video.has_audio) return s.log(t("spk.noAudio"));
  if (s.speakersRun.running) return;
  const segs = s.segments.filter((x) => x.end > x.start);
  if (!segs.length) return s.log(t("spk.noCaptions"));
  if (s.speakers.length && !(await confirmDialog(t("spk.confirmReplace", { n: s.speakers.length })))) return;

  s.set({ speakersRun: { running: true, phase: "audio", done: 0, total: segs.length } });
  s.log(t("spk.start", { n: segs.length }));
  const unlisten = await listen<SpeakersProgress>("speakers-progress", (e) => S().set({ speakersRun: { ...e.payload, running: true } }));
  try {
    const started = performance.now();
    const out = await api.speakersDetect({ path: video.path, spans: segs.map((x) => ({ start: x.start, end: x.end })), options: opts });
    if (S().video?.path !== video.path) return;
    const label = new Map(segs.map((x, i) => [x.id, out.labels[i]]));
    // Câu có thể đã bị sửa trong lúc chạy; chỉ gán cho câu còn đúng id.
    S().set({ segments: S().segments.map((x) => (label.has(x.id) ? { ...x, speaker: label.get(x.id)! > 0 ? String(label.get(x.id)) : undefined } : x)) });
    S().setSpeakers(speakersFromLabels(out.labels, (n) => t("spk.defaultName", { n }), out.speakers));
    const unknown = out.labels.filter((l) => l === 0).length;
    S().log(t("spk.done", { n: out.speakers.length, unknown, secs: ((performance.now() - started) / 1000).toFixed(1) }));
  } catch (e) {
    S().log(t("spk.stopped", { err: String(e) }));
  } finally {
    unlisten();
    S().set({ speakersRun: { ...S().speakersRun, running: false, phase: "idle" } });
  }
}

export function cancelSpeakers() {
  api.speakersCancel().catch(() => {});
}

/**
 * Hộp xác nhận của hệ điều hành. Không dùng `window.confirm`: webview macOS (wry) không cài hộp thoại JS
 * nên nó trả false ngay, nút bấm như không có tác dụng.
 */
export const confirmDialog = (message: string) =>
  ask(message, { kind: "warning", okLabel: t("dlg.ok"), cancelLabel: t("dlg.cancel") });

/** Số câu mỗi lần gọi server: đủ nhỏ để tiến độ và nút Huỷ phản hồi nhanh (server nhận tối đa 50). */
const TRANSLATE_BATCH = 10;
/** Số câu gốc đứng trước lô gửi kèm làm ngữ cảnh, như server dùng giữa các câu trong lô. */
const TRANSLATE_CONTEXT = 5;
let translateCancelled = false;
/** Đánh thức vòng dịch đang chờ OCR chốt thêm câu (để nút Huỷ có tác dụng ngay). */
let wakeTranslate: (() => void) | null = null;

/**
 * Khoá nhận diện câu qua các lần OCR cập nhật danh sách (id bị đánh lại mỗi lần):
 * câu đã chốt giữ nguyên mốc bắt đầu và chữ gốc.
 */
const segKey = (x: Segment) => `${x.start}|${x.src}`;

/** Chờ danh sách câu đổi, OCR dừng hoặc người dùng bấm Huỷ. */
function segmentsChanged(): Promise<void> {
  return new Promise((resolve) => {
    const done = () => {
      off();
      wakeTranslate = null;
      resolve();
    };
    const off = useEditor.subscribe((s, p) => {
      if (s.segments !== p.segments || !s.ocr.running) done();
    });
    wakeTranslate = done;
  });
}

/**
 * Dịch cột gốc sang `target` qua `POST /translate` của server, theo thứ tự thời gian, từng lô.
 * `onlyEmpty` bỏ qua câu đã có bản dịch (giữ bản người dùng đã sửa). Câu lỗi giữ nguyên bản dịch cũ.
 * Đang quét OCR thì dịch tiếp các câu mới chốt cho tới khi quét xong.
 */
export async function runTranslate(opts: { source: string; target: string; onlyEmpty: boolean }) {
  const s = S();
  if (s.translate.running) return;
  const session = useSession.getState().session;
  if (!session || session.guest || !session.token) return s.log(t("act.trGuest"));
  const sent = new Set<string>();
  const pending = () => S().segments.filter((x) => x.src.trim() && !sent.has(segKey(x)) && (!opts.onlyEmpty || !x.vi.trim()));
  const first = pending();
  if (!first.length && !s.ocr.running) return s.log(t("act.trNothing"));
  const overwrite = first.filter((x) => x.vi.trim()).length;
  if (overwrite && !(await confirmDialog(t("act.trConfirmOverwrite", { n: overwrite })))) return;

  const server = settingsStore.get("serverUrl", DEFAULT_SERVER);
  const video = s.video?.path;
  const started = performance.now();
  let done = 0;
  let failed = 0;
  translateCancelled = false;
  s.set({ translate: { running: true, done: 0, total: first.length } });
  s.log(t("act.trStart", { n: first.length, lang: langName(opts.target), server }));
  try {
    // Người dùng có thể đã mở video khác hoặc bấm Huỷ trong lúc chờ lô trước.
    while (!translateCancelled && S().video?.path === video) {
      const todo = pending();
      if (!todo.length) {
        if (!S().ocr.running) break;
        await segmentsChanged();
        continue;
      }
      const batch = todo.slice(0, TRANSLATE_BATCH);
      batch.forEach((x) => sent.add(segKey(x)));
      const all = S().segments.filter((x) => x.src.trim());
      const at = Math.max(0, all.findIndex((x) => segKey(x) === segKey(batch[0])));
      const context = all.slice(Math.max(0, at - TRANSLATE_CONTEXT), at).map((x) => x.src);
      const out = await api.translateLines(server, session.token, opts.source, opts.target, batch.map((x) => x.src), context);
      if (S().video?.path !== video) break;
      batch.forEach((seg, j) => {
        const r = out[j];
        // Tìm lại theo khoá: trong lúc chờ server, OCR có thể đã thay danh sách và đánh lại id.
        const segsNow = S().segments;
        const idx = r?.text ? segsNow.findIndex((x) => segKey(x) === segKey(seg)) : -1;
        if (idx >= 0) S().updateSegment(segsNow[idx].id, { vi: opts.target === "vi" ? withPronouns(segsNow, idx, S().pronouns, r.text) : r.text });
        else if (!r?.text) {
          if (!failed) S().log(t("act.trLineFailed", { err: r?.error ?? "?" }));
          failed++;
        }
      });
      done += batch.length;
      S().set({ translate: { running: true, done, total: done + pending().length } });
    }
    const left = pending().length;
    if (left) S().log(t("act.trCancelled", { done, total: done + left }));
    else S().log(t("act.trDone", { n: done - failed, failed, secs: ((performance.now() - started) / 1000).toFixed(1) }));
  } catch (e) {
    const m = e instanceof Error ? e.message : String(e);
    S().log(t("act.trStopped", { err: m === "tr.signIn" || m === "tr.noApi" ? t(m as TKey) : m }));
  } finally {
    S().set({ translate: { ...S().translate, running: false } });
  }
}

/** Áp lại bảng xưng hô cho mọi câu đã dịch có nhân vật (sau khi sửa bảng hoặc gán lại nhân vật). */
export function applyPronounsToAll() {
  const { segments, pronouns } = S();
  let changed = 0;
  const next = segments.map((x, i) => {
    const vi = x.vi ? withPronouns(segments, i, pronouns) : x.vi;
    if (vi === x.vi) return x;
    changed++;
    return { ...x, vi };
  });
  S().set({ segments: next });
  S().log(t("pron.applied", { n: changed }));
}

export function cancelTranslate() {
  translateCancelled = true;
  wakeTranslate?.();
}

/**
 * Lồng tiếng: đọc bản dịch từng câu bằng giọng của nhân vật (câu chưa gán nhân vật dùng `voice`) qua
 * `POST /speak` của server, ghép thành một file đặt từ 0 trên track lồng tiếng, thay clip lồng tiếng cũ.
 * Câu đã đọc được lưu ở `work/dub-cache`, nên chạy lại chỉ đọc các câu đã sửa.
 */
export async function runDub(opts: { language: string; voice: string }) {
  const s = S();
  const { video } = s;
  if (!video) return s.log(t("act.noVideo"));
  if (s.dub.running) return;
  const session = useSession.getState().session;
  if (!session || session.guest || !session.token) return s.log(t("dub.guest"));
  const voiceOf = new Map(s.speakers.map((x) => [x.id, x.voice]));
  const lines: DubLine[] = s.segments
    .filter((x) => x.vi.trim() && x.end > x.start)
    .map((x) => ({ id: x.id, startMs: x.start, endMs: x.end, text: x.vi.trim(), voice: (x.speaker && voiceOf.get(x.speaker)) || opts.voice }));
  if (!lines.length) return s.log(t("dub.nothing"));
  const track = s.tracks.find((x) => x.dub);
  if (track?.locked) return s.log(t("act.trackLocked"));

  const server = settingsStore.get("serverUrl", DEFAULT_SERVER);
  const dir = s.workDir || s.outDir || dirOf(video.path);
  const old = s.clips.filter((c) => c.speech && s.tracks.find((x) => x.id === c.trackId)?.dub);
  const oldAssets = s.assets.filter((a) => old.some((c) => c.assetId === a.id));
  const output = `${dir}/${baseName(video.path)}.dub.${opts.language}.${Date.now()}.m4a`;
  const totalMs = Math.max(timelineEnd(s), lines[lines.length - 1].endMs);
  s.set({ dub: { running: true, phase: "speak", done: 0, total: lines.length } });
  s.log(t("dub.start", { n: lines.length, server }));
  const unlisten = await listen<DubProgress>("dub-progress", (e) => S().set({ dub: { running: true, ...e.payload } }));
  const started = performance.now();
  try {
    const out = await api.dubSpeak({
      server, token: session.token, language: opts.language, lines, totalMs, cacheDir: `${dir}/dub-cache`, output, replace: oldAssets.map((a) => a.path),
    });
    if (S().video?.path !== video.path) return;
    for (const a of oldAssets) S().removeAsset(a.id);
    let dubTrack = S().tracks.find((x) => x.dub)?.id;
    if (!dubTrack) {
      dubTrack = S().addTrack("audio");
      S().setTrack(dubTrack, { dub: true });
    }
    const asset = S().addAsset(await api.probeMedia(out.output));
    const clip = S().placeAsset(asset.id, 0, dubTrack);
    const placed = S().clips.find((c) => c.id === clip);
    if (placed) S().patchClips({ [placed.id]: { speech: out.spans } });
    else S().log(t("act.trackLocked"));
    // Track lồng tiếng đã có clip khác chồng lên: clip mới sang track thường, không hạ âm gốc.
    if (placed && placed.trackId !== dubTrack) S().log(t("dub.trackBusy"));
    loadAssetData(asset.id);
    S().log(t("dub.done", { n: out.spoken, cached: out.cached, failed: out.failed.length, overrun: out.overrun.length, secs: ((performance.now() - started) / 1000).toFixed(1) }));
    if (out.failed.length) S().log(t("dub.lineFailed", { id: out.failed[0].id, err: out.failed[0].error }));
    if (out.overrun.length) S().log(t("dub.overrunHint", { ids: out.overrun.slice(0, 10).join(", ") }));
    await autosaveProject();
  } catch (e) {
    const m = e instanceof Error ? e.message : String(e);
    S().log(t("dub.stopped", { err: m.startsWith("dub.") ? t(m as TKey) : m }));
  } finally {
    unlisten();
    S().set({ dub: { ...S().dub, running: false, phase: "idle" } });
  }
}

export function cancelDub() {
  api.dubCancel().catch(() => {});
}

/** Vùng mặc định khi người dùng chưa khoanh: dải dưới của khung hình, nơi phụ đề thường nằm. */
export const DEFAULT_SUB_REGION = { x: 0.05, y: 0.74, w: 0.9, h: 0.22 };

/**
 * Quét phụ đề cứng. Câu đã chốt được đưa vào danh sách và tự lưu vào file dự án trong lúc quét;
 * bị dừng (bấm Huỷ, đóng dự án, app lỗi) thì `resume` = true quét tiếp từ chỗ đã lưu với đúng tham số cũ.
 */
export async function runOcr(opts: { scanFps: number; model: OcrModel; everyFrame: boolean; fullFrame: boolean }, resume = false) {
  const s = S();
  const { video } = s;
  if (!video) return s.log(t("act.noVideo"));
  if (s.ocr.running) return;
  const prev = s.ocrResume?.path === video.path ? s.ocrResume : null;
  let job: OcrResume;
  // Câu của các lần quét trước (giữ cả bản dịch người dùng đã sửa).
  let keep: Segment[] = [];
  if (resume && prev) {
    job = prev;
    keep = s.segments.filter((x) => x.start < prev.doneMs);
    s.log(t("act.ocrResume", { time: formatTime(prev.doneMs) }));
  } else {
    let region = opts.fullFrame ? null : s.linkOcr ? s.regions.dialog : s.regions.ocr ?? s.regions.dialog;
    if (!opts.fullFrame && !region) {
      region = DEFAULT_SUB_REGION;
      s.set({ regions: { ...s.regions, dialog: region } });
      s.log(t("act.defaultRegion"));
    }
    if (s.segments.length && !(await confirmDialog(t("act.confirmReplace", { n: s.segments.length })))) return;
    job = { path: video.path, doneMs: 0, region: region ?? null, scanFps: opts.scanFps, model: opts.model, everyFrame: opts.everyFrame };
    s.log(t("act.ocrStart", { model: opts.model, fps: opts.scanFps, every: opts.everyFrame ? t("act.ocrEveryFrame") : "" }));
  }
  // OcrSegment (Rust) → Segment của editor (srt.ts, dạng chuẩn ở frontend): chữ OCR là câu gốc, chưa có bản dịch.
  // Giữ bản dịch đã có của câu đã chốt (người dùng có thể đang dịch trong lúc quét).
  const withKept = (xs: OcrSegment[]): Segment[] => {
    const vi = new Map(S().segments.filter((x) => x.vi).map((x) => [segKey(x), x.vi]));
    return [...keep, ...xs.map((x) => ({ id: 0, start: x.start, end: x.end, src: x.text, vi: "" }))]
      .map((x, i) => ({ ...x, id: i + 1, vi: x.vi || (vi.get(segKey(x)) ?? "") }));
  };
  const stillHere = () => S().video?.path === video.path;

  s.set({ ocrResume: job, ocr: { running: true, phase: "model", done: 0, total: 0, ocrCalls: 0, lastText: "" } });
  autosaveProject();
  const unlisten = await listen<OcrProgress>("ocr-progress", (e) => {
    S().set({ ocr: { ...e.payload, running: true } });
  });
  const unlistenPartial = await listen<OcrPartial>("ocr-partial", (e) => {
    // Người dùng có thể đã mở video khác trong lúc quét.
    if (!stillHere()) return;
    S().setSegments(withKept(e.payload.segments));
    S().set({ ocrResume: { ...job, doneMs: e.payload.doneMs } });
    autosaveProject();
  });
  try {
    const out = await api.ocrExtract({
      path: video.path,
      width: video.width,
      height: video.height,
      durationMs: video.duration_ms,
      region: job.region,
      scanFps: job.scanFps,
      model: job.model,
      everyFrame: job.everyFrame,
      startMs: job.doneMs,
    });
    if (!stillHere()) return;
    const segs = withKept(out.segments);
    S().setSegments(segs);
    if (out.complete) {
      S().set({ ocrResume: null });
      S().log(t("act.ocrDone", { n: segs.length, frames: out.frames, calls: out.ocrCalls, secs: out.seconds.toFixed(1) }));
    } else {
      S().set({ ocrResume: { ...job, doneMs: out.doneMs } });
      S().log(t("act.ocrPaused", { time: formatTime(out.doneMs), n: segs.length }));
    }
    await autosaveProject();
  } catch (e) {
    S().log(t("act.ocrStopped", { err: String(e) }));
  } finally {
    unlisten();
    unlistenPartial();
    S().set({ ocr: { ...S().ocr, running: false, phase: "idle" } });
  }
}

export function cancelOcr() {
  api.ocrCancel().catch(() => {});
}

export type ExportRes = "source" | "1080" | "720" | "1080v";

/** Kích thước xuất (chẵn) theo lựa chọn; giữ tỉ lệ sequence trừ khung dọc 9:16. */
export function exportSize(res: ExportRes): { W: number; H: number } {
  const { W, H } = sequenceSize(S());
  const even = (v: number) => Math.max(2, Math.round(v / 2) * 2);
  if (res === "1080v") return { W: 1080, H: 1920 };
  if (res === "source") return { W: even(W), H: even(H) };
  const h = res === "1080" ? 1080 : 720;
  return { W: even((W * h) / H), H: h };
}

/**
 * Xuất timeline: dựng `RenderJob` từ track/clip (đúng như preview), vẽ chữ và phụ đề thành chuỗi PNG,
 * rồi để Rust chạy một lệnh ffmpeg. Tiến độ qua sự kiện `render-progress`.
 */
export async function exportVideo(opts: { res: ExportRes; encoder: string }) {
  const s = S();
  if (s.render.running) return;
  const durationMs = Math.round(timelineEnd(s));
  if (!durationMs) return s.log(t("act.renderEmpty"));
  const base = s.video ? baseName(s.video.path) : "glotalk";
  const output = await save({ defaultPath: `${s.outDir ? s.outDir + "/" : ""}${base}.edit.mp4`, filters: [{ name: "MP4", extensions: ["mp4"] }] });
  if (!output) return;

  const { W, H } = exportSize(opts.res);
  const seq = sequenceSize(s);
  const fps = s.video?.fps || 30;
  const assets = new Map(s.assets.map((a) => [a.id, a]));
  const tracks = new Map(s.tracks.map((x) => [x.id, x]));
  const byStart = (a: Clip, b: Clip) => a.start - b.start;
  // UV của shader preview dùng thang 0..1, chromakey của ffmpeg chuẩn hoá thêm 1/√2.
  const KEY_K = 0.25 / Math.SQRT2;

  const layers: RenderLayer[] = [];
  for (const tk of s.tracks) {
    if (tk.kind !== "video" || tk.hidden) continue;
    for (const c of s.clips.filter((x) => x.trackId === tk.id).sort(byStart)) {
      const a = c.assetId ? assets.get(c.assetId) : undefined;
      if (!a || a.kind === "audio") continue;
      const fx = clipFx(c);
      layers.push({
        path: a.path, image: a.kind === "image", startMs: c.start, durMs: c.dur, inMs: c.in, srcW: a.width, srcH: a.height,
        opacity: fx.opacity, scale: fx.scale, x: fx.x, y: fx.y,
        key: fx.key.enabled ? { color: fx.key.color, similarity: Math.max(0.0001, fx.key.similarity * KEY_K), blend: fx.key.smoothness * KEY_K, spill: fx.key.spill } : null,
      });
    }
  }
  const audio: RenderAudio[] = [];
  for (const c of s.clips) {
    const tk = tracks.get(c.trackId);
    const a = c.assetId ? assets.get(c.assetId) : undefined;
    if (!tk || tk.kind !== "audio" || !a || !trackAudible(s.tracks, tk)) continue;
    const volume = tk.gain * clipFx(c).volume;
    if (volume > 0) audio.push({ path: a.path, startMs: c.start, durMs: c.dur, inMs: c.in, volume, volumeL: tk.gainL ?? 1, volumeR: tk.gainR ?? 1, dub: !!tk.dub });
  }

  let unlisten: (() => void) | undefined;
  S().set({ render: { running: true, doneMs: 0, totalMs: durationMs } });
  try {
    const graphics = await renderGraphics(W, H, H / seq.H, durationMs);
    S().log(t("act.renderStart", { w: W, h: H, enc: opts.encoder, file: output }));
    unlisten = await listen<RenderProgress>("render-progress", (e) => S().set({ render: { running: true, ...e.payload } }));
    const t0 = performance.now();
    await api.renderVideo({ output, width: W, height: H, fps, durationMs, encoder: opts.encoder, layers, audio, graphics, grade: gradeForExport(s.grade), duck: duckSpans(s.tracks, s.clips) });
    S().log(t("act.renderDone", { file: output, secs: ((performance.now() - t0) / 1000).toFixed(1) }));
  } catch (e) {
    S().log(t("act.renderError", { err: String(e) }));
  } finally {
    unlisten?.();
    S().set({ render: { ...S().render, running: false } });
  }
}

export function cancelRender() {
  api.renderCancel().catch(() => {});
}

/**
 * Chữ (track Text) và phụ đề (câu gốc / bản dịch) thành chuỗi PNG trong suốt, mỗi ảnh ứng với một khoảng thời gian không đổi nội dung,
 * kèm file ffconcat ghi thời lượng từng ảnh. Không có chữ nào thì trả về null.
 */
async function renderGraphics(W: number, H: number, k: number, durationMs: number): Promise<string | null> {
  const s = S();
  type Item = { start: number; end: number; draw: (g: CanvasRenderingContext2D) => void };
  const items: Item[] = [];
  for (const tk of s.tracks) {
    if (tk.kind !== "text" || tk.hidden) continue;
    for (const c of s.clips.filter((x) => x.trackId === tk.id && x.text)) {
      const fx = clipFx(c);
      items.push({ start: c.start, end: c.start + c.dur, draw: (g) => drawText(g, { text: c.text!, ...fx }, W, H, k) });
    }
  }
  if (s.subStyle.enabled)
    for (const seg of s.segments) {
      const text = captionText(s.tracks, seg);
      if (text) items.push({ start: seg.start, end: seg.end, draw: (g) => drawCaption(g, text, s.subStyle, W, H, k) });
    }
  if (!items.length) return null;

  const cuts = [...new Set([0, durationMs, ...items.flatMap((x) => [x.start, x.end])])].filter((v) => v >= 0 && v <= durationMs).sort((a, b) => a - b);
  const dir = await api.overlayBegin();
  const canvas = document.createElement("canvas");
  canvas.width = W;
  canvas.height = H;
  const g = canvas.getContext("2d")!;
  const written = new Map<string, string>();
  const lines = ["ffconcat version 1.0"];
  let last = "";
  for (let i = 0; i < cuts.length - 1; i++) {
    const [a, b] = [cuts[i], cuts[i + 1]];
    if (b <= a) continue;
    const active = items.filter((x) => x.start <= a && a < x.end);
    const sig = active.map((x) => items.indexOf(x)).join(",");
    let name = written.get(sig);
    if (!name) {
      name = `g${written.size}.png`;
      g.clearRect(0, 0, W, H);
      active.forEach((x) => x.draw(g));
      const blob = await new Promise<Blob>((ok, fail) => canvas.toBlob((bl) => (bl ? ok(bl) : fail(new Error("toBlob"))), "image/png"));
      await api.overlayWrite(name, new Uint8Array(await blob.arrayBuffer()));
      written.set(sig, name);
    }
    lines.push(`file '${name}'`, `duration ${((b - a) / 1000).toFixed(3)}`);
    last = name;
  }
  // Demuxer concat bỏ qua thời lượng của mục cuối nếu không lặp lại tên file.
  lines.push(`file '${last}'`);
  const list = `${dir}/graphics.ffconcat`;
  await api.writeText(list, lines.join("\n") + "\n");
  return list;
}
