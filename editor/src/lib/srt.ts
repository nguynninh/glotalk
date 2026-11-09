export interface Segment {
  id: number;
  start: number; // ms
  end: number; // ms
  src: string; // câu gốc
  vi: string; // câu dịch
  speaker?: string;
}

const toMs = (t: string) => {
  const m = t.trim().match(/(\d+):(\d+):(\d+)[,.](\d+)/);
  if (!m) return 0;
  return +m[1] * 3_600_000 + +m[2] * 60_000 + +m[3] * 1000 + +m[4].padEnd(3, "0").slice(0, 3);
};
const fromMs = (ms: number) => {
  const p = (n: number, w = 2) => String(Math.floor(n)).padStart(w, "0");
  return `${p(ms / 3_600_000)}:${p((ms % 3_600_000) / 60_000)}:${p((ms % 60_000) / 1000)},${p(ms % 1000, 3)}`;
};

/** Đọc SRT. Nếu mỗi block có 2 dòng thì dòng 1 là câu gốc, dòng 2 là câu dịch. */
export function parseSrt(text: string, startId = 1): Segment[] {
  return text
    .replace(/\r/g, "")
    .split(/\n\s*\n/)
    .map((b) => b.trim().split("\n"))
    .filter((l) => l.length >= 2)
    .map((lines, i) => {
      const ti = lines.findIndex((l) => l.includes("-->"));
      const [a, b] = lines[ti].split("-->");
      const body = lines.slice(ti + 1);
      return {
        id: startId + i,
        start: toMs(a),
        end: toMs(b),
        src: body.length > 1 ? body[0] : body.join("\n"),
        vi: body.length > 1 ? body.slice(1).join("\n") : "",
      };
    })
    .sort((x, y) => x.start - y.start);
}

export function toSrt(segs: Segment[], field: "vi" | "src" | "both" = "vi"): string {
  return segs
    .map((s, i) => {
      const body = field === "both" ? [s.src, s.vi].filter(Boolean).join("\n") : s[field] || s.src;
      return `${i + 1}\n${fromMs(s.start)} --> ${fromMs(s.end)}\n${body}\n`;
    })
    .join("\n");
}
