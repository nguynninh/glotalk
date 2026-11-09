import { create } from "zustand";
import { core } from "./core";
import { panels } from "./panels";
import { zh } from "./zh";
import { ja } from "./ja";

/**
 * Đa ngôn ngữ. Từ điển gốc là tiếng Việt (`vi`); mọi ngôn ngữ khác phải có đủ khoá (TypeScript kiểm tra).
 * Dùng `useT()` trong component (tự re-render khi đổi ngôn ngữ) và `t()` ngoài React (store/actions).
 * Biến trong chuỗi viết dạng `{name}`.
 */
export const LANGS = [
  { id: "vi", label: "Tiếng Việt", short: "VI" },
  { id: "en", label: "English", short: "EN" },
  { id: "zh", label: "简体中文", short: "中" },
  { id: "ja", label: "日本語", short: "日" },
] as const;
export type Lang = (typeof LANGS)[number]["id"];

const dict = {
  vi: { ...core.vi, ...panels.vi },
  en: { ...core.en, ...panels.en },
  zh,
  ja,
};
export type { TKey } from "./keys";
import type { TKey } from "./keys";
type Vars = Record<string, string | number>;

const KEY = "glotalk.lang";
const initial = (): Lang => {
  try {
    const v = localStorage.getItem(KEY);
    if (LANGS.some((l) => l.id === v)) return v as Lang;
  } catch {
    /* bỏ qua */
  }
  const nav = navigator.language?.toLowerCase().slice(0, 2);
  return LANGS.find((l) => l.id === nav)?.id ?? "en";
};

export const useLang = create<{ lang: Lang; setLang: (l: Lang) => void }>((set) => ({
  lang: initial(),
  setLang: (lang) => {
    try {
      localStorage.setItem(KEY, lang);
    } catch {
      /* bỏ qua */
    }
    document.documentElement.lang = lang;
    set({ lang });
  },
}));
document.documentElement.lang = useLang.getState().lang;

function translate(lang: Lang, key: TKey, vars?: Vars): string {
  const s: string = dict[lang][key] ?? dict.vi[key] ?? key;
  return vars ? s.replace(/\{(\w+)\}/g, (m, k) => (k in vars ? String(vars[k]) : m)) : s;
}

/** Dịch ngoài React, theo ngôn ngữ hiện tại. */
export const t = (key: TKey, vars?: Vars) => translate(useLang.getState().lang, key, vars);

/** Hook dịch cho component. */
export function useT() {
  const lang = useLang((s) => s.lang);
  return (key: TKey, vars?: Vars) => translate(lang, key, vars);
}
