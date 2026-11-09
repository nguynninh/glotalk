import { FormEvent, MouseEvent, ReactNode, useEffect, useRef, useState } from "react";
import { Hero } from "../components/Hero";
import { lastUser, login, rememberSession, Session, setLastUser } from "../lib/auth";
import { settingsStore } from "../lib/api";
import { Lang, LANGS, TKey, useLang, useT } from "../i18n";
import pkg from "../../package.json";

const AGREE_KEY = "login.agreed";
const MIN_PASS = 6;
// Mở chế độ nhà phát triển: tên đăng nhập DEV_USER, bấm mỗi mục (ô ghi nhớ, chữ "Ghi nhớ đăng nhập", ô đồng ý) DEV_TAPS lần rồi bấm Đăng nhập.
const DEV_USER = "vtvlive";
const DEV_TAPS = 10;
type DevTap = "rememberBox" | "rememberText" | "agreeBox";
// Tên đăng nhập / email / số điện thoại: không khoảng trắng, chỉ chữ, số và . _ @ + -
const USER_RE = /^[\p{L}\p{N}._@+-]{3,64}$/u;

type Field = "user" | "pass";
/** Thông báo hiện trên form; `fields` tô đỏ ô lỗi, `userOk` đánh dấu tên đăng nhập đúng, `retry` hiện nút Thử lại. */
interface LoginMsg {
  key: TKey;
  vars?: Record<string, string>;
  fields?: Field[];
  userOk?: boolean;
  retry?: boolean;
}

/** Kiểm tra trước khi gọi server; null khi hợp lệ. */
function validate(user: string, password: string): LoginMsg | null {
  const u = user.trim();
  if (!u && !password) return { key: "login.emptyBoth", fields: ["user", "pass"] };
  if (!u) return { key: "login.emptyUser", fields: ["user"] };
  if (!password) return { key: "login.emptyPass", fields: ["pass"] };
  if (!USER_RE.test(u)) return { key: "login.badUser", fields: ["user"] };
  if (password.length < MIN_PASS) return { key: "login.shortPass", vars: { n: String(MIN_PASS) }, fields: ["pass"], userOk: true };
  return null;
}

const SOCIAL: { id: string; name: string; icon: ReactNode }[] = [
  {
    id: "facebook",
    name: "Facebook",
    icon: <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor"><path d="M13.5 21v-7.5h2.6l.4-3h-3V8.6c0-.9.3-1.5 1.5-1.5h1.6V4.4c-.3 0-1.2-.1-2.3-.1-2.3 0-3.9 1.4-3.9 4v2.2H7.8v3h2.6V21h3.1z" /></svg>,
  },
  {
    id: "google",
    name: "Google",
    icon: (
      <svg viewBox="0 0 24 24" width="16" height="16">
        <path fill="#4285F4" d="M21.6 12.2c0-.7-.1-1.4-.2-2H12v3.8h5.4a4.6 4.6 0 0 1-2 3v2.5h3.2c1.9-1.7 3-4.3 3-7.3z" />
        <path fill="#34A853" d="M12 22c2.7 0 5-.9 6.6-2.4l-3.2-2.5c-.9.6-2 1-3.4 1-2.6 0-4.8-1.8-5.6-4.1H3.1v2.6A10 10 0 0 0 12 22z" />
        <path fill="#FBBC05" d="M6.4 14c-.2-.6-.3-1.3-.3-2s.1-1.4.3-2V7.4H3.1a10 10 0 0 0 0 9.2L6.4 14z" />
        <path fill="#EA4335" d="M12 5.9c1.5 0 2.8.5 3.8 1.5l2.9-2.9A10 10 0 0 0 3.1 7.4L6.4 10c.8-2.3 3-4.1 5.6-4.1z" />
      </svg>
    ),
  },
  {
    id: "apple",
    name: "Apple",
    icon: <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor"><path d="M16.4 12.6c0-2.4 2-3.6 2.1-3.7a4.5 4.5 0 0 0-3.5-1.9c-1.5-.2-2.9.9-3.7.9-.8 0-1.9-.9-3.2-.8a4.7 4.7 0 0 0-4 2.4c-1.7 3-.4 7.4 1.2 9.8.8 1.2 1.8 2.5 3 2.4 1.2 0 1.7-.8 3.1-.8s1.9.8 3.2.8c1.3 0 2.1-1.2 2.9-2.4.9-1.3 1.3-2.6 1.3-2.7 0 0-2.4-1-2.4-4zM14 5.5c.7-.8 1.1-1.9 1-3-1 0-2.1.7-2.8 1.5-.6.7-1.2 1.8-1 2.9 1 .1 2.1-.6 2.8-1.4z" /></svg>,
  },
];

