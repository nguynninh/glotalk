import { invoke } from "@tauri-apps/api/core";

/** `auth::Login` bên Rust (camelCase). */
export interface AuthLogin {
  token: string;
  user: string;
  /** Giây Unix. */
  expiresAt: number;
}

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
  /** Quét tiếp từ mốc này (ms) */
  startMs: number;
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
  /** false khi bị dừng giữa chừng: `segments` là phần đã chốt, quét tiếp từ `doneMs` */
  complete: boolean;
  doneMs: number;
}

/** Sự kiện "ocr-partial": các câu đã chốt của lần chạy này và mốc đã quét chắc chắn. */
export interface OcrPartial {
  segments: OcrSegment[];
  doneMs: number;
}

export interface OcrProgress {
  /** "download": done/total là byte model đã tải/tổng */
  phase: "download" | "model" | "ocr" | "done";
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
  volumeL: number;
  volumeR: number;
  /** Clip của track lồng tiếng: không bị hạ theo `duck`. */
  dub: boolean;
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
  /** Hạ các clip không phải lồng tiếng xuống `gain` trong `spans` (ms trên timeline). */
  duck: { gain: number; spans: [number, number][] } | null;
}
export interface RenderProgress {
  doneMs: number;
  totalMs: number;
}

/** Khớp `stt::SttJob`/`SttOutput`/`SttProgress` (camelCase); `SttSegment` giữ tên trường Rust. */
export interface SttJob {
  server: string;
  path: string;
  sourceLanguage: string;
  /** Rỗng: không dịch */
  targetLanguage: string;
  /** Xin server phân đoạn theo người nói sau khi nhận dạng xong. */
  speakers?: boolean;
}
export interface SttSegment {
  start: number;
  end: number;
  text: string;
  translation: string;
  /** Nhãn 1-based của server, 0 là chưa xác định. */
  speaker: number;
}
export interface SttOutput {
  segments: SttSegment[];
  audioSeconds: number;
  seconds: number;
  warnings: string[];
}
export interface SttProgress {
  phase: "audio" | "connect" | "stt";
  doneS: number;
  totalS: number;
  lastText: string;
}

/** Khớp `commands::SpeakersJob` (snake_case không đổi vì các trường là một từ) và `speakers::Outcome` (camelCase). */
export interface SpeakersJob {
  path: string;
  spans: { start: number; end: number }[];
  options: { threshold: number; speakers: number };
}
export interface SpeakerInfo {
  label: number;
  lines: number;
  seconds: number;
  pitchHz: number | null;
  gender: "male" | "female" | null;
}
export interface SpeakersOutcome {
  /** Nhãn từng câu theo thứ tự `spans`; 0 là không xác định. */
  labels: number[];
  speakers: SpeakerInfo[];
}
export interface SpeakersProgress {
  phase: "download" | "model" | "audio" | "embed";
  done: number;
  total: number;
}

/** Khớp `dub::DubJob`/`DubOutput`/`DubProgress` (camelCase). */
export interface DubLine {
  id: number;
  startMs: number;
  endMs: number;
  text: string;
  voice: string;
}
export interface DubJob {
  server: string;
  token: string;
  language: string;
  lines: DubLine[];
  totalMs: number;
  cacheDir: string;
  output: string;
  /** File lồng tiếng cũ, xoá sau khi xong (chỉ `.m4a` cùng thư mục với `output`). */
  replace: string[];
}
export interface DubOutput {
  output: string;
  spoken: number;
  cached: number;
  failed: { id: number; error: string }[];
  overrun: number[];
  /** Khoảng có giọng lồng tiếng, ms từ đầu file. */
  spans: [number, number][];
}
export interface DubProgress {
  phase: "speak" | "mix";
  done: number;
  total: number;
}

/** Server công khai (onplay-server); đổi trong Cài đặt. */
export const DEFAULT_SERVER = "https://stt.qiverse.top";
/** Ngôn ngữ server nhận dạng/dịch được (`server/docs/streaming-audio.md`). */
export const STT_LANGUAGES = ["vi", "en", "zh", "ja", "ko", "th", "id", "ru", "ar", "fr", "de", "es"] as const;

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
  sttCheck: (server: string) => invoke<string>("stt_check", { server }),
  sttTranscribe: (job: SttJob) => invoke<SttOutput>("stt_transcribe", { job }),
  sttCancel: () => invoke<void>("stt_cancel"),
  speakersDetect: (job: SpeakersJob) => invoke<SpeakersOutcome>("speakers_detect", { job }),
  speakersCancel: () => invoke<void>("speakers_cancel"),
  dubSpeak: (job: DubJob) => invoke<DubOutput>("dub_speak", { job }),
  dubCancel: () => invoke<void>("dub_cancel"),
  readText: (path: string) => invoke<string>("read_text", { path }),
  writeText: (path: string, content: string) => invoke<void>("write_text", { path, content }),
  /** Tạo thư mục dự án (kèm `work/`, `export/`), trả về đường dẫn file `.glotalk` bên trong. */
  createProject: (dir: string) => invoke<string>("create_project", { dir }),
  pathExists: (path: string) => invoke<boolean>("path_exists", { path }),
  authLogin: (server: string, user: string, password: string) => invoke<AuthLogin>("auth_login", { server, user, password }),
  /** Tên tài khoản nếu token còn hiệu lực, null nếu server từ chối. */
  authMe: (server: string, token: string) => invoke<string | null>("auth_me", { server, token }),
  authLogout: (server: string, token: string) => invoke<void>("auth_logout", { server, token }),
  /** Một lô câu qua `POST /translate` của server; mỗi phần tử là `text` hoặc `error` của riêng câu đó. */
  translateLines: (server: string, token: string, source: string, target: string, texts: string[], context: string[]) =>
    invoke<{ text?: string; error?: string }[]>("translate_lines", { server, token, source, target, texts, context }),
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
