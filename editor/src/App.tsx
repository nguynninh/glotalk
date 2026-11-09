import { useCallback, useEffect, useRef, useState } from "react";
import { getCurrentWebview } from "@tauri-apps/api/webview";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { TimelineTool, timelineEnd, useEditor } from "./store/editor";
import { addMediaFiles, closeProject, exportSrt, importMedia, importSrt, importSrtFile, MEDIA_EXTS, newProject, openProject, saveProject } from "./store/actions";
import "./store/autosave";
import { Splitter } from "./components/Panel";
import { InspectorPanel } from "./panels/InspectorPanel";
import { MonitorPanel } from "./panels/MonitorPanel";
import { ProjectPanel } from "./panels/ProjectPanel";
import { timelineDropTarget, TimelinePanel } from "./panels/TimelinePanel";
import { SettingsTab } from "./tabs/SettingsTab";
import { TitlebarPrefs } from "./components/TitlebarPrefs";
import { LANGS, t, useLang, useT } from "./i18n";
import { useTheme } from "./lib/theme";
import { Splash } from "./screens/Splash";
import { Welcome } from "./screens/Welcome";
import { Login } from "./screens/Login";
import { logout, rememberSession, restoreSession, useSession } from "./lib/auth";
import "./App.css";

type Workspace = "edit" | "settings";

// titleBarStyle "Overlay" chỉ có trên macOS: tự vẽ thanh tiêu đề (chứa theme/ngôn ngữ). Nền tảng khác giữ thanh tiêu đề native, đặt các nút này cuối toolbar.
const IS_MAC = navigator.userAgent.includes("Mac");

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));
const loadNum = (k: string, d: number) => {
  try {
    return Number(localStorage.getItem(`glotalk.layout.${k}`)) || d;
  } catch {
    return d;
  }
};
const saveNum = (k: string, v: number) => {
  try {
    localStorage.setItem(`glotalk.layout.${k}`, String(Math.round(v)));
  } catch {
    /* bỏ qua */
  }
};

/** Mở app: màn hình chờ → đăng nhập (bỏ qua nếu phiên ghi nhớ còn hiệu lực) → màn hình bắt đầu (tạo/mở dự án) → trình biên tập. */
export default function App() {
  const [screen, setScreen] = useState<"splash" | "login" | "welcome" | "editor">("splash");
  const session = useSession((s) => s.session);
  const setSession = useSession((s) => s.setSession);
  const loadSession = useCallback(async () => {
    const s = await restoreSession();
    if (!s) rememberSession(null);
    setSession(s);
  }, [setSession]);
  const afterSplash = useCallback(() => setScreen(useSession.getState().session ? "welcome" : "login"), []);
  const signOut = () => {
    logout(session);
    setSession(null);
    setScreen("login");
  };
  const goHome = useCallback(() => setScreen("welcome"), []);
  if (screen === "splash") return <Splash onDone={afterSplash} work={loadSession} />;
  if (screen === "login") return <Login onLogin={(s) => { setSession(s); setScreen("welcome"); }} />;
  if (screen === "welcome") return <Welcome onOpen={() => setScreen("editor")} onLogout={signOut} />;
  return <Editor onHome={goHome} />;
}

