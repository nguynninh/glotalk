import { create } from "zustand";
import { MediaInfo, OcrModel, Region, VideoInfo } from "../lib/api";
import { acceptsAsset, Clip, clipEnd, contentEnd, displayOrder, fits, MediaAsset, MIN_CLIP_MS, newTrack, pruneTracks, STILL_MS, Track, TrackKind } from "../lib/timeline";
import { Segment } from "../lib/srt";
import { defaultSubStyle, SubStyle } from "../lib/subStyle";
import { ColorGrade, defaultGrade } from "../lib/colorGrade";
import { t } from "../i18n";

export type RegionKind = "dialog" | "ocr";
export interface OcrState {
  running: boolean;
  phase: "idle" | "download" | "model" | "ocr" | "done";
  done: number;
  total: number;
  ocrCalls: number;
  lastText: string;
}

/** OCR dở dang của video `path`: quét tiếp từ `doneMs` với đúng tham số cũ. Lưu trong file dự án. */
export interface OcrResume {
  path: string;
  doneMs: number;
  region: Region | null;
  scanFps: number;
  model: OcrModel;
  everyFrame: boolean;
}

export interface SttState {
  running: boolean;
  phase: "idle" | "audio" | "connect" | "stt";
  doneS: number;
  totalS: number;
  lastText: string;
}

/** Dịch cột gốc sang ngôn ngữ đích qua server (`runTranslate`). */
export interface TranslateState {
  running: boolean;
  done: number;
  total: number;
}

export type InspectorTab = "caption" | "style" | "color" | "detect" | "export";

export interface AudioMix {
  /** Âm lượng tổng khi xem, 0..1 */
  master: number;
  masterMuted: boolean;
}
const MIX_KEY = "glotalk.audioMix";
const defaultMix: AudioMix = { master: 1, masterMuted: false };
const loadMix = (): AudioMix => {
  try {
    const m = JSON.parse(localStorage.getItem(MIX_KEY) ?? "null");
    return m ? { master: Number(m.master ?? 1), masterMuted: !!m.masterMuted } : defaultMix;
  } catch {
    return defaultMix;
  }
};

export type TimelineTool = "select" | "trackFwd" | "ripple" | "razor" | "slip" | "pen" | "hand" | "type";

export const defaultTracks = (): Track[] => [
  newTrack("v1", "video"),
  newTrack("c1", "caption"),
  newTrack("a1", "audio"),
  newTrack("a2", "audio", { dub: true }),
];

interface EditorState {
  /** Video chính: quyết định fps/khung hình của sequence, là nguồn cho preview và OCR. */
  video: VideoInfo | null;
  outDir: string;
  /** Thư mục file trung gian (wav cho STT…) của dự án; rỗng thì dùng `outDir`. */
  workDir: string;
  peaksPerSec: number;

  assets: MediaAsset[];
  tracks: Track[];
  clips: Clip[];
  selectedClipId: string | null;
  /** Công cụ timeline, như thanh công cụ của Premiere. */
  tool: TimelineTool;
  /** Hít vào playhead và mép clip khi kéo. */
  snap: boolean;
  /** Bật thì clip hình/tiếng liên kết được chọn, kéo, tách, xoá cùng nhau. */
  linkedSelection: boolean;
  /** Tiến độ xuất video */
  render: { running: boolean; doneMs: number; totalMs: number };
  /** Đang chờ bấm lên monitor để lấy màu khoá cho clip này. */
  pickingKeyFor: string | null;
  /** Bộ đếm sinh id cho asset/track/clip. */
  uid: number;

  segments: Segment[];
  selectedId: number | null;
  nextId: number;

  time: number;
  playing: boolean;
  /** pixel trên timeline cho mỗi giây */
  pxPerSec: number;
  /** Yêu cầu monitor tua tới (monitor lắng nghe trường này) */
  seekTo: { ms: number; n: number } | null;

  subStyle: SubStyle;
  grade: ColorGrade;
  regions: Partial<Record<RegionKind, Region>>;
  linkOcr: boolean;
  selectingRegion: RegionKind | null;
  inspectorTab: InspectorTab;
  mix: AudioMix;
  logs: string[];
  ocr: OcrState;
  ocrResume: OcrResume | null;
  stt: SttState;
  translate: TranslateState;

  set: (p: Partial<EditorState>) => void;
  log: (msg: string) => void;
  seek: (ms: number) => void;
  setSegments: (segs: Segment[]) => void;
  updateSegment: (id: number, p: Partial<Segment>) => void;
  addSegmentAt: (ms: number) => void;
  removeSegment: (id: number) => void;
  splitAt: (ms: number) => void;
  setMix: (p: Partial<AudioMix>) => void;

