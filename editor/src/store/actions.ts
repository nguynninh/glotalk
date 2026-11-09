import { open, save } from "@tauri-apps/plugin-dialog";
import { listen } from "@tauri-apps/api/event";
import { api, formatTime, MediaInfo, OcrModel, OcrProgress, Region, RenderAudio, RenderLayer, RenderProgress, VideoInfo } from "../lib/api";
import { parseSrt, Segment, toSrt } from "../lib/srt";
import { Clip, clipFx, Track, trackAudible } from "../lib/timeline";
import { drawCaption, drawText } from "../lib/graphics";
import { SubStyle } from "../lib/subStyle";
import { ColorGrade, gradeForExport } from "../lib/colorGrade";
import { RegionKind, sequenceSize, timelineEnd, useEditor } from "./editor";
import { t } from "../i18n";

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
  subStyle: SubStyle;
  grade: ColorGrade;
  regions: Partial<Record<RegionKind, Region>>;
  uid: number;
}
let projectPath = "";

export async function saveProject(saveAs = false) {
  const s = S();
  let file = projectPath;
  if (!file || saveAs) {
    const name = s.video ? `${baseName(s.video.path)}.glotalk` : "project.glotalk";
    const picked = await save({ defaultPath: s.outDir ? `${s.outDir}/${name}` : name, filters: [{ name: "Glotalk", extensions: ["glotalk"] }] });
    if (!picked) return;
    file = picked;
  }
  const data: ProjectFile = {
    version: 1,
    video: s.video,
    assets: s.assets.map(({ thumbs: _t, peaks: _p, ...a }) => a),
    tracks: s.tracks,
    clips: s.clips,
    segments: s.segments,
    subStyle: s.subStyle,
    grade: s.grade,
    regions: s.regions,
    uid: s.uid,
  };
  try {
    await api.writeText(file, JSON.stringify(data, null, 1));
    projectPath = file;
    S().log(t("act.projectSaved", { file }));
  } catch (e) {
    S().log(t("act.projectError", { err: String(e) }));
  }
}

export async function openProject() {
  const file = await open({ filters: [{ name: "Glotalk", extensions: ["glotalk"] }] });
  if (typeof file !== "string") return;
  try {
    const d = JSON.parse(await api.readText(file)) as ProjectFile;
    if (d.version !== 1) throw new Error(`version ${d.version}`);
    S().set({
      video: d.video,
      assets: d.assets.map((a) => ({ ...a, thumbs: [], peaks: [] })),
      tracks: d.tracks,
      clips: d.clips,
      subStyle: d.subStyle,
      grade: d.grade,
      regions: d.regions ?? {},
      uid: d.uid,
      selectedClipId: null,
      time: 0,
      outDir: dirOf(file),
    });
    S().setSegments(d.segments ?? []);
    projectPath = file;
    S().assets.forEach((a) => loadAssetData(a.id));
    S().log(t("act.projectOpened", { file }));
  } catch (e) {
    S().log(t("act.projectError", { err: String(e) }));
  }
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
  const { video, outDir } = S();
  if (!video) return S().log(t("act.noVideo"));
  const out = `${outDir || dirOf(video.path)}/${baseName(video.path)}.16k.wav`;
  S().log(t("act.extractingAudio"));
  try {
    await api.extractAudio(video.path, out);
    S().log(t("act.audioExtracted", { file: out }));
  } catch (e) {
    S().log(t("act.audioError", { err: String(e) }));
  }
}

/** Vùng mặc định khi người dùng chưa khoanh: dải dưới của khung hình, nơi phụ đề thường nằm. */
export const DEFAULT_SUB_REGION = { x: 0.05, y: 0.74, w: 0.9, h: 0.22 };

export async function runOcr(opts: { scanFps: number; model: OcrModel; everyFrame: boolean; fullFrame: boolean }) {
  const s = S();
  const { video } = s;
  if (!video) return s.log(t("act.noVideo"));
  if (s.ocr.running) return;
  let region = opts.fullFrame ? null : s.linkOcr ? s.regions.dialog : s.regions.ocr ?? s.regions.dialog;
  if (!opts.fullFrame && !region) {
    region = DEFAULT_SUB_REGION;
    s.set({ regions: { ...s.regions, dialog: region } });
    s.log(t("act.defaultRegion"));
  }
  if (s.segments.length && !window.confirm(t("act.confirmReplace", { n: s.segments.length }))) return;

  s.set({ ocr: { running: true, phase: "model", done: 0, total: 0, ocrCalls: 0, lastText: "" } });
  s.log(t("act.ocrStart", { model: opts.model, fps: opts.scanFps, every: opts.everyFrame ? t("act.ocrEveryFrame") : "" }));
  const unlisten = await listen<OcrProgress>("ocr-progress", (e) => {
    S().set({ ocr: { ...e.payload, running: true } });
  });
  try {
    const out = await api.ocrExtract({
      path: video.path,
      width: video.width,
      height: video.height,
      durationMs: video.duration_ms,
      region: region ?? null,
      scanFps: opts.scanFps,
      model: opts.model,
      everyFrame: opts.everyFrame,
    });
    // Người dùng có thể đã mở video khác trong lúc quét.
    if (S().video?.path !== video.path) return;
    // OcrSegment (Rust) → Segment của editor (srt.ts, dạng chuẩn ở frontend): chữ OCR là câu gốc, chưa có bản dịch.
    S().setSegments(out.segments.map((x, i) => ({ id: i + 1, start: x.start, end: x.end, src: x.text, vi: "" })));
    S().log(t("act.ocrDone", { n: out.segments.length, frames: out.frames, calls: out.ocrCalls, secs: out.seconds.toFixed(1) }));
  } catch (e) {
    S().log(t("act.ocrStopped", { err: String(e) }));
  } finally {
    unlisten();
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
    if (volume > 0) audio.push({ path: a.path, startMs: c.start, durMs: c.dur, inMs: c.in, volume });
  }

  let unlisten: (() => void) | undefined;
  S().set({ render: { running: true, doneMs: 0, totalMs: durationMs } });
  try {
    const graphics = await renderGraphics(W, H, H / seq.H, durationMs);
    S().log(t("act.renderStart", { w: W, h: H, enc: opts.encoder, file: output }));
    unlisten = await listen<RenderProgress>("render-progress", (e) => S().set({ render: { running: true, ...e.payload } }));
    const t0 = performance.now();
    await api.renderVideo({ output, width: W, height: H, fps, durationMs, encoder: opts.encoder, layers, audio, graphics, grade: gradeForExport(s.grade) });
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
 * Chữ (track Text) và phụ đề (C1) thành chuỗi PNG trong suốt, mỗi ảnh ứng với một khoảng thời gian không đổi nội dung,
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
  const capTrack = s.tracks.find((x) => x.kind === "caption");
  if (s.subStyle.enabled && !capTrack?.hidden)
    for (const seg of s.segments) {
      const text = seg.vi || seg.src;
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
