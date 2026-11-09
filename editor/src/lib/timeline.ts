import { MediaInfo } from "./api";

/**
 * Mô hình timeline đa track. Thời gian là mili giây nguyên.
 * Track "caption" là track phụ đề đặc biệt (OCR/dịch/TTS), nội dung nằm ở `segments` của store, không phải `clips`.
 */
export type TrackKind = "video" | "audio" | "text" | "caption";

export interface Track {
  id: string;
  kind: TrackKind;
  /** Tên do người dùng đặt; rỗng thì dùng nhãn mặc định theo loại. */
  name: string;
  /** Track lồng tiếng mặc định (đích của bước TTS). */
  dub?: boolean;
  /** Do app tự tạo khi thả/thêm clip mà không còn chỗ: tự bỏ đi khi không còn clip nào. */
  auto?: boolean;
  hidden: boolean;
  locked: boolean;
  muted: boolean;
  solo: boolean;
  /** 0..1 */
  gain: number;
}

export interface MediaAsset extends MediaInfo {
  id: string;
  thumbs: string[];
  peaks: number[];
}

export interface Clip {
  id: string;
  trackId: string;
  /** Không có với clip chữ. */
  assetId?: string;
  /** Vị trí trên timeline. */
  start: number;
  dur: number;
  /** Điểm bắt đầu trong file nguồn. */
  in: number;
  /** Clip video và âm thanh của cùng một file nhập vào được liên kết: kéo, cắt, xoá cùng nhau. */
  linkId?: string;
  text?: string;
  /** Hiệu ứng; thiếu trường nào thì dùng giá trị trong DEFAULT_FX. */
  fx?: Partial<ClipFx>;
}

export interface ChromaKey {
  enabled: boolean;
  /** #rrggbb */
  color: string;
  /** Độ gần màu khoá bị xoá hẳn, 0..1 */
  similarity: number;
  /** Độ mềm vùng chuyển, 0..1 */
  smoothness: number;
  /** Khử viền màu khoá còn sót, 0..1 */
  spill: number;
}

export interface ClipFx {
  /** 0..1 */
  opacity: number;
  /** 1 = vừa khung */
  scale: number;
  /** Lệch tâm theo tỉ lệ khung hình, -1..1 */
  x: number;
  y: number;
  key: ChromaKey;
  /** Âm lượng clip 0..1, nhân với âm lượng track */
  volume: number;
  /** Clip chữ: cỡ chữ theo pixel khung hình sequence, màu chữ */
  fontSize: number;
  color: string;
}

export const DEFAULT_FX: ClipFx = {
  opacity: 1,
  scale: 1,
  x: 0,
  y: 0,
  key: { enabled: false, color: "#00ff00", similarity: 0.4, smoothness: 0.08, spill: 0.1 },
  volume: 1,
  fontSize: 72,
  color: "#ffffff",
};

export const clipFx = (c: Clip): ClipFx => ({ ...DEFAULT_FX, ...c.fx, key: { ...DEFAULT_FX.key, ...c.fx?.key } });

/** Ảnh tĩnh và chữ không có độ dài nguồn; mặc định đặt 5 giây. */
export const STILL_MS = 5000;
export const MIN_CLIP_MS = 100;

export const newTrack = (id: string, kind: TrackKind, extra: Partial<Track> = {}): Track => ({
  id, kind, name: "", hidden: false, locked: false, muted: false, solo: false, gain: 1, ...extra,
});

/** Thứ tự hiển thị từ trên xuống: chữ, video (số lớn ở trên, như Premiere), phụ đề, âm thanh. */
export function displayOrder(tracks: Track[]): Track[] {
  const of = (k: TrackKind) => tracks.filter((t) => t.kind === k);
  return [...of("text").reverse(), ...of("video").reverse(), ...of("caption"), ...of("audio")];
}

/** Mã ngắn kiểu NLE: V1, A2, T1, C1 — đánh số theo thứ tự trong cùng loại. */
export function trackCode(tracks: Track[], tk: Track): string {
  const n = tracks.filter((t) => t.kind === tk.kind).indexOf(tk) + 1;
  return { video: "V", audio: "A", text: "T", caption: "C" }[tk.kind] + n;
}

/** Loại track nhận được loại media này. */
export const acceptsAsset = (tk: Track, kind: MediaAsset["kind"]) =>
  (tk.kind === "video" && kind !== "audio") || (tk.kind === "audio" && kind === "audio");

/** Clip đang nằm dưới thời điểm `ms`. */
export const clipAt = (c: Clip, ms: number) => ms >= c.start && ms < c.start + c.dur;

export const clipEnd = (c: Clip) => c.start + c.dur;

/** Có chỗ trống cho đoạn [start, start+dur) trên track không (bỏ qua các clip trong `ignore`). */
export function fits(clips: Clip[], trackId: string, start: number, dur: number, ignore: Set<string> = new Set()) {
  if (start < 0) return false;
  return !clips.some((c) => c.trackId === trackId && !ignore.has(c.id) && start < clipEnd(c) && c.start < start + dur);
}

/** Track nào có nghe được không: không tắt tiếng, và nếu có track đang solo thì phải là track đó. */
export function trackAudible(tracks: Track[], tk: Track) {
  const anySolo = tracks.some((t) => t.kind === "audio" && t.solo);
  return !tk.muted && (!anySolo || tk.solo);
}

/** Thời điểm cuối cùng có nội dung trên timeline. */
export const contentEnd = (clips: Clip[], segEnd: number) => Math.max(segEnd, 0, ...clips.map(clipEnd));

/** Bỏ các track tự tạo đã trống. */
export const pruneTracks = (tracks: Track[], clips: Clip[]) =>
  tracks.filter((t) => !t.auto || clips.some((c) => c.trackId === t.id));
