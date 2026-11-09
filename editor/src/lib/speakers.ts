import type { SpeakerInfo } from "./api";

export type Gender = "male" | "female";

/** Nhân vật của dự án; `id` là giá trị lưu trong `Segment.speaker`. */
export interface Speaker {
  id: string;
  name: string;
  gender: Gender | null;
  /** Giọng KorvaTTS dùng khi lồng tiếng. */
  voice: string;
  color: string;
}

/** Giọng KorvaTTS của server (`onplay-dub/src/voices.rs`), xen kẽ giọng cao (nữ) và thấp (nam). */
export const VOICES: { id: string; gender: Gender }[] = [
  { id: "khanh_vy", gender: "female" },
  { id: "huu_dat", gender: "male" },
  { id: "phuong_linh", gender: "female" },
  { id: "hoang_nam", gender: "male" },
  { id: "ngoc_huyen", gender: "female" },
  { id: "quang_huy", gender: "male" },
  { id: "quynh_nhu", gender: "female" },
  { id: "gia_bao", gender: "male" },
  { id: "bao_kim", gender: "female" },
  { id: "thanh_phong", gender: "male" },
];

/** Đủ khác nhau trên nền tím của track phụ đề, ở cả giao diện sáng và tối. */
export const SPEAKER_COLORS = ["#f2b134", "#4fc3f7", "#ef6f6c", "#7bd389", "#f48fb1", "#b39ddb", "#ffcc80", "#80cbc4", "#e6ee9c", "#90a4ae"];

export const voiceName = (id: string) => id.split("_").map((w) => w[0].toUpperCase() + w.slice(1)).join(" ");

/** Giọng chưa ai dùng, ưu tiên đúng giới tính; hết thì quay vòng trong nhóm đó. */
export function pickVoice(gender: Gender | null, taken: string[]): string {
  const pool = VOICES.filter((v) => !gender || v.gender === gender).map((v) => v.id);
  return pool.find((v) => !taken.includes(v)) ?? pool[taken.filter((v) => pool.includes(v)).length % pool.length];
}

export const speakerColor = (index: number) => SPEAKER_COLORS[index % SPEAKER_COLORS.length];

export const nextSpeakerId = (list: Speaker[]) => String(Math.max(0, ...list.map((s) => Number(s.id) || 0)) + 1);

/**
 * Nhân vật cho các nhãn 1-based đã gán vào câu (0 bỏ qua), theo thứ tự nhãn.
 * `info` (phân cụm trong Editor) cho biết giới tính đoán từ cao độ giọng.
 */
export function speakersFromLabels(labels: number[], nameOf: (n: number) => string, info: SpeakerInfo[] = []): Speaker[] {
  const used = [...new Set(labels.filter((l) => l > 0))].sort((a, b) => a - b);
  const out: Speaker[] = [];
  for (const [i, label] of used.entries()) {
    const gender = info.find((x) => x.label === label)?.gender ?? null;
    out.push({ id: String(label), name: nameOf(label), gender, voice: pickVoice(gender, out.map((s) => s.voice)), color: speakerColor(i) });
  }
  return out;
}