  addTrack: (kind: Exclude<TrackKind, "caption">) => string;
  removeTrack: (id: string) => void;
  setTrack: (id: string, p: Partial<Track>) => void;
  addAsset: (info: MediaInfo) => MediaAsset;
  setAsset: (id: string, p: Partial<Pick<MediaAsset, "thumbs" | "peaks">>) => void;
  removeAsset: (id: string) => void;
  /** Đặt asset lên timeline tại `ms`; video có tiếng sinh thêm clip âm thanh liên kết. Trả về id clip chính, hoặc null nếu track bị khoá. */
  placeAsset: (assetId: string, ms: number, trackId?: string) => string | null;
  addTextClip: (ms: number, trackId?: string) => void;
  /** Áp nhiều thay đổi clip cùng lúc (kéo clip kèm clip liên kết). */
  patchClips: (patches: Record<string, Partial<Clip>>) => void;
  removeClip: (id: string) => void;
  splitClip: (id: string, ms: number) => void;
  unlinkClip: (id: string) => void;
  selectClip: (id: string | null) => void;
  togglePlay: () => void;
  /** Bỏ track tự tạo đã trống (gọi khi kết thúc kéo clip, không gọi giữa lúc kéo để track đích không biến mất dưới con trỏ). */
  pruneEmptyTracks: () => void;
}

const now = () => new Date().toLocaleTimeString("vi-VN", { hour12: false });