function Editor({ onHome }: { onHome: () => void }) {
  const [ws, setWs] = useState<Workspace>("edit");
  const [leftW, setLeftW] = useState(() => loadNum("projectW", 280));
  const [rightW, setRightW] = useState(() => loadNum("inspectorW", 340));
  const [topH, setTopH] = useState(() => loadNum("topH", Math.round(window.innerHeight * 0.58)));
  const video = useEditor((s) => s.video);
  const log = useEditor((s) => s.log);
  const t = useT();
  const lang = useLang((s) => s.lang);
  const setLang = useLang((s) => s.setLang);
  const themePref = useTheme((s) => s.pref);
  const setThemePref = useTheme((s) => s.setPref);

  useEffect(() => { saveNum("projectW", leftW); }, [leftW]);
  useEffect(() => { saveNum("inspectorW", rightW); }, [rightW]);
  useEffect(() => { saveNum("topH", topH); }, [topH]);
  useShortcuts(ws === "edit");
  useFileDrop(ws === "edit");
  useCloseToHome(onHome);

  const prefs = (
    <TitlebarPrefs
      theme={themePref}
      onTheme={setThemePref}
      lang={lang}
      langs={LANGS}
      onLang={setLang}
      settingsOn={ws === "settings"}
      onSettings={() => setWs(ws === "settings" ? "edit" : "settings")}
      labels={{ theme: t("theme.label"), dark: t("theme.dark"), light: t("theme.light"), system: t("theme.system"), lang: t("lang.label"), settings: t("ws.settings") }}
    />
  );

  return (
    <div className="app">
      {IS_MAC ? (
        <div className="titlebar" data-tauri-drag-region>
          <span className="tb-title" data-tauri-drag-region>
            Glotalk Editor{video ? ` — ${video.path.replace(/^.*[\\/]/, "")}` : ""}
          </span>
          <span className="grow" data-tauri-drag-region />
          {prefs}
        </div>
      ) : null}
      <header className="topbar">
        <div className="brand"><img className="logo" src="/logo.png" alt="" />Glotalk <b>Editor</b></div>
        <nav className="menu">
          <FileMenu />
        </nav>
        <span className="grow" />
        <span className="title muted">{video ? video.path.replace(/^.*[\\/]/, "") : t("top.noProject")}</span>
        <button className="primary" disabled={!video} onClick={() => log(t("top.runPipelineLog"))}>
          {t("top.runPipeline")}
        </button>
        {IS_MAC ? null : prefs}
      </header>

      {ws === "edit" ? (
        <main
          className="workspace"
          style={{
            gridTemplateColumns: `${leftW}px 4px 1fr 4px ${rightW}px`,
            gridTemplateRows: `${topH}px 4px 1fr`,
            gridTemplateAreas: `"proj ls mon rs insp" "hs hs hs hs hs" "tl tl tl tl tl"`,
          }}
        >
          <div className="area" style={{ gridArea: "proj" }}><ProjectPanel /></div>
          <div className="area" style={{ gridArea: "ls" }}><Splitter dir="col" onDrag={(d) => setLeftW((w) => clamp(w + d, 220, 600))} /></div>
          <section className="panel monitor-panel" style={{ gridArea: "mon" }}>
            <header className="panel-tabs"><button className="on">{t("top.program", { name: video ? video.path.replace(/^.*[\\/]/, "") : t("top.empty") })}</button></header>
            <div className="panel-body"><MonitorPanel /></div>
          </section>
          <div className="area" style={{ gridArea: "hs" }}><Splitter dir="row" onDrag={(d) => setTopH((h) => clamp(h + d, 240, window.innerHeight - 260))} /></div>
          <section className="panel" style={{ gridArea: "tl" }}>
            <header className="panel-tabs"><button className="on">{t("top.timeline")}</button></header>
            <div className="panel-body"><TimelinePanel /></div>
          </section>
          <div className="area" style={{ gridArea: "rs" }}><Splitter dir="col" onDrag={(d) => setRightW((w) => clamp(w - d, 260, 600))} /></div>
          <div className="area" style={{ gridArea: "insp" }}><InspectorPanel /></div>
        </main>
      ) : (
        <main className="settings-wrap"><SettingsTab log={log} /></main>
      )}
    </div>
  );
}

/** Menu "Tệp" trên topbar: gom các thao tác dự án / nhập / xuất. Đóng khi chọn mục, bấm ra ngoài hoặc Esc. */
function FileMenu() {
  const t = useT();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    window.addEventListener("mousedown", onDown);
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("mousedown", onDown);
      window.removeEventListener("keydown", onKey);
    };
  }, [open]);
  const run = (fn: () => unknown) => () => {
    setOpen(false);
    fn();
  };
  return (
    <div className="menu-drop" ref={ref}>
      <button className={open ? "on" : ""} aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen(!open)}>
        {t("menu.file")}
      </button>
      {open ? (
        <div className="ctx-menu" role="menu">
          <button role="menuitem" onClick={run(newProject)}>{t("menu.newProject")}</button>
          <button role="menuitem" onClick={run(openProject)}>{t("menu.openProject")}<kbd>⌘O</kbd></button>
          <button role="menuitem" onClick={run(() => saveProject())} title={t("menu.saveProjectTitle")}>{t("menu.saveProject")}<kbd>⌘S</kbd></button>
          <hr />
          <button role="menuitem" onClick={run(importMedia)}>{t("menu.importVideo")}</button>
          <button role="menuitem" onClick={run(importSrt)}>{t("menu.importSrt")}</button>
          <hr />
          <button role="menuitem" onClick={run(() => exportSrt("vi"))}>{t("menu.exportSrt")}</button>
        </div>
      ) : null}
    </div>
  );
}

/** Phím tắt kiểu NLE. Bỏ qua khi đang gõ trong ô nhập. */
/** Ở màn dự án, nút đóng cửa sổ (×) đưa về màn Bắt đầu thay vì thoát app; ở các màn khác đóng như thường. */
function useCloseToHome(onHome: () => void) {
  useEffect(() => {
    let leaving = false;
    const off = getCurrentWindow().onCloseRequested(async (e) => {
      e.preventDefault();
      if (leaving) return;
      leaving = true;
      if (await closeProject()) onHome();
      else leaving = false;
    });
    return () => {
      off.then((fn) => fn());
    };
  }, [onHome]);
}