/** Màn đăng nhập kiểu Vplay: form bên trái, ảnh nền bên phải. `onLogin` khi đăng nhập xong hoặc chọn dùng ngay. */
export function Login({ onLogin }: { onLogin: (s: Session) => void }) {
  const t = useT();
  const lang = useLang((s) => s.lang);
  const setLang = useLang((s) => s.setLang);
  const [user, setUser] = useState(lastUser);
  const [password, setPassword] = useState("");
  const [showPass, setShowPass] = useState(false);
  const [remember, setRemember] = useState(() => !!lastUser());
  const [agreed, setAgreed] = useState(() => settingsStore.get(AGREE_KEY) === "1");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<LoginMsg | null>(null);
  const userRef = useRef<HTMLInputElement>(null);
  const passRef = useRef<HTMLInputElement>(null);
  const taps = useRef<Record<DevTap, number>>({ rememberBox: 0, rememberText: 0, agreeBox: 0 });
  const [devToast, setDevToast] = useState(false);

  useEffect(() => {
    if (!devToast) return;
    const id = setTimeout(() => setDevToast(false), 2500);
    return () => clearTimeout(id);
  }, [devToast]);

  const tap = (k: DevTap) => {
    taps.current[k]++;
  };
  /** Đủ điều kiện thì hiện thông báo và trả về true để bỏ qua đăng nhập thật. */
  const devUnlock = () => {
    const c = taps.current;
    if (user.trim() !== DEV_USER || c.rememberBox < DEV_TAPS || c.rememberText < DEV_TAPS || c.agreeBox < DEV_TAPS) return false;
    taps.current = { rememberBox: 0, rememberText: 0, agreeBox: 0 };
    setMsg(null);
    setDevToast(true);
    return true;
  };
  // Chữ "Ghi nhớ đăng nhập" không bật/tắt ô checkbox, chỉ đếm cho chế độ nhà phát triển.
  const tapRememberText = (e: MouseEvent) => {
    e.preventDefault();
    tap("rememberText");
  };

  const bad = (f: Field) => !!msg?.fields?.includes(f);
  const show = (m: LoginMsg) => {
    setMsg(m);
    const f = m.fields?.[0];
    if (f) (f === "user" ? userRef : passRef).current?.focus();
  };

  const agree = (v: boolean) => {
    setAgreed(v);
    settingsStore.set(AGREE_KEY, v ? "1" : "");
  };
  const needAgree = () => {
    if (!agreed) show({ key: "login.needAgree" });
    return !agreed;
  };

  const run = async () => {
    if (busy || devUnlock()) return;
    const invalid = validate(user, password);
    if (invalid) return show(invalid);
    if (needAgree()) return;
    setBusy(true);
    setMsg(null);
    try {
      const s = await login(user, password);
      setLastUser(s.user);
      rememberSession(remember ? s : null);
      onLogin(s);
    } catch (err) {
      const m = err instanceof Error ? err.message : String(err);
      if (m === "login.badUser") show({ key: m, fields: ["user"] });
      else if (m === "login.wrongPass") show({ key: m, fields: ["pass"], userOk: true });
      else if (m === "login.tooMany" || m === "login.noApi") show({ key: m });
      else {
        // Lỗi mạng/server: không lộ chi tiết kỹ thuật cho người dùng, ghi log để debug.
        console.error("login failed:", err);
        show({ key: "login.failed", retry: true });
      }
    } finally {
      setBusy(false);
    }
  };
  const submit = (e: FormEvent) => {
    e.preventDefault();
    run();
  };

  const guest = () => {
    if (needAgree()) return;
    onLogin({ user: "", token: "", guest: true });
  };

  // Chưa có liên kết/luồng OAuth của Vplay: báo rõ thay vì im lặng.
  const soon = (key: TKey, vars?: Record<string, string>) => () => show({ key, vars });

  return (
    <div className="welcome login">
      <aside className="login-side">
        <LoginDecor />
        <div className="welcome-drag" data-tauri-drag-region />
        <img className="login-logo" src="/vlive-logo.png" alt="Vlive" draggable={false} />
        <h1>{t("login.title")}</h1>
        <p className="login-sub">{t("login.subtitle")}</p>
        <span className="login-bar" />
        <form className="login-form" onSubmit={submit} noValidate>
          <div className="login-msg" role="alert">
            {msg ? (
              <>
                {t(msg.key, msg.vars)}
                {msg.retry ? <button type="button" className="inline-link" onClick={run}>{t("login.retry")}</button> : null}
              </>
            ) : null}
          </div>
          <label className={`login-field${bad("user") ? " bad" : ""}`}>
            <svg viewBox="0 0 20 20" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.5"><circle cx="10" cy="6.5" r="3.5" /><path d="M3 17.5c.8-3.4 3.6-5.5 7-5.5s6.2 2.1 7 5.5" strokeLinecap="round" /></svg>
            <input ref={userRef} placeholder={t("login.username")} value={user} autoFocus={!user} autoComplete="username" aria-invalid={bad("user")}
              onChange={(e) => { setUser(e.target.value); setMsg(null); }} />
            {bad("user") ? <ErrIcon /> : msg?.userOk ? <OkIcon /> : null}
          </label>
          <label className={`login-field${bad("pass") ? " bad" : ""}`}>
            <svg viewBox="0 0 20 20" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.5"><rect x="3.5" y="8.5" width="13" height="9" rx="2.5" /><path d="M6.5 8.5V6a3.5 3.5 0 0 1 7 0v2.5" /><circle cx="10" cy="13" r="1" fill="currentColor" /></svg>
            <input ref={passRef} type={showPass ? "text" : "password"} placeholder={t("login.password")} value={password} autoFocus={!!user} autoComplete="current-password" aria-invalid={bad("pass")}
              onChange={(e) => { setPassword(e.target.value); if (msg?.fields || msg?.retry) setMsg(null); }} />
            {bad("pass") ? <ErrIcon /> : null}
            <button type="button" className="login-eye" title={t(showPass ? "login.hide" : "login.show")} onClick={() => setShowPass(!showPass)}>
              <svg viewBox="0 0 20 20" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
                <path d="M2 10s3-5.5 8-5.5S18 10 18 10s-3 5.5-8 5.5S2 10 2 10z" />
                <circle cx="10" cy="10" r="2.5" />
                {showPass ? null : <path d="M3.5 16.5l13-13" />}
              </svg>
            </button>
          </label>
          <div className="login-row">
            <label className="login-check">
              <input type="checkbox" checked={remember} onClick={() => tap("rememberBox")} onChange={(e) => setRemember(e.target.checked)} />
              <span className="login-check-text" onClick={tapRememberText}>{t("login.remember")}</span>
            </label>
            <button type="button" className="inline-link" onClick={soon("login.linkSoon")}>{t("login.forgot")}</button>
          </div>
          <button type="submit" className="login-submit" disabled={busy}>
            {busy ? <span className="spinner small" /> : <>{t("login.submit")}<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="M3 8h10M9 4l4 4-4 4" /></svg></>}
          </button>
          <div className="login-or"><span>{t("login.or")}</span></div>
          <div className="login-social">
            {SOCIAL.map((s) => (
              <button key={s.id} type="button" className={`social social-${s.id}`} title={t("login.with", { name: s.name })} onClick={soon("login.socialSoon", { name: s.name })}>{s.icon}</button>
            ))}
            <span className="social-sep" />
            <button type="button" className="social social-guest" title={t("login.guestTitle")} onClick={guest}>▶ {t("login.guest")}</button>
          </div>
        </form>
        <span className="grow" />
        <footer className="login-foot">
          <select value={lang} onChange={(e) => setLang(e.target.value as Lang)} aria-label={t("lang.label")}>
            {LANGS.map((l) => <option key={l.id} value={l.id}>{l.label}</option>)}
          </select>
          <span className="foot-sep" />
          <button type="button" className="login-link" onClick={soon("login.linkSoon")}>{t("login.register")}</button>
          <span className="login-ver">v{pkg.version}</span>
        </footer>
        <label className="login-check login-agree">
          <input type="checkbox" checked={agreed} onClick={() => tap("agreeBox")} onChange={(e) => agree(e.target.checked)} />
          <span>
            {t("login.agree")} <button type="button" className="inline-link" onClick={soon("login.linkSoon")}>{t("login.terms")}</button> {t("login.and")}{" "}
            <button type="button" className="inline-link" onClick={soon("login.linkSoon")}>{t("login.policy")}</button>
          </span>
        </label>
      </aside>
      <Hero tagline={t("welcome.tagline")} />
      {devToast ? <div className="login-toast" role="status">{t("login.devMode")}</div> : null}
    </div>
  );
}

