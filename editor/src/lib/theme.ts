import { create } from "zustand";
import { getCurrentWindow } from "@tauri-apps/api/window";

export type ThemePref = "dark" | "light" | "system";
export type Theme = "dark" | "light";

const KEY = "glotalk.theme";
const media = window.matchMedia("(prefers-color-scheme: light)");

const loadPref = (): ThemePref => {
  try {
    const v = localStorage.getItem(KEY);
    if (v === "dark" || v === "light" || v === "system") return v;
  } catch {
    /* bỏ qua */
  }
  // Mặc định tối: app biên tập video, nền tối để không lệch màu khi xem.
  return "dark";
};
const resolve = (p: ThemePref): Theme => (p === "system" ? (media.matches ? "light" : "dark") : p);

function apply(pref: ThemePref, theme: Theme) {
  document.documentElement.dataset.theme = theme;
  // Đồng bộ theme cửa sổ native để nút đèn giao thông/viền macOS khớp màu. Chạy ngoài Tauri (vite dev) thì bỏ qua.
  try {
    getCurrentWindow().setTheme(pref === "system" ? null : theme).catch(() => {});
  } catch {
    /* bỏ qua */
  }
}

/** `resolved` là theme đang hiển thị; canvas vẽ bằng màu CSS cần đưa nó vào deps để vẽ lại khi đổi. */
export const useTheme = create<{ pref: ThemePref; resolved: Theme; setPref: (p: ThemePref) => void }>((set) => ({
  pref: loadPref(),
  resolved: resolve(loadPref()),
  setPref: (pref) => {
    try {
      localStorage.setItem(KEY, pref);
    } catch {
      /* bỏ qua */
    }
    const resolved = resolve(pref);
    apply(pref, resolved);
    set({ pref, resolved });
  },
}));

{
  const { pref, resolved } = useTheme.getState();
  apply(pref, resolved);
  media.addEventListener("change", () => {
    const { pref } = useTheme.getState();
    if (pref !== "system") return;
    const resolved = resolve(pref);
    document.documentElement.dataset.theme = resolved;
    useTheme.setState({ resolved });
  });
}
