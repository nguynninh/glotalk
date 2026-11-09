import { SubStyle } from "./subStyle";

/**
 * Vẽ chữ/phụ đề lên canvas khi xuất video, bám theo cách preview hiển thị bằng CSS (`subStyleCss`, `.text-layer`).
 * ffmpeg trên máy không chắc có drawtext/libass nên chữ được vẽ ở đây thành PNG rồi ffmpeg chỉ việc phủ lên.
 * `k` = chiều cao khung xuất / chiều cao sequence (cỡ chữ lưu theo pixel sequence).
 */

function hexA(hex: string, opacity: number) {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${opacity})`;
}

const LINE_H = 1.15;

export function drawCaption(g: CanvasRenderingContext2D, text: string, s: SubStyle, W: number, H: number, k: number) {
  const lines = text.split("\n");
  const size = s.size * k;
  g.save();
  g.font = `${s.bold ? 900 : 400} ${size}px "${s.font}", sans-serif`;
  g.textAlign = "center";
  g.textBaseline = "middle";
  g.lineJoin = "round";
  g.translate(W / 2, s.posY * H);
  g.scale(s.scaleX / 100, s.scaleY / 100);
  const lh = size * LINE_H;
  const y0 = -((lines.length - 1) * lh) / 2;
  if (s.background) {
    const w = Math.max(...lines.map((l) => g.measureText(l).width));
    g.fillStyle = hexA(s.backgroundColor, s.backgroundOpacity / 100);
    g.fillRect(-w / 2 - 12 * k, y0 - lh / 2 - 4 * k, w + 24 * k, lines.length * lh + 8 * k);
  }
  const stroke = s.outline * 2 * k;
  const paint = () =>
    lines.forEach((l, i) => {
      const y = y0 + i * lh;
      if (stroke > 0) g.strokeText(l, 0, y);
      g.fillText(l, 0, y);
    });
  g.lineWidth = stroke;
  g.strokeStyle = s.outlineColor;
  g.fillStyle = s.color;
  if (s.glow) {
    g.shadowColor = hexA(s.glowColor, s.glowOpacity / 100);
    g.shadowBlur = (s.glowWidth + s.glowBlur) * k;
    paint();
  }
  if (s.shadow > 0) {
    g.shadowColor = "rgba(0,0,0,0.8)";
    g.shadowBlur = 0;
    g.shadowOffsetX = g.shadowOffsetY = s.shadow * k;
    paint();
  }
  g.shadowColor = "transparent";
  g.shadowOffsetX = g.shadowOffsetY = 0;
  paint();
  g.restore();
}

export interface TextItem {
  text: string;
  fontSize: number;
  color: string;
  opacity: number;
  scale: number;
  x: number;
  y: number;
}

export function drawText(g: CanvasRenderingContext2D, t: TextItem, W: number, H: number, k: number) {
  const size = t.fontSize * t.scale * k;
  const lines = t.text.split("\n");
  g.save();
  g.globalAlpha = t.opacity;
  g.font = `700 ${size}px ${getComputedStyle(document.body).fontFamily || "sans-serif"}`;
  g.textAlign = "center";
  g.textBaseline = "middle";
  g.fillStyle = t.color;
  g.shadowColor = "rgba(0,0,0,0.6)";
  g.shadowBlur = 6 * k;
  g.shadowOffsetY = 2 * k;
  const lh = size * LINE_H;
  const y0 = (0.5 + t.y) * H - ((lines.length - 1) * lh) / 2;
  lines.forEach((l, i) => g.fillText(l, (0.5 + t.x) * W, y0 + i * lh));
  g.restore();
}