const ErrIcon = () => (
  <svg className="login-state bad" viewBox="0 0 20 20" width="18" height="18" aria-hidden>
    <circle cx="10" cy="10" r="8" fill="currentColor" />
    <path d="M10 5.5v5.5" stroke="#fff" strokeWidth="2" strokeLinecap="round" />
    <circle cx="10" cy="14.2" r="1.2" fill="#fff" />
  </svg>
);
const OkIcon = () => (
  <svg className="login-state ok" viewBox="0 0 20 20" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
    <path d="M4.5 10.5l3.5 3.5 7.5-8" />
  </svg>
);

/** Sóng xanh nhạt và các tam giác "play" mờ trang trí nền cột đăng nhập (dùng chung cho màn Bắt đầu). */
export function LoginDecor() {
  const play = (x: string, y: string, size: number, rot = 0) => (
    <svg className="login-play" style={{ left: x, top: y, width: size, height: size, transform: `rotate(${rot}deg)` }} viewBox="0 0 20 20">
      <path d="M5 3.5c0-1.2 1.3-1.9 2.3-1.3l9.5 6.5c.9.6.9 1.9 0 2.6l-9.5 6.5C6.3 18.4 5 17.7 5 16.5z" />
    </svg>
  );
  return (
    <div className="login-decor" aria-hidden>
      {play("78%", "5%", 56)}
      {play("90%", "17%", 22, -15)}
      {play("10%", "77%", 34)}
    </div>
  );
}
