/**
 * Chỉnh màu cơ bản kiểu Lumetri. Hiện chỉ áp khi xem (CSS filter trên <video>);
 * khi nối xuất video sẽ map sang `eq` / `colorbalance` của ffmpeg nên màu có thể lệch nhẹ.
 */
export interface ColorGrade {
  enabled: boolean;
  /** -100 (lạnh/xanh) .. 100 (ấm/cam) */
  temperature: number;
  /** -100 (xanh lá) .. 100 (hồng/magenta) */
  tint: number;
  /** stop, -3 .. 3 */
  exposure: number;
  /** -100 .. 100 */
  contrast: number;
  /** 0 .. 200, 100 = giữ nguyên */
  saturation: number;
}

export const defaultGrade: ColorGrade = { enabled: true, temperature: 0, tint: 0, exposure: 0, contrast: 0, saturation: 100 };

const hasWb = (g: ColorGrade) => g.temperature !== 0 || g.tint !== 0;

/** Hệ số nhân kênh R, G, B cho nhiệt độ/tint. */
function wbGains(g: ColorGrade): [number, number, number] {
  return [1 + (g.temperature / 100) * 0.2, 1 - (g.tint / 100) * 0.15, 1 - (g.temperature / 100) * 0.2];
}

/**
 * Cân bằng trắng cho preview. WebKit (WKWebView của Tauri trên macOS) không vẽ `filter: url(#svg)` trên <video>
 * và bỏ luôn cả chuỗi filter, nên không dùng feColorMatrix. Thay vào đó: lớp phủ màu `mix-blend-mode: multiply`
 * (nhân từng kênh với gain/max ≤ 1) rồi `brightness(max)` trên khung bọc — tương đương ma trận đường chéo cũ.
 */
export function wbOverlay(g: ColorGrade): { color: string; gain: number } | undefined {
  if (!g.enabled || !hasWb(g)) return undefined;
  const ch = wbGains(g);
  const gain = Math.max(...ch);
  const [r, gr, b] = ch.map((c) => Math.round((Math.max(0, c) / gain) * 255));
  return { color: `rgb(${r}, ${gr}, ${b})`, gain };
}

/** Chuỗi CSS `filter` cho khung bọc <video> (áp sau lớp phủ cân bằng trắng), hoặc undefined khi không cần chỉnh. */
export function gradeCssFilter(g: ColorGrade): string | undefined {
  if (!g.enabled) return undefined;
  const parts: string[] = [];
  const wb = wbOverlay(g);
  if (wb && wb.gain !== 1) parts.push(`brightness(${wb.gain.toFixed(3)})`);
  if (g.exposure !== 0) parts.push(`brightness(${Math.pow(2, g.exposure).toFixed(3)})`);
  if (g.contrast !== 0) parts.push(`contrast(${(1 + g.contrast / 100).toFixed(3)})`);
  if (g.saturation !== 100) parts.push(`saturate(${(g.saturation / 100).toFixed(3)})`);
  return parts.length ? parts.join(" ") : undefined;
}

/** Tham số cho ffmpeg khi xuất (`colorchannelmixer` + `eq`), cùng phép tính với preview; null khi không cần chỉnh. */
export function gradeForExport(g: ColorGrade): { r: number; g: number; b: number; contrast: number; saturation: number } | null {
  if (!g.enabled) return null;
  const ev = Math.pow(2, g.exposure);
  const [r, gr, b] = wbGains(g).map((c) => Math.max(0, c) * ev);
  const out = { r, g: gr, b, contrast: 1 + g.contrast / 100, saturation: g.saturation / 100 };
  const identity = [out.r, out.g, out.b, out.contrast, out.saturation].every((v) => Math.abs(v - 1) < 1e-4);
  return identity ? null : out;
}
