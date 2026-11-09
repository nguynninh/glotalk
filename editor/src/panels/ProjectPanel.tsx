import { useEffect, useRef, useState } from "react";
import { convertFileSrc } from "@tauri-apps/api/core";
import { formatTime, formatTimecode } from "../lib/api";
import { useEditor } from "../store/editor";
import { exportSrt, importMedia, importSrt } from "../store/actions";
import { MediaAsset } from "../lib/timeline";
import { timelineDropTarget } from "./TimelinePanel";
import { Panel } from "../components/Panel";
import { useT } from "../i18n";

type Tab = "media" | "captions" | "log";

export function ProjectPanel() {
  const [tab, setTab] = useState<Tab>("media");
  const count = useEditor((s) => s.segments.length);
  const t = useT();
  return (
    <Panel
      tabs={[{ id: "media", label: t("proj.tabProject") }, { id: "captions", label: t("proj.tabCaptions", { n: count }) }, { id: "log", label: "Log" }]}
      active={tab}
      onTab={setTab}
      className="project"
    >
      {tab === "media" && <MediaTab />}
      {tab === "captions" && <CaptionList />}
      {tab === "log" && <LogTab />}
    </Panel>
  );
}

function MediaTab() {
  const assets = useEditor((s) => s.assets);
  const clips = useEditor((s) => s.clips);
  const count = useEditor((s) => s.segments.length);
  const t = useT();
  const kindLabel = { video: t("proj.kindVideo"), audio: t("proj.kindAudio"), image: t("proj.kindImage") };

  // Kéo từ thư viện sang timeline bằng pointer event: kéo-thả HTML5 trong webview bị Tauri chặn khi bật nhận file từ hệ điều hành.
  const startDrag = (e: React.PointerEvent, asset: MediaAsset) => {
    if (e.button !== 0) return;
    const x0 = e.clientX;
    const y0 = e.clientY;
    let ghost: HTMLDivElement | null = null;
    const move = (ev: PointerEvent) => {
      if (!ghost && Math.hypot(ev.clientX - x0, ev.clientY - y0) < 5) return;
      if (!ghost) {
        ghost = document.createElement("div");
        ghost.className = "drag-ghost";
        ghost.textContent = asset.path.replace(/^.*[\\/]/, "");
        document.body.appendChild(ghost);
      }
      ghost.style.left = `${ev.clientX + 12}px`;
      ghost.style.top = `${ev.clientY + 8}px`;
      const tg = timelineDropTarget(ev.clientX, ev.clientY);
      window.dispatchEvent(new CustomEvent("timeline-drop-hover", { detail: tg?.trackId ?? null }));
    };
    const up = (ev: PointerEvent) => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
      window.dispatchEvent(new CustomEvent("timeline-drop-hover", { detail: null }));
      if (!ghost) return;
      ghost.remove();
      const tg = timelineDropTarget(ev.clientX, ev.clientY);
      if (tg && useEditor.getState().placeAsset(asset.id, tg.ms, tg.trackId) === null) useEditor.getState().log(t("act.trackLocked"));
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
  };

  const remove = (a: MediaAsset) => {
    const n = clips.filter((c) => c.assetId === a.id).length;
    if (!n || window.confirm(t("proj.confirmRemoveAsset", { name: a.path.replace(/^.*[\\/]/, ""), n }))) useEditor.getState().removeAsset(a.id);
  };

  return (
    <div className="media">
      <div className="media-actions">
        <button onClick={importMedia}>{t("proj.importVideo")}</button>
        <button onClick={importSrt}>{t("proj.importSrt")}</button>
        <button onClick={() => exportSrt("vi")} disabled={!count}>{t("proj.exportSrt")}</button>
      </div>
      {assets.length ? (
        assets.map((a) => (
          <div key={a.id} className="media-item" title={t("proj.dragToTimeline")} onPointerDown={(e) => startDrag(e, a)}
            onContextMenu={(e) => { e.preventDefault(); remove(a); }}>
            <div className={`media-thumb ${a.kind}`}>
              {a.thumbs.length ? <img draggable={false} src={convertFileSrc(a.thumbs[Math.floor(a.thumbs.length / 3)])} /> : <span>{a.kind === "audio" ? "♪" : "…"}</span>}
            </div>
            <div className="media-info">
              <b title={a.path}>{a.path.replace(/^.*[\\/]/, "")}</b>
              <span>{kindLabel[a.kind]}{a.kind !== "audio" ? ` · ${a.width}×${a.height}` : ""}{a.kind === "video" ? ` · ${a.fps.toFixed(2)} fps` : ""}</span>
              <span>
                {a.kind !== "image" ? formatTime(a.duration_ms).slice(0, 8) : ""}
                {a.kind === "video" ? ` · ${a.has_audio ? t("proj.hasAudio") : t("proj.noAudio")}` : ""}
              </span>
            </div>
            <button className="ibtn media-remove" title={t("proj.removeAsset")} onPointerDown={(e) => e.stopPropagation()} onClick={() => remove(a)}>✕</button>
          </div>
        ))
      ) : (
        <p className="muted small pad">{t("proj.noMedia")}</p>
      )}
    </div>
  );
}

function CaptionList() {
  const segments = useEditor((s) => s.segments);
  const selectedId = useEditor((s) => s.selectedId);
  const fps = useEditor((s) => s.video?.fps || 30);
  const { set, seek, updateSegment } = useEditor.getState();
  const [q, setQ] = useState("");
  const t = useT();
  const list = q ? segments.filter((s) => (s.vi + s.src).toLowerCase().includes(q.toLowerCase())) : segments;
  const selRef = useRef<HTMLTableRowElement>(null);
  useEffect(() => {
    selRef.current?.scrollIntoView({ block: "nearest" });
  }, [selectedId]);
  return (
    <div className="cap-list">
      <input className="search" placeholder={t("proj.search")} value={q} onChange={(e) => setQ(e.target.value)} />
      <div className="cap-table">
        <table>
          <thead><tr><th>#</th><th>{t("proj.colStart")}</th><th>{t("proj.colTranslation")}</th></tr></thead>
          <tbody>
            {list.map((s, i) => (
              <tr key={s.id} ref={s.id === selectedId ? selRef : undefined} className={s.id === selectedId ? "sel" : ""}
                onClick={() => { set({ selectedId: s.id, inspectorTab: "caption" }); seek(s.start); }}>
                <td className="muted">{i + 1}</td>
                <td className="mono">{formatTimecode(s.start, fps)}</td>
                <td>
                  <input className="cell" value={s.vi} placeholder={s.src} onChange={(e) => updateSegment(s.id, { vi: e.target.value })} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {!segments.length && <p className="muted small pad">{t("proj.noCaptions")}</p>}
      </div>
    </div>
  );
}

function LogTab() {
  const logs = useEditor((s) => s.logs);
  const ref = useRef<HTMLPreElement>(null);
  useEffect(() => {
    if (ref.current) ref.current.scrollTop = ref.current.scrollHeight;
  }, [logs]);
  return <pre ref={ref} className="log">{logs.join("\n")}</pre>;
}
