import type { CSSProperties } from "react";

/** Style phụ đề. Map 1-1 sang style ASS khi xuất video. */
export interface SubStyle {
  enabled: boolean;
  font: string;
  size: number;
  color: string;
  outline: number;
  outlineColor: string;
  bold: boolean;
  scaleX: number;
  scaleY: number;
  shadow: number;
  glow: boolean;
  glowColor: string;
  glowWidth: number;
  glowBlur: number;
  glowOpacity: number;
  background: boolean;
  backgroundColor: string;
  backgroundOpacity: number;
  posY: number; // 0..1 tính từ trên xuống
}

export const defaultSubStyle: SubStyle = {
  enabled: true,
  font: "Arial Black",
  size: 50,
  color: "#ffffff",
  outline: 5.8,
  outlineColor: "#000000",
  bold: true,
  scaleX: 94,
  scaleY: 104,
  shadow: 2.6,
  glow: true,
  glowColor: "#000000",
  glowWidth: 9.5,
  glowBlur: 3.2,
  glowOpacity: 60,
  background: false,
  backgroundColor: "#000000",
  backgroundOpacity: 55,
  posY: 0.84,
};

function hexA(hex: string, opacity: number) {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${opacity})`;
}

/** CSS xem trước. `scale` = chiều cao hiển thị / chiều cao video gốc (size ASS tính theo pixel gốc). */
export function subStyleCss(s: SubStyle, scale: number): CSSProperties {
  const px = (v: number) => `${(v * scale).toFixed(2)}px`;
  const shadows: string[] = [];
  if (s.glow) shadows.push(`0 0 ${px(s.glowWidth + s.glowBlur)} ${hexA(s.glowColor, s.glowOpacity / 100)}`);
  if (s.shadow > 0) shadows.push(`${px(s.shadow)} ${px(s.shadow)} 0 ${hexA("#000000", 0.8)}`);
  return {
    fontFamily: `"${s.font}", sans-serif`,
    fontSize: px(s.size),
    fontWeight: s.bold ? 900 : 400,
    color: s.color,
    WebkitTextStroke: `${px(s.outline * 2)} ${s.outlineColor}`,
    paintOrder: "stroke fill",
    textShadow: shadows.join(", ") || undefined,
    transform: `scale(${s.scaleX / 100}, ${s.scaleY / 100})`,
    background: s.background ? hexA(s.backgroundColor, s.backgroundOpacity / 100) : undefined,
    padding: s.background ? `${px(4)} ${px(12)}` : undefined,
    lineHeight: 1.15,
  };
}
