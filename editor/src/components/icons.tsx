/** Icon SVG nét mảnh cho timeline, theo màu chữ (currentColor). */
const P = { width: 14, height: 14, viewBox: "0 0 16 16", fill: "none", stroke: "currentColor", strokeWidth: 1.4, strokeLinecap: "round", strokeLinejoin: "round" } as const;

export const IconLock = ({ open }: { open?: boolean }) => (
  <svg {...P}><rect x="3.5" y="7" width="9" height="6.5" rx="1" /><path d={open ? "M5.5 7V5a2.5 2.5 0 0 1 4.8-1" : "M5.5 7V5a2.5 2.5 0 0 1 5 0v2"} /></svg>
);
export const IconEye = ({ off }: { off?: boolean }) => (
  <svg {...P}><path d="M1.5 8s2.4-4.5 6.5-4.5S14.5 8 14.5 8 12.1 12.5 8 12.5 1.5 8 1.5 8Z" /><circle cx="8" cy="8" r="2" />{off && <path d="M2.5 13.5 13.5 2.5" />}</svg>
);
export const IconCursor = () => <svg {...P}><path d="M4 2.5v10l2.8-2.6 1.9 4.2 1.7-.8-1.9-4.1 3.8-.2Z" /></svg>;
export const IconRazor = () => (
  <svg {...P}><path d="M3 13 9.5 6.5" /><path d="m8 3 5 5-2.5 1.5L6.5 5.5Z" /></svg>
);
export const IconMagnet = () => <svg {...P}><path d="M4 3v5a4 4 0 0 0 8 0V3" /><path d="M4 5.5h2.2M9.8 5.5H12M6.2 3v5a1.8 1.8 0 0 0 3.6 0V3" /></svg>;
export const IconLink = () => (
  <svg {...P}><path d="M6.8 9.2a2.5 2.5 0 0 0 3.5 0l2.2-2.2a2.5 2.5 0 0 0-3.5-3.5l-.8.8" /><path d="M9.2 6.8a2.5 2.5 0 0 0-3.5 0L3.5 9a2.5 2.5 0 0 0 3.5 3.5l.8-.8" /></svg>
);
export const IconScissors = () => (
  <svg {...P}><circle cx="4.5" cy="11.5" r="2" /><circle cx="11.5" cy="11.5" r="2" /><path d="M5.8 10 12 2.5M10.2 10 4 2.5" /></svg>
);
export const IconTrash = () => <svg {...P}><path d="M3 4.5h10M6.5 4.5V3h3v1.5M4.5 4.5l.6 9h5.8l.6-9" /></svg>;
export const IconTrackFwd = () => (
  <svg {...P}><rect x="2" y="4" width="7" height="8" rx="1" strokeDasharray="2 1.5" /><path d="M10 8h4.5M12.5 6l2 2-2 2" /></svg>
);
export const IconRipple = () => <svg {...P}><path d="M8 2.5v11M2 8h4M10 8h4M4 6 2 8l2 2M12 6l2 2-2 2" /></svg>;
export const IconSlip = () => <svg {...P}><path d="M2.5 4v8M13.5 4v8M5 8h6M6.5 6.5 5 8l1.5 1.5M9.5 6.5 11 8l-1.5 1.5" /></svg>;
export const IconPen = () => <svg {...P}><path d="m3 13 1-3.5L10.5 3 13 5.5 6.5 12Z" /><path d="M9 4.5 11.5 7" /></svg>;
export const IconHand = () => (
  <svg {...P}><path d="M5 8.5V4a1 1 0 0 1 2 0v4M7 7.5V3a1 1 0 0 1 2 0v4.5M9 7.5V4a1 1 0 0 1 2 0v5M11 8V6.5a1 1 0 0 1 2 0V10a4 4 0 0 1-4 4H8a4 4 0 0 1-3.2-1.6L2.6 9.5a1 1 0 0 1 1.6-1.2L5 9.3" /></svg>
);
export const IconType = () => <svg {...P}><path d="M3 4V3h10v1M8 3v10M6 13h4" /></svg>;