export const useEditor = create<EditorState>((set, get) => ({
  video: null,
  outDir: "",
  workDir: "",
  peaksPerSec: 50,
  assets: [],
  tracks: defaultTracks(),
  clips: [],
  selectedClipId: null,
  tool: "select",
  snap: true,
  linkedSelection: true,
  pickingKeyFor: null,
  render: { running: false, doneMs: 0, totalMs: 0 },
  uid: 1,
  segments: [],
  selectedId: null,
  nextId: 1,
  time: 0,
  playing: false,
  pxPerSec: 60,
  seekTo: null,
  subStyle: defaultSubStyle,
  grade: defaultGrade,
  regions: {},
  linkOcr: true,
  selectingRegion: null,
  inspectorTab: "caption",
  mix: loadMix(),
  logs: [`[${now()}] ${t("store.ready")}`],
  ocr: { running: false, phase: "idle", done: 0, total: 0, ocrCalls: 0, lastText: "" },
  ocrResume: null,
  stt: { running: false, phase: "idle", doneS: 0, totalS: 0, lastText: "" },
  translate: { running: false, done: 0, total: 0 },

  set: (p) => set(p),
  log: (msg) => set((s) => ({ logs: [...s.logs.slice(-499), `[${now()}] ${msg}`] })),
  seek: (ms) => {
    const v = get().video;
    const frame = 1000 / (v?.fps || 30);
    // Làm tròn theo frame để thời gian phụ đề luôn khớp khung hình.
    const t = Math.round(Math.min(Math.max(0, Math.round(ms / frame) * frame), timelineEnd(get())));
    set((s) => ({ time: t, seekTo: { ms: t, n: (s.seekTo?.n ?? 0) + 1 } }));
  },
  setSegments: (segs) =>
    set({ segments: [...segs].sort((a, b) => a.start - b.start), nextId: Math.max(0, ...segs.map((s) => s.id)) + 1, selectedId: null }),
  updateSegment: (id, p) =>
    set((s) => ({ segments: s.segments.map((x) => (x.id === id ? { ...x, ...p } : x)).sort((a, b) => a.start - b.start) })),
  addSegmentAt: (ms) => {
    const { segments, nextId } = get();
    const next = segments.find((s) => s.start > ms);
    const end = Math.min(ms + 2000, next?.start ?? Infinity, timelineEnd(get()));
    if (segments.some((s) => ms >= s.start && ms < s.end) || end - ms < 200) {
      get().log(t("store.noRoom"));
      return;
    }
    const seg: Segment = { id: nextId, start: ms, end, src: "", vi: t("store.newCaption") };
    set({ segments: [...segments, seg].sort((a, b) => a.start - b.start), nextId: nextId + 1, selectedId: seg.id, selectedClipId: null, inspectorTab: "caption" });
  },
  removeSegment: (id) => set((s) => ({ segments: s.segments.filter((x) => x.id !== id), selectedId: s.selectedId === id ? null : s.selectedId })),
  splitAt: (ms) => {
    const { segments, nextId } = get();
    const seg = segments.find((s) => ms > s.start + 100 && ms < s.end - 100);
    if (!seg) return;
    const right: Segment = { ...seg, id: nextId, start: ms };
    set({
      segments: segments.flatMap((s) => (s.id === seg.id ? [{ ...s, end: ms }, right] : [s])),
      nextId: nextId + 1,
      selectedId: right.id,
    });
  },
  setMix: (p) => set((s) => ({ mix: { ...s.mix, ...p } })),

  addTrack: (kind) => {
    const id = `t${get().uid}`;
    set((s) => ({ tracks: [...s.tracks, newTrack(id, kind)], uid: s.uid + 1 }));
    return id;
  },
  removeTrack: (id) =>
    set((s) => {
      const tk = s.tracks.find((t) => t.id === id);
      if (!tk || tk.kind === "caption") return {};
      const gone = new Set(s.clips.filter((c) => c.trackId === id).map((c) => c.id));
      return {
        tracks: s.tracks.filter((t) => t.id !== id),
        clips: s.clips.filter((c) => !gone.has(c.id)),
        selectedClipId: s.selectedClipId && gone.has(s.selectedClipId) ? null : s.selectedClipId,
      };
    }),
  setTrack: (id, p) => set((s) => ({ tracks: s.tracks.map((t) => (t.id === id ? { ...t, ...p } : t)) })),

  addAsset: (info) => {
    const { assets, uid, video } = get();
    const found = assets.find((a) => a.path === info.path);
    if (found) return found;
    const asset: MediaAsset = { ...info, id: `m${uid}`, thumbs: [], peaks: [] };
    const first = !video && info.kind === "video";
    set({
      assets: [...assets, asset],
      uid: uid + 1,
      ...(first ? { video: { path: info.path, duration_ms: info.duration_ms, width: info.width, height: info.height, fps: info.fps, has_audio: info.has_audio } } : {}),
    });
    return asset;
  },
  setAsset: (id, p) => set((s) => ({ assets: s.assets.map((a) => (a.id === id ? { ...a, ...p } : a)) })),
  removeAsset: (id) =>
    set((s) => {
      const gone = s.assets.find((a) => a.id === id);
      const clips = s.clips.filter((c) => c.assetId !== id);
      return {
        assets: s.assets.filter((a) => a.id !== id),
        clips,
        tracks: pruneTracks(s.tracks, clips),
        selectedClipId: clips.some((c) => c.id === s.selectedClipId) ? s.selectedClipId : null,
        video: gone && s.video?.path === gone.path ? null : s.video,
      };
    }),

  placeAsset: (assetId, ms, trackId) => {
    const st = get();
    const asset = st.assets.find((a) => a.id === assetId);
    if (!asset) return null;
    let { tracks, uid } = st;
    const clips = [...st.clips];
    const start = snapFrame(st, ms);
    const dur = asset.kind === "image" ? STILL_MS : asset.duration_ms;
    if (dur < MIN_CLIP_MS) return null;
    // Track đích nếu nhận được và còn chỗ; nếu không thì track cùng loại đầu tiên còn chỗ; không có thì tạo track mới.
    const pickTrack = (kind: "video" | "audio", preferred?: string): string | null => {
      const pref = tracks.find((t) => t.id === preferred);
      if (pref && pref.locked) return null;
      if (pref && acceptsAsset(pref, kind === "video" ? asset.kind : "audio") && fits(clips, pref.id, start, dur)) return pref.id;
      const ok = displayOrder(tracks)
        .filter((t) => t.kind === kind && !t.locked && !(kind === "audio" && t.dub))
        .find((t) => fits(clips, t.id, start, dur));
      if (ok) return ok.id;
      const id = `t${uid++}`;
      tracks = [...tracks, newTrack(id, kind, { auto: true })];
      return id;
    };
    const mainKind = asset.kind === "audio" ? "audio" : "video";
    const mainTrack = pickTrack(mainKind, trackId);
    if (!mainTrack) return null;
    const linkId = asset.kind === "video" && asset.has_audio ? `l${uid++}` : undefined;
    const main: Clip = { id: `c${uid++}`, trackId: mainTrack, assetId, start, dur, in: 0, linkId };
    clips.push(main);
    if (linkId) {
      const audioTrack = pickTrack("audio");
      if (audioTrack) clips.push({ id: `c${uid++}`, trackId: audioTrack, assetId, start, dur, in: 0, linkId });
    }
    set({ tracks, clips, uid, selectedClipId: main.id, selectedId: null });
    return main.id;
  },

  addTextClip: (ms, trackId) => {
    const st = get();
    let { tracks, uid } = st;
    const start = snapFrame(st, ms);
    const pref = tracks.find((t) => t.id === trackId && t.kind === "text" && !t.locked);
    let tid = pref && fits(st.clips, pref.id, start, STILL_MS) ? pref.id : displayOrder(tracks).find((t) => t.kind === "text" && !t.locked && fits(st.clips, t.id, start, STILL_MS))?.id;
    if (!tid) {
      tid = `t${uid++}`;
      tracks = [...tracks, newTrack(tid, "text", { auto: true })];
    }
    const clip: Clip = { id: `c${uid++}`, trackId: tid, start, dur: STILL_MS, in: 0, text: t("store.newText") };
    set({ tracks, uid, clips: [...st.clips, clip], selectedClipId: clip.id, selectedId: null, inspectorTab: "caption" });
  },

  patchClips: (patches) => set((s) => ({ clips: s.clips.map((c) => (patches[c.id] ? { ...c, ...patches[c.id] } : c)) })),

  removeClip: (id) =>
    set((s) => {
      const c = s.clips.find((x) => x.id === id);
      if (!c || s.tracks.find((t) => t.id === c.trackId)?.locked) return {};
      const withLink = s.linkedSelection && c.linkId;
      const clips = s.clips.filter((x) => x.id !== id && !(withLink && x.linkId === c.linkId));
      return { clips, tracks: pruneTracks(s.tracks, clips), selectedClipId: null };
    }),

  splitClip: (id, ms) => {
    const st = get();
    const c = st.clips.find((x) => x.id === id);
    if (!c) return;
    const group = st.linkedSelection && c.linkId ? st.clips.filter((x) => x.linkId === c.linkId) : [c];
    const cut = Math.round(ms);
    if (group.some((x) => cut < x.start + MIN_CLIP_MS || cut > clipEnd(x) - MIN_CLIP_MS)) return;
    if (group.some((x) => st.tracks.find((t) => t.id === x.trackId)?.locked)) return;
    let uid = st.uid;
    // Tách cả nhóm thì phần phải là nhóm liên kết mới; tách riêng một clip thì phần phải giữ liên kết cũ.
    const newLink = !c.linkId ? undefined : group.length > 1 ? `l${uid++}` : c.linkId;
    const rights: Clip[] = [];
    const lefts: Record<string, Partial<Clip>> = {};
    let selected = id;
    for (const x of group) {
      const off = cut - x.start;
      const r: Clip = { ...x, id: `c${uid++}`, start: cut, dur: x.dur - off, in: x.in + off, linkId: newLink };
      rights.push(r);
      lefts[x.id] = { dur: off };
      if (x.id === id) selected = r.id;
    }
    set({ clips: [...st.clips.map((x) => (lefts[x.id] ? { ...x, ...lefts[x.id] } : x)), ...rights], uid, selectedClipId: selected });
  },

  unlinkClip: (id) =>
    set((s) => {
      const link = s.clips.find((c) => c.id === id)?.linkId;
      return link ? { clips: s.clips.map((c) => (c.linkId === link ? { ...c, linkId: undefined } : c)) } : {};
    }),

  selectClip: (id) => set(id ? { selectedClipId: id, selectedId: null } : { selectedClipId: null }),
  pruneEmptyTracks: () =>
    set((s) => {
      const tracks = pruneTracks(s.tracks, s.clips);
      return tracks.length === s.tracks.length ? {} : { tracks };
    }),
  togglePlay: () => {
    const s = get();
    if (s.playing) return set({ playing: false });
    // Đang ở cuối thì phát lại từ đầu.
    if (s.time >= timelineEnd(s) - 1) s.seek(0);
    if (timelineEnd(get()) > 0) set({ playing: true });
  },
}));

