import { MediaInfo } from "./api";

/**
 * Mô hình timeline đa track. Thời gian là mili giây nguyên.
 * Track "caption" là track phụ đề đặc biệt (OCR/dịch/TTS), nội dung nằm ở `segments` của store, không phải `clips`:
 * mỗi câu có câu gốc (`src`) và bản dịch (`vi`) chung mốc thời gian, ẩn/hiện riêng từng dòng.
 */
export type TrackKind = "video" | "audio" | "text" | "caption";

export interface Track {
  id: string;
  kind: TrackKind;
  /** Tên do người dùng đặt; rỗng thì dùng nhãn mặc định theo loại. */
  name: string;
  /** Track lồng tiếng mặc định (đích của bước TTS). */
  dub?: boolean;
  /** Track lồng tiếng: âm lượng còn lại 0..1 của các track khác khi có giọng lồng tiếng; thiếu là DUCK_GAIN. */
  duck?: number;
  /** Do app tự tạo khi thả/thêm clip mà không còn chỗ: tự bỏ đi khi không còn clip nào. */
  auto?: boolean;
  /** Track phụ đề: `hidden` là ẩn bản dịch, `srcHidden` là ẩn câu gốc. */
  hidden: boolean;
  srcHidden?: boolean;
  locked: boolean;
  muted: boolean;
  solo: boolean;
  /** 0..1 */
  gain: number;
  /** Âm lượng riêng tai trái/phải 0..1, nhân thêm vào `gain`; thiếu là 1. */
  gainL?: number;
  gainR?: number;
  /** Chiều cao track do người dùng kéo (px); thiếu thì dùng mặc định theo loại. */
  height?: number;
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
  /** Clip lồng tiếng: khoảng có giọng [bắt đầu, kết thúc) tính bằng ms trong file nguồn, để hạ âm các track khác. */
  speech?: [number, number][];
}

/** Âm lượng còn lại của âm gốc khi có giọng lồng tiếng, như `--background` của onplay-dub. */
export const DUCK_GAIN = 0.15;

/**
 * Khoảng cần hạ âm trên timeline: giọng của các clip lồng tiếng đang nghe được, đã dời theo vị trí clip
 * và cắt theo phần clip đang dùng. Trả về `gain` của track lồng tiếng đầu tiên có giọng.
 */
export function duckSpans(tracks: Track[], clips: Clip[]): { gain: number; spans: [number, number][] } | null {
  const spans: [number, number][] = [];
  let gain: number | null = null;
  for (const c of clips) {
    const tk = tracks.find((t) => t.id === c.trackId);
    if (!tk?.dub || !c.speech?.length || !trackAudible(tracks, tk)) continue;
    gain ??= tk.duck ?? DUCK_GAIN;
    for (const [a, b] of c.speech) {
      const s = Math.max(a, c.in) - c.in + c.start;
      const e = Math.min(b, c.in + c.dur) - c.in + c.start;
      if (e > s) spans.push([s, e]);
    }
  }
  if (gain === null || !spans.length) return null;
  spans.sort((x, y) => x[0] - y[0]);
  const merged: [number, number][] = [];
  for (const sp of spans) {
    const last = merged[merged.length - 1];
    if (last && sp[0] <= last[1]) last[1] = Math.max(last[1], sp[1]);
    else merged.push([...sp]);
  }
  return { gain, spans: merged };
}

/** Thời gian hạ dần trước khi giọng bắt đầu và trả dần sau khi dứt (ms), như `DUCK_DOWN_S`/`DUCK_UP_S` khi xuất. */
const DUCK_DOWN_MS = 150;
const DUCK_UP_MS = 300;

/** Hệ số âm lượng của các track khác tại `ms` (1 = không hạ); `spans` tăng dần như `duckSpans` trả về. */
export function duckLevel(duck: { gain: number; spans: [number, number][] } | null, ms: number): number {
  if (!duck) return 1;
  const { gain: g, spans } = duck;
  // Khoảng cuối cùng đã bắt đầu hạ (tìm nhị phân).
  let lo = 0;
  let hi = spans.length - 1;
  let at = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (spans[mid][0] - DUCK_DOWN_MS <= ms) {
      at = mid;
      lo = mid + 1;
    } else hi = mid - 1;
  }
  if (at < 0) return 1;
  const [s, e] = spans[at];
  if (ms <= e) return Math.max(g, 1 - ((ms - (s - DUCK_DOWN_MS)) / DUCK_DOWN_MS) * (1 - g));
  return Math.min(1, g + ((ms - e) / DUCK_UP_MS) * (1 - g));
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

/** Track phụ đề có ẩn hết cả hai dòng không. */
export const trackHidden = (tk: Track) => tk.hidden && (tk.kind !== "caption" || !!tk.srcHidden);

/**
 * Chỉ giữ một track phụ đề. Dự án cũ có C1 (gốc) + C2 (dịch): gộp lại, mắt C1 thành mắt câu gốc, mắt C2 thành mắt bản dịch.
 * Dự án chưa có track phụ đề thì thêm vào.
 */
export function withCaptionTracks(tracks: Track[]): Track[] {
  const caps = tracks.filter((t) => t.kind === "caption");
  if (caps.length === 1) return tracks;
  if (!caps.length) return [...tracks, newTrack("c1", "caption")];
  const [c1, c2] = caps;
  const merged: Track = { ...c1, hidden: c2.hidden, srcHidden: c1.hidden, locked: c1.locked || c2.locked };
  return tracks.filter((t) => t.kind !== "caption" || t === c1).map((t) => (t === c1 ? merged : t));
}

/** Chữ phụ đề lên hình: câu gốc ở trên, bản dịch ở dưới, bỏ dòng đang ẩn hoặc rỗng. */
export function captionText(tracks: Track[], seg: { src: string; vi: string }): string {
  const cap = tracks.find((t) => t.kind === "caption");
  if (!cap) return "";
  return [!cap.srcHidden && seg.src, !cap.hidden && seg.vi].filter(Boolean).join("\n");
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
