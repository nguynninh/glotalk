import { ReactNode } from "react";

type ThemePref = "dark" | "light" | "system";

const ICONS: Record<ThemePref, ReactNode> = {
  light: (
    <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
      <circle cx="8" cy="8" r="3" />
      <path d="M8 1.5v1.5M8 13v1.5M1.5 8H3M13 8h1.5M3.4 3.4l1 1M11.6 11.6l1 1M3.4 12.6l1-1M11.6 4.4l1-1" />
    </svg>
  ),
  dark: (
    <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round">
      <path d="M13.5 9.5A5.5 5.5 0 0 1 6.5 2.5a5.5 5.5 0 1 0 7 7z" />
    </svg>
  ),
  system: (
    <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round">
      <rect x="1.75" y="2.75" width="12.5" height="8.5" rx="1.5" />
      <path d="M5.5 14h5M8 11.25V14" strokeLinecap="round" />
    </svg>
  ),
};

const NEXT: Record<ThemePref, ThemePref> = { dark: "light", light: "system", system: "dark" };

/** Nút sáng/tối/theo hệ thống (bấm để xoay vòng), chọn ngôn ngữ và mở Cài đặt và chọn ngôn ngữ ở góc phải thanh tiêu đề. */
export function TitlebarPrefs<L extends string>({ theme, onTheme, lang, langs, onLang, settingsOn, onSettings, labels }: {
  theme: ThemePref;
  onTheme: (v: ThemePref) => void;
  lang: L;
  langs: readonly { id: L; label: string; short: string }[];
  onLang: (v: L) => void;
  settingsOn: boolean;
  onSettings: () => void;
  labels: { theme: string; dark: string; light: string; system: string; lang: string; settings: string };
}) {
  return (
    <div className="tb-prefs">
      {/* Select trong suốt phủ lên nút: hiển thị gọn (🌐 VI) nhưng vẫn mở danh sách native đầy đủ. */}
      <label className="tb-btn tb-lang" title={labels.lang}>
        <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.4">
          <circle cx="8" cy="8" r="6.25" />
          <path d="M1.75 8h12.5M8 1.75c1.8 1.7 2.7 3.8 2.7 6.25S9.8 12.55 8 14.25C6.2 12.55 5.3 10.45 5.3 8S6.2 3.45 8 1.75z" />
        </svg>
        <span>{langs.find((l) => l.id === lang)?.short}</span>
        <select value={lang} aria-label={labels.lang} onChange={(e) => onLang(e.target.value as L)}>
          {langs.map((l) => (
            <option key={l.id} value={l.id}>{l.label}</option>
          ))}
        </select>
      </label>
      <button
        className="tb-btn"
        title={`${labels.theme}: ${labels[theme]}`}
        aria-label={`${labels.theme}: ${labels[theme]}`}
        onClick={() => onTheme(NEXT[theme])}
      >
        {ICONS[theme]}
      </button>
      <button className={`tb-btn${settingsOn ? " on" : ""}`} title={labels.settings} aria-label={labels.settings} aria-pressed={settingsOn} onClick={onSettings}>
        <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round">
          <circle cx="8" cy="8" r="2.1" />
          <path d="M6.9 1.75h2.2l.35 1.75 1.2.7 1.7-.57 1.1 1.9-1.35 1.18v1.38l1.35 1.18-1.1 1.9-1.7-.57-1.2.7-.35 1.75H6.9l-.35-1.75-1.2-.7-1.7.57-1.1-1.9 1.35-1.18V7.31L2.55 6.13l1.1-1.9 1.7.57 1.2-.7z" />
        </svg>
      </button>
    </div>
  );
}
