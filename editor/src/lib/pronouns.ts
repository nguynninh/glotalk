import type { Segment } from "./srt";

/**
 * Xưng hô tiếng Việt theo cặp nhân vật. Model dịch trên server (Qwen3 1.7B) luôn ra "tôi/bạn" và
 * bỏ qua cả tên người nói lẫn lời dặn xưng hô trong ngữ cảnh (đã thử), nên đổi bằng quy tắc sau khi dịch.
 */
export interface PronounRule {
  /** `Speaker.id` của người nói. */
  from: string;
  /** `Speaker.id` của người nghe, hoặc `ANYONE`: mặc định của người nói với mọi người. */
  to: string;
  /** Thay "tôi"; rỗng thì giữ nguyên. */
  self: string;
  /** Thay "bạn"; rỗng thì giữ nguyên. */
  other: string;
}

export const ANYONE = "*";
/** Câu kề cách xa hơn thì coi như không phải đang đối thoại với nhau. */
const DIALOG_GAP_MS = 15_000;

/** Luật cho cặp (người nói, người nghe): đúng cặp trước, rồi mặc định của người nói. */
export function ruleFor(rules: PronounRule[], from: string | undefined, to: string | undefined): PronounRule | undefined {
  if (!from) return undefined;
  return (to ? rules.find((r) => r.from === from && r.to === to) : undefined) ?? rules.find((r) => r.from === from && r.to === ANYONE);
}

/**
 * Người nghe của câu `index` (danh sách đã sắp theo thời gian): `listener` đặt tay (trừ khi `manual`
 * = false), nếu không thì nhân vật khác gần nhất ở câu trước (người vừa nói thường là người được đáp
 * lời), rồi câu sau.
 */
export function listenerOf(segments: Segment[], index: number, manual = true): string | undefined {
  const seg = segments[index];
  if (!seg) return undefined;
  if (manual && seg.listener) return seg.listener;
  if (!seg.speaker) return undefined;
  const near = (from: number, step: number) => {
    for (let i = from; i >= 0 && i < segments.length; i += step) {
      const other = segments[i];
      const gap = step < 0 ? seg.start - other.end : other.start - seg.end;
      if (gap > DIALOG_GAP_MS) return undefined;
      if (other.speaker && other.speaker !== seg.speaker) return other.speaker;
    }
    return undefined;
  };
  return near(index - 1, -1) ?? near(index + 1, 1);
}

// "chúng tôi" là số nhiều; "các bạn", "bạn bè", "bạn của tôi"… là bạn bè hoặc số nhiều, không phải ngôi thứ hai.
const FIRST = /(?<!\p{L})(?<!chúng\s+)tôi(?!\p{L})/giu;
const SECOND = /(?<!\p{L})(?<!(?:các|những|người|một|làm|kết|là|có)\s+)bạn(?!\p{L})(?!\s+(?:bè|thân|gái|trai|học|cùng|đời|đồng|của\s+(?:tôi|mình|anh|em|con|chị)(?!\p{L})))/giu;

const keepCase = (match: string, word: string) => (match[0] !== match[0].toLowerCase() ? word[0].toUpperCase() + word.slice(1) : word);

/** Đổi "tôi"/"bạn" theo `rule`; trả về câu mới (giữ nguyên nếu không có gì để đổi). */
export function applyPronouns(text: string, rule: PronounRule | undefined): string {
  if (!rule) return text;
  // Đổi "bạn" trước: ngoại lệ "bạn của tôi" cần nhìn thấy chữ "tôi" gốc.
  let out = rule.other.trim() ? text.replace(SECOND, (m) => keepCase(m, rule.other.trim())) : text;
  if (rule.self.trim()) out = out.replace(FIRST, (m) => keepCase(m, rule.self.trim()));
  return out;
}

/** Câu dịch của `segments[index]` sau khi áp xưng hô. */
export function withPronouns(segments: Segment[], index: number, rules: PronounRule[], text = segments[index].vi): string {
  return applyPronouns(text, ruleFor(rules, segments[index].speaker, listenerOf(segments, index)));
}
