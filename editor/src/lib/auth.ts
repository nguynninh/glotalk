import { create } from "zustand";
import { api, DEFAULT_SERVER, settingsStore } from "./api";

export interface Session {
  user: string;
  token: string;
  guest: boolean;
}

const server = () => settingsStore.get("serverUrl", DEFAULT_SERVER);

/** Đăng nhập qua `/auth/login` của server Glotalk. Lỗi sai tài khoản/khoá tạm là khoá i18n (`login.*`). */
export async function login(user: string, password: string): Promise<Session> {
  const r = await api.authLogin(server(), user.trim(), password);
  return { user: r.user, token: r.token, guest: false };
}

const REMEMBER_KEY = "login.remember";

/** Phiên đã ghi nhớ (tên và token, không lưu mật khẩu). */
export function rememberedSession(): Session | null {
  try {
    const v = JSON.parse(settingsStore.get(REMEMBER_KEY, "null"));
    return v && typeof v.user === "string" && typeof v.token === "string" ? { user: v.user, token: v.token, guest: false } : null;
  } catch {
    return null;
  }
}

export const rememberSession = (s: Session | null) => settingsStore.set(REMEMBER_KEY, s ? JSON.stringify({ user: s.user, token: s.token }) : "");

/** Tên đăng nhập gần nhất, điền sẵn vào form. */
export const lastUser = () => settingsStore.get("login.lastUser");
export const setLastUser = (user: string) => settingsStore.set("login.lastUser", user);

/**
 * Kiểm tra phiên đã ghi nhớ với server. Server từ chối (hết hạn, server khởi động lại) thì trả null;
 * không kết nối được thì vẫn giữ phiên để dùng app khi mất mạng.
 */
export async function restoreSession(): Promise<Session | null> {
  const s = rememberedSession();
  if (!s) return null;
  try {
    const user = await api.authMe(server(), s.token);
    return user ? { ...s, user } : null;
  } catch {
    return s;
  }
}

/** Huỷ token trên server; lỗi mạng bỏ qua vì phiên cục bộ đã xoá. */
export async function logout(s: Session | null) {
  rememberSession(null);
  if (s && !s.guest && s.token) await api.authLogout(server(), s.token).catch(() => {});
}

export const useSession = create<{ session: Session | null; setSession: (s: Session | null) => void }>((set) => ({
  session: null,
  setSession: (session) => set({ session }),
}));
