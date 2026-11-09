import { useEffect, useState } from "react";
import { api } from "../lib/api";
import { newProject, openProject, openProjectFile, recentProjects, removeRecentProject } from "../store/actions";
import { useEditor } from "../store/editor";
import { useSession } from "../lib/auth";
import { Lang, LANGS, useLang, useT } from "../i18n";
import pkg from "../../package.json";
import { LoginDecor } from "./Login";

const fileName = (p: string) => p.replace(/^.*[\\/]/, "").replace(/\.glotalk$/i, "");
const dirName = (p: string) => p.replace(/[\\/][^\\/]+$/, "");

const Chevron = () => (
  <svg className="wl-chevron" viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden><path d="M6 3.5L10.5 8 6 12.5" /></svg>
);
const FolderIcon = ({ size = 20 }: { size?: number }) => (
  <svg viewBox="0 0 20 20" width={size} height={size} fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round" aria-hidden><path d="M2.5 5.5a1.5 1.5 0 0 1 1.5-1.5h3.6l1.8 2h6.6a1.5 1.5 0 0 1 1.5 1.5v7a1.5 1.5 0 0 1-1.5 1.5H4a1.5 1.5 0 0 1-1.5-1.5z" /><path d="M2.5 9h15" /></svg>
);

/** Màn hình bắt đầu: cột trái tạo/mở dự án và danh sách gần đây, bên phải ảnh nền. `onOpen` khi đã có dự án, `onLogout` về màn đăng nhập. */
export function Welcome({ onOpen, onLogout }: { onOpen: () => void; onLogout: () => void }) {
  const t = useT();
  const session = useSession((s) => s.session);
  const lang = useLang((s) => s.lang);
  const setLang = useLang((s) => s.setLang);
  const [recent, setRecent] = useState(recentProjects);
  const [missing, setMissing] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    let alive = true;
    Promise.all(recent.map((f) => api.pathExists(f).catch(() => true))).then((ok) => {
      if (alive) setMissing(new Set(recent.filter((_, i) => !ok[i])));
    });
    return () => {
      alive = false;
    };
  }, [recent]);

  // Hành động trả về false cả khi người dùng huỷ hộp thoại; chỉ báo lỗi khi có dòng log mới.
  const run = async (fn: () => Promise<boolean>) => {
    setBusy(true);
    setError("");
    const before = useEditor.getState().logs.length;
    try {
      if (await fn()) return onOpen();
      const logs = useEditor.getState().logs;
      if (logs.length !== before) setError(logs[logs.length - 1].replace(/^\[[^\]]*\]\s*/, ""));
    } finally {
      setBusy(false);
    }
  };

  const remove = (f: string) => {
    removeRecentProject(f);
    setRecent(recentProjects());
  };

  return (
    <div className="welcome login">
      <aside className="login-side wl-side">
        <LoginDecor />
        <div className="welcome-drag" data-tauri-drag-region />
        <img className="login-logo" src="/vlive-logo.png" alt="Vlive" draggable={false} />
        <div className="wl-app"><img src="/logo.png" alt="" />Glotalk<span className="wl-app-sep" /><b>Editor</b></div>
        <h1>{t("welcome.title")}</h1>
        <div className="welcome-actions">
          <button className="wl-action wl-primary" disabled={busy} onClick={() => run(newProject)}>
            <svg viewBox="0 0 20 20" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden><rect x="5.5" y="2.5" width="9" height="15" rx="2" /><path d="M9 14.8h2" strokeLinecap="round" /></svg>
            <span>{t("welcome.newProject")}</span>
            <Chevron />
          </button>
          <button className="wl-action" disabled={busy} onClick={() => run(openProject)}>
            <FolderIcon />
            <span>{t("welcome.openProject")}</span>
            <Chevron />
          </button>
        </div>
        {error ? <div className="welcome-error">{error}</div> : null}
        <h2 className="wl-heading">{t("welcome.recent")}</h2>
        <ul className="welcome-recent">
          {recent.length ? (
            recent.map((f) => {
              const gone = missing.has(f);
              return (
                <li key={f} className={gone ? "gone" : ""}>
                  <button className="recent-open" disabled={busy || gone} title={f} onClick={() => run(() => openProjectFile(f))}>
                    <span className="wl-recent-icon"><FolderIcon size={22} /></span>
                    <span className="wl-recent-text">
                      <span className="recent-name">{fileName(f)}</span>
                      <span className="recent-path">{gone ? t("welcome.missing") : dirName(f)}</span>
                    </span>
                    <Chevron />
                  </button>
                  <button className="ibtn recent-remove" title={t("welcome.remove")} onClick={() => remove(f)}>×</button>
                </li>
              );
            })
          ) : (
            <li className="muted small">{t("welcome.noRecent")}</li>
          )}
        </ul>
        <span className="grow" />
        <footer className="login-foot">
          <select value={lang} onChange={(e) => setLang(e.target.value as Lang)} aria-label={t("lang.label")}>
            {LANGS.map((l) => <option key={l.id} value={l.id}>{l.label}</option>)}
          </select>
          <span className="foot-sep" />
          <button className="login-link" title={t("welcome.logout")} onClick={onLogout}>
            {session?.guest || !session ? t("welcome.signIn") : t("welcome.logoutAs", { user: session.user })}
          </button>
          <span className="login-ver">v{pkg.version}</span>
        </footer>
      </aside>
      <section className="welcome-hero" data-tauri-drag-region>
        <img src="/logo.png" alt="" draggable={false} />
        <p>{t("welcome.tagline")}</p>
      </section>
    </div>
  );
}
