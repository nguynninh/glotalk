import { useEffect, useRef, useState } from "react";
import { useShallow } from "zustand/react/shallow";
import { formatTimecode, Region } from "../lib/api";
import { subStyleCss } from "../lib/subStyle";
import { gradeCssFilter, wbOverlay } from "../lib/colorGrade";
import { layerRect, samplePixel } from "../lib/compositor";
import { captionText, clipAt, clipFx } from "../lib/timeline";
import { currentSegment, RegionKind, sequenceSize, timelineEnd, useEditor } from "../store/editor";
import { importMedia } from "../store/actions";
import { TKey, useT } from "../i18n";
import { useProgram } from "./useProgram";
import { Timecode } from "./TimelinePanel";

const LABEL: Record<RegionKind, TKey> = { dialog: "mon.dialogRegion", ocr: "mon.ocrRegion" };

export function MonitorPanel() {
  const video = useEditor((s) => s.video);
  const playing = useEditor((s) => s.playing);
  const subStyle = useEditor((s) => s.subStyle);
  const regions = useEditor((s) => s.regions);
  const linkOcr = useEditor((s) => s.linkOcr);
  const selecting = useEditor((s) => s.selectingRegion);
  const pickingKeyFor = useEditor((s) => s.pickingKeyFor);
  const seg = useEditor(currentSegment);
  const mix = useEditor((s) => s.mix);
  const tracks = useEditor((s) => s.tracks);
  // Chữ: T1 dưới, T sau đè lên; phụ đề (C1) luôn trên cùng. So nông để khi phát chỉ render lại lúc danh sách đổi.
  const texts = useEditor(useShallow((s) =>
    s.tracks
      .filter((tk) => tk.kind === "text" && !tk.hidden)
      .flatMap((tk) => s.clips.filter((c) => c.trackId === tk.id && clipAt(c, s.time))),
  ));
  const grade = useEditor((s) => s.grade);
  const end = useEditor(timelineEnd);
  const { W, H } = useEditor(useShallow(sequenceSize));
  const { set, seek, log, setMix, togglePlay } = useEditor.getState();
  const t = useT();

  const stageRef = useRef<HTMLDivElement>(null);
  const hostRef = useRef<HTMLDivElement>(null);
  const [fit, setFit] = useState<"fit" | number>("fit");
  const [box, setBox] = useState<{ left: number; top: number; width: number; height: number; scale: number } | null>(null);
  const [draft, setDraft] = useState<Region | null>(null);
  const dragStart = useRef<{ x: number; y: number } | null>(null);
  const hasContent = end > 0;
  const program = useProgram(hostRef, (err) => log(t("mon.keyFallback", { err })), (err) => log(t("mon.playFailed", { err })),
    (st) => log(t("mon.playStats", { ...st })));

  // Khung hình sequence vừa với stage (hoặc theo % đã chọn).
  const measure = () => {
    const s = stageRef.current;
    if (!s) return setBox(null);
    const scale = fit === "fit" ? Math.min(s.clientWidth / W, s.clientHeight / H) : fit;
    const width = W * scale;
    const height = H * scale;
    setBox({ left: (s.clientWidth - width) / 2, top: (s.clientHeight - height) / 2, width, height, scale });
  };
  useEffect(() => {
    const ro = new ResizeObserver(measure);
    if (stageRef.current) ro.observe(stageRef.current);
    measure();
    return () => ro.disconnect();
  }, [W, H, fit]);

  const toRel = (e: React.PointerEvent) => {
    const r = stageRef.current!.getBoundingClientRect();
    const c = (n: number) => Math.min(1, Math.max(0, n));
    return { x: c((e.clientX - r.left - box!.left) / box!.width), y: c((e.clientY - r.top - box!.top) / box!.height) };
  };

  // Lấy màu khoá: đổi điểm bấm sang toạ độ trong nguồn của clip đang chọn rồi đọc pixel nguồn (chưa khoá).
  const pickKey = (e: React.PointerEvent) => {
    const s = useEditor.getState();
    const clip = s.clips.find((c) => c.id === s.pickingKeyFor);
    set({ pickingKeyFor: null });
    const el = clip && program.mediaOf(clip.id);
    if (!clip || !el || el instanceof HTMLAudioElement) return;
    const w = el instanceof HTMLImageElement ? el.naturalWidth : el.videoWidth;
    const h = el instanceof HTMLImageElement ? el.naturalHeight : el.videoHeight;
    const fx = clipFx(clip);
    const p = toRel(e);
    const r = layerRect({ w, h, scale: fx.scale, x: fx.x, y: fx.y }, W, H);
    const u = (p.x * W - r.x) / r.w;
    const v = (p.y * H - r.y) / r.h;
    if (u < 0 || u > 1 || v < 0 || v > 1) return log(t("mon.pickOutside"));
    const color = samplePixel(el, u, v);
    if (!color) return log(t("mon.pickBlocked"));
    s.patchClips({ [clip.id]: { fx: { ...clip.fx, key: { ...fx.key, enabled: true, color } } } });
  };

  const onDown = (e: React.PointerEvent) => {
    if (!box) return;
    if (pickingKeyFor) return pickKey(e);
    if (!selecting) return;
    stageRef.current!.setPointerCapture(e.pointerId);
    dragStart.current = toRel(e);
    setDraft({ ...dragStart.current, w: 0, h: 0 });
  };
  const onMove = (e: React.PointerEvent) => {
    if (!dragStart.current) return;
    const a = dragStart.current;
    const b = toRel(e);
    setDraft({ x: Math.min(a.x, b.x), y: Math.min(a.y, b.y), w: Math.abs(b.x - a.x), h: Math.abs(b.y - a.y) });
  };
  const onUp = () => {
    if (selecting && draft && draft.w > 0.01 && draft.h > 0.01) {
      set({ regions: { ...regions, [selecting]: draft }, selectingRegion: null });
      log(t("mon.regionPicked", { label: t(LABEL[selecting]).toLowerCase() }));
    }
    dragStart.current = null;
    setDraft(null);
  };

  const fps = video?.fps || 30;
  const text = seg ? captionText(tracks, seg) : "";
  const wb = wbOverlay(grade);

  return (
    <div className="monitor">
      <div ref={stageRef} className={`stage ${selecting || pickingKeyFor ? "selecting" : ""}`} onPointerDown={onDown} onPointerMove={onMove} onPointerUp={onUp}>
        <div ref={hostRef} className="program-host"
          style={{ ...(box ? { left: box.left, top: box.top, width: box.width, height: box.height } : {}), filter: gradeCssFilter(grade), visibility: hasContent ? "visible" : "hidden" }}>
          {wb && <div className="wb-overlay" style={{ background: wb.color }} />}
        </div>
        {!hasContent && (
          <button className="drop" onClick={importMedia}>
            <span className="drop-icon">＋</span>
            {t("mon.importToStart")}
            <small>MP4, MOV, MKV, MP3, WAV, PNG, JPG…</small>
          </button>
        )}
        {box &&
          texts.map((c) => {
            const fx = clipFx(c);
            return (
              <div key={c.id} className="text-layer"
                style={{
                  left: box.left + (0.5 + fx.x) * box.width,
                  top: box.top + (0.5 + fx.y) * box.height,
                  fontSize: fx.fontSize * fx.scale * box.scale,
                  color: fx.color,
                  opacity: fx.opacity,
                }}>
                {c.text}
              </div>
            );
          })}
        {box &&
          (["dialog", "ocr"] as RegionKind[]).map((k) => {
            if (k === "ocr" && linkOcr) return null;
            const r = draft && selecting === k ? draft : regions[k];
            return r ? (
              <div key={k} className={`region ${k}`}
                style={{ left: box.left + r.x * box.width, top: box.top + r.y * box.height, width: r.w * box.width, height: r.h * box.height }}>
                <span>{t(LABEL[k])}</span>
              </div>
            ) : null;
          })}
        {box && subStyle.enabled && text && (
          <div className="sub-layer" style={{ left: box.left, width: box.width, top: box.top + box.height * subStyle.posY }}>
            <span style={subStyleCss(subStyle, box.scale)}>{text}</span>
          </div>
        )}
        {selecting && <div className="stage-hint">{t("mon.dragHint", { label: t(LABEL[selecting]).toLowerCase() })}</div>}
        {pickingKeyFor && <div className="stage-hint">{t("mon.pickHint")}</div>}
      </div>

      <div className="monitor-bar">
        <Timecode className="tc accent" fps={fps} />
        <select className="fit" value={String(fit)} onChange={(e) => setFit(e.target.value === "fit" ? "fit" : Number(e.target.value))}>
          <option value="fit">{t("mon.fit")}</option>
          <option value="0.25">25%</option>
          <option value="0.5">50%</option>
          <option value="1">100%</option>
        </select>
        <div className="transport-btns">
          <button className="ibtn" title={t("mon.toStart")} onClick={() => seek(0)} disabled={!hasContent}>⏮</button>
          <button className="ibtn" title={t("mon.prevFrame")} onClick={() => seek(useEditor.getState().time - 1000 / fps)} disabled={!hasContent}>◀︎❙</button>
          <button className="ibtn play" title={t("mon.playPause")} onClick={togglePlay} disabled={!hasContent}>{playing ? "❚❚" : "▶︎"}</button>
          <button className="ibtn" title={t("mon.nextFrame")} onClick={() => seek(useEditor.getState().time + 1000 / fps)} disabled={!hasContent}>❙▶︎</button>
          <button className="ibtn" title={t("mon.toEnd")} onClick={() => seek(end)} disabled={!hasContent}>⏭</button>
        </div>
        <div className="vol">
          <button className="ibtn" title={t("mon.mute")} onClick={() => setMix({ masterMuted: !mix.masterMuted })}>
            {mix.masterMuted || mix.master === 0 ? "🔇" : mix.master < 0.5 ? "🔉" : "🔊"}
          </button>
          <input type="range" min={0} max={1} step={0.01} value={mix.masterMuted ? 0 : mix.master}
            title={t("mon.volume", { n: Math.round((mix.masterMuted ? 0 : mix.master) * 100) })}
            onChange={(e) => setMix({ master: Number(e.target.value), masterMuted: false })} />
        </div>
        <span className="tc muted">{formatTimecode(end, fps)}</span>
        <button className="ibtn" title={t("mon.fullscreen")} onClick={() => stageRef.current?.requestFullscreen()} disabled={!hasContent}>⛶</button>
      </div>
    </div>
  );
}