/** Khung hình sequence: theo video chính, mặc định 1920×1080. */
export const sequenceSize = (s: { video: { width: number; height: number } | null }) => ({
  W: s.video?.width || 1920,
  H: s.video?.height || 1080,
});

/** Làm tròn về frame gần nhất của sequence, không âm. */
const snapFrame = (s: { video: VideoInfo | null }, ms: number) => {
  const frame = 1000 / (s.video?.fps || 30);
  return Math.max(0, Math.round(Math.round(ms / frame) * frame));
};

/** Độ dài sequence: tới clip/phụ đề cuối cùng, ít nhất bằng video chính. */
export const timelineEnd = (s: { clips: Clip[]; segments: Segment[]; video: VideoInfo | null }) =>
  Math.max(s.video?.duration_ms ?? 0, contentEnd(s.clips, s.segments.length ? s.segments[s.segments.length - 1].end : 0));

useEditor.subscribe((s, prev) => {
  if (s.mix === prev.mix) return;
  try {
    localStorage.setItem(MIX_KEY, JSON.stringify(s.mix));
  } catch {
    /* bỏ qua */
  }
});


/** Segment đang hiện tại playhead */
export const currentSegment = (s: { segments: Segment[]; time: number }) =>
  s.segments.find((x) => s.time >= x.start && s.time < x.end) ?? null;