function useShortcuts(active: boolean) {
  useEffect(() => {
    if (!active) return;
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement;
      if (t.closest("input, textarea, select, [contenteditable]")) return;
      const s = useEditor.getState();
      const key = e.key.toLowerCase();
      if ((e.metaKey || e.ctrlKey) && key === "s") {
        e.preventDefault();
        saveProject(e.shiftKey);
        return;
      }
      if ((e.metaKey || e.ctrlKey) && key === "o") {
        e.preventDefault();
        openProject();
        return;
      }
      if (e.metaKey || e.ctrlKey) return;
      const frame = 1000 / (s.video?.fps || 30);
      const sel = s.clips.find((c) => c.id === s.selectedClipId);
      const tools: Record<string, TimelineTool> = { v: "select", a: "trackFwd", b: "ripple", c: "razor", y: "slip", p: "pen", h: "hand", t: "type" };
      if (tools[key] && !e.shiftKey && !e.altKey) s.set({ tool: tools[key] });
      else if (key === " ") s.togglePlay(); else if (key === "arrowleft") s.seek(s.time - (e.shiftKey ? 1000 : frame));
      else if (key === "arrowright") s.seek(s.time + (e.shiftKey ? 1000 : frame));
      else if (key === "home") s.seek(0);
      else if (key === "end") s.seek(timelineEnd(s));
      else if (key === "n") s.addSegmentAt(s.time);
      // 1–9: gán nhân vật thứ n cho câu đang chọn; 0: bỏ gán.
      else if (/^[0-9]$/.test(key) && s.selectedId !== null && !sel && (key === "0" || s.speakers[Number(key) - 1]))
        s.updateSegment(s.selectedId, { speaker: key === "0" ? undefined : s.speakers[Number(key) - 1].id });
      else if (key === "s") sel ? s.splitClip(sel.id, s.time) : s.splitAt(s.time);
      else if ((key === "delete" || key === "backspace") && (sel || s.selectedId)) sel ? s.removeClip(sel.id) : s.removeSegment(s.selectedId!);
      else if (key === "escape") s.set({ selectingRegion: null, pickingKeyFor: null, selectedId: null, selectedClipId: null });
      else if (key === "=" || key === "+") s.set({ pxPerSec: Math.min(600, s.pxPerSec * 1.5) });
      else if (key === "-") s.set({ pxPerSec: Math.max(2, s.pxPerSec / 1.5) });
      else if (key === "arrowup" || key === "arrowdown") {
        // Nhảy tới phụ đề trước/sau.
        const list = s.segments;
        const target = key === "arrowdown" ? list.find((x) => x.start > s.time + 1) : [...list].reverse().find((x) => x.start < s.time - 1);
        if (target) {
          s.seek(target.start);
          s.set({ selectedId: target.id });
        }
      } else return;
      e.preventDefault();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [active]);
}

/**
 * Kéo file từ Finder/Explorer vào cửa sổ. Tauri trả về đường dẫn thật (kéo-thả HTML không có), toạ độ theo pixel vật lý.
 * Thả lên timeline: đặt clip tại đó. Thả chỗ khác: chỉ thêm vào thư viện, trừ khi timeline đang trống thì đặt tại 0.
 */
function useFileDrop(active: boolean) {
  useEffect(() => {
    if (!active) return;
    let unlisten: (() => void) | undefined;
    let disposed = false;
    const hover = (id: string | null) => window.dispatchEvent(new CustomEvent("timeline-drop-hover", { detail: id }));
    let webview;
    try {
      webview = getCurrentWebview();
    } catch {
      return; // ngoài Tauri (mở bằng trình duyệt thường) thì không có kéo file
    }
    webview
      .onDragDropEvent((ev) => {
        const p = ev.payload;
        if (p.type === "leave") return hover(null);
        const dpr = window.devicePixelRatio || 1;
        const target = timelineDropTarget(p.position.x / dpr, p.position.y / dpr);
        if (p.type !== "drop") return hover(target?.trackId ?? null);
        hover(null);
        const ext = (f: string) => f.replace(/^.*\./, "").toLowerCase();
        const media = p.paths.filter((f) => MEDIA_EXTS.includes(ext(f)));
        const srt = p.paths.filter((f) => ext(f) === "srt");
        const other = p.paths.filter((f) => !media.includes(f) && !srt.includes(f));
        const s = useEditor.getState();
        if (other.length) s.log(t("act.dropUnsupported", { names: other.map((f) => f.replace(/^.*[\\/]/, "")).join(", ") }));
        srt.slice(0, 1).forEach((f) => importSrtFile(f));
        if (media.length) addMediaFiles(media, target ?? (s.clips.length ? undefined : { ms: 0 }));
      })
      .then((u) => (disposed ? u() : (unlisten = u)));
    return () => {
      disposed = true;
      unlisten?.();
    };
  }, [active]);
}
