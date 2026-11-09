import { invoke } from "@tauri-apps/api/core";

export interface ToolStatus {
  ffmpeg: string | null;
  ffprobe: string | null;
  encoders: string[];
}

export interface VideoInfo {
  path: string;
  duration_ms: number;
  width: number;
  height: number;
  fps: number;
  has_audio: boolean;
}

/** Kết quả `probe_media`: video, âm thanh hoặc ảnh tĩnh. Trường snake_case như struct Rust. */
export interface MediaInfo {
  path: string;
  kind: "video" | "audio" | "image";
  /** 0 với ảnh tĩnh */
  duration_ms: number;
  width: number;
  height: number;
  fps: number;
  has_audio: boolean;
}

/** Vùng theo tỉ lệ 0..1 so với khung hình, khớp với `project::Region` bên Rust. */
export interface Region {
  x: number;
  y: number;
  w: number;
  h: number;
}

export type OcrModel = "tiny" | "small" | "medium";

export interface OcrJob {
  path: string;
  width: number;
  height: number;
  durationMs: number;
  region: Region | null;
  scanFps: number;
  model: OcrModel;
  everyFrame: boolean;
}

export interface OcrSegment {
  start: number;
  end: number;
  text: string;
  conf: number;
}

export interface OcrOutput {
  segments: OcrSegment[];
  frames: number;
  ocrCalls: number;
  seconds: number;
}

export interface OcrProgress {
  phase: "model" | "ocr" | "done";
  done: number;
  total: number;
  ocrCalls: number;
  lastText: string;
}

/** Khớp `media::render::RenderJob` (camelCase). */
export interface RenderLayer {
  path: string;
  image: boolean;
  startMs: number;
  durMs: number;
  inMs: number;
  srcW: number;
  srcH: number;
  opacity: number;
  scale: number;
  x: number;
  y: number;
  key: { color: string; similarity: number; blend: number; spill: number } | null;
}
export interface RenderAudio {
  path: string;
  startMs: number;
  durMs: number;
  inMs: number;
  volume: number;
}
export interface RenderJob {
  output: string;
  width: number;
  height: number;
  fps: number;
  durationMs: number;
  encoder: string;
  layers: RenderLayer[];
  audio: RenderAudio[];
  graphics: string | null;
  grade: { r: number; g: number; b: number; contrast: number; saturation: number } | null;
}
export interface RenderProgress {
  doneMs: number;
  totalMs: number;
}

export const api = {
  checkTools: () => invoke<ToolStatus>("check_tools"),
  probeVideo: (path: string) => invoke<VideoInfo>("probe_video", { path }),
  probeMedia: (path: string) => invoke<MediaInfo>("probe_media", { path }),
  extractAudio: (input: string, output: string) =>
    invoke<string>("extract_audio", { input, output }),
  audioPeaks: (path: string, perSecond: number) => invoke<number[]>("audio_peaks", { path, perSecond }),
  thumbnails: (path: string, durationMs: number, count: number, height: number) =>
    invoke<string[]>("thumbnails", { path, durationMs, count, height }),
  ocrExtract: (job: OcrJob) => invoke<OcrOutput>("ocr_extract", { job }),
  ocrCancel: () => invoke<void>("ocr_cancel"),
  overlayBegin: () => invoke<string>("overlay_begin"),
  /** Gửi byte thô (không mã hoá JSON); tên file trong header. */
  overlayWrite: (name: string, bytes: Uint8Array) => invoke<void>("overlay_write", bytes, { headers: { "x-name": name } }),
  renderVideo: (job: RenderJob) => invoke<string>("render_video", { job }),
  renderCancel: () => invoke<void>("render_cancel"),
  readText: (path: string) => invoke<string>("read_text", { path }),
  writeText: (path: string, content: string) => invoke<void>("write_text", { path, content }),
};

/** Timecode kiểu NLE: HH:MM:SS:FF */
export function formatTimecode(ms: number, fps = 30): string {
  const t = Math.max(0, ms);
  const totalFrames = Math.floor((t / 1000) * fps);
  const f = totalFrames % Math.round(fps);
  const s = Math.floor(t / 1000);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(Math.floor(s / 3600))}:${p(Math.floor((s % 3600) / 60))}:${p(s % 60)}:${p(f)}`;
}

export function formatTime(ms: number): string {
  const t = Math.max(0, Math.floor(ms));
  const h = Math.floor(t / 3_600_000);
  const m = Math.floor((t % 3_600_000) / 60_000);
  const s = Math.floor((t % 60_000) / 1000);
  const f = t % 1000;
  const p = (n: number, w = 2) => String(n).padStart(w, "0");
  return `${p(h)}:${p(m)}:${p(s)}.${p(f, 3)}`;
}

export const settingsStore = {
  get(key: string, fallback = ""): string {
    try {
      return localStorage.getItem(`glotalk.${key}`) ?? fallback;
    } catch {
      return fallback;
    }
  },
  set(key: string, value: string) {
    try {
      localStorage.setItem(`glotalk.${key}`, value);
    } catch {
      /* bỏ qua */
    }
  },
};
