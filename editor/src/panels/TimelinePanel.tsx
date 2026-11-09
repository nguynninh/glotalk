import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { convertFileSrc } from "@tauri-apps/api/core";
import { formatTimecode } from "../lib/api";
import { Segment } from "../lib/srt";
import { Clip, clipEnd, clipFx, displayOrder, fits, MediaAsset, MIN_CLIP_MS, Track, trackAudible, trackCode, trackHidden } from "../lib/timeline";
import { timelineEnd, useEditor } from "../store/editor";
import { confirmDialog } from "../store/actions";
import { useT } from "../i18n";
import { useTheme } from "../lib/theme";
import { IconCursor, IconEye, IconHand, IconLink, IconLock, IconMagnet, IconPen, IconRazor, IconRipple, IconScissors, IconSlip, IconTrackFwd, IconTrash, IconType } from "../components/icons";
import type { TimelineTool } from "../store/editor";
import type { TKey } from "../i18n";

const HEAD_W = 200;
const RULER_H = 26;
const TRACK_H: Record<Track["kind"], number> = { video: 76, caption: 46, audio: 64, text: 40 };
/** Track âm thanh kéo cao được tới mức này; từ AUDIO_LR_H trở lên thì hiện thêm âm lượng tai trái/phải. */
const AUDIO_MAX_H = 200;
const AUDIO_LR_H = 104;
const trackH = (tk: Track) => tk.height ?? TRACK_H[tk.kind];
/** Màu clip theo thứ tự track âm thanh, kiểu nhãn màu của Premiere (lời, hiệu ứng, nền, nhạc…). */
const AUDIO_COLORS = ["#2f7d43", "#2a6496", "#22705f", "#8a3f9e", "#9a6224", "#7a3b4f"];
const MIN_PX = 2;
const MAX_PX = 600;
const MIN_LEN = 200; // ms, phụ đề
const SNAP_PX = 6;

function pickStep(pxPerSec: number) {
  const steps = [0.1, 0.25, 0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 1200];
  return steps.find((s) => s * pxPerSec >= 90) ?? 1800;
}

/**
 * Điểm thả trên timeline (cho kéo thả file và kéo từ thư viện media): track dưới con trỏ và thời điểm.
 * Đọc từ DOM vì nguồn kéo nằm ngoài component này.
 */
export function timelineDropTarget(clientX: number, clientY: number): { ms: number; trackId?: string } | null {
  const el = document.elementFromPoint(clientX, clientY);
  const content = document.querySelector<HTMLElement>(".tl-content");
  if (!el || !content || !el.closest(".timeline")) return null;
  const { pxPerSec } = useEditor.getState();
  const ms = Math.max(0, ((clientX - content.getBoundingClientRect().left) / pxPerSec) * 1000);
  const lane = el.closest<HTMLElement>("[data-track]");
  return { ms, trackId: lane?.dataset.track };
}

const TOOLS: { id: TimelineTool; key: string; label: TKey; Icon: () => React.JSX.Element }[] = [
  { id: "select", key: "V", label: "tl.toolSelect", Icon: IconCursor },
  { id: "trackFwd", key: "A", label: "tl.toolTrackFwd", Icon: IconTrackFwd },
  { id: "ripple", key: "B", label: "tl.toolRipple", Icon: IconRipple },
  { id: "razor", key: "C", label: "tl.toolRazor", Icon: IconRazor },
  { id: "slip", key: "Y", label: "tl.toolSlip", Icon: IconSlip },
  { id: "pen", key: "P", label: "tl.toolPen", Icon: IconPen },
  { id: "hand", key: "H", label: "tl.toolHand", Icon: IconHand },
  { id: "type", key: "T", label: "tl.toolType", Icon: IconType },
];

/** Thanh công cụ dọc bên trái timeline, kiểu Premiere. */
function ToolPalette() {
  const tool = useEditor((s) => s.tool);
  const t = useT();
  return (
    <div className="tool-palette">
      {TOOLS.map(({ id, key, label, Icon }) => (
        <button key={id} className={`tp-btn ${tool === id ? "on" : ""}`} title={`${t(label)} (${key})`}
          onClick={() => useEditor.getState().set({ tool: id })}>
          <Icon />
        </button>
      ))}
    </div>
  );
}

/** Mã thời gian theo playhead; component riêng để chỉ nó render lại khi time đổi. */
export function Timecode({ className, fps }: { className: string; fps: number }) {
  const time = useEditor((s) => s.time);
  return <span className={className}>{formatTimecode(time, fps)}</span>;
}

/** Playhead và tự cuộn theo nó khi đang phát. */
function Playhead({ pxPerSec, scrollRef }: { pxPerSec: number; scrollRef: React.RefObject<HTMLDivElement | null> }) {
  const time = useEditor((s) => s.time);
  const playing = useEditor((s) => s.playing);
  const x = (time / 1000) * pxPerSec;
  useEffect(() => {
    const el = scrollRef.current;
    if (!el || !playing) return;
    if (x > el.scrollLeft + el.clientWidth - 40 || x < el.scrollLeft) el.scrollLeft = x - 40;
  }, [x, playing]);
  return (
    <div className="playhead" style={{ left: x }}>
      <div className="ph-head" />
    </div>
  );
}

type Menu = { x: number; y: number } & ({ clip: string } | { track: string });

export function TimelinePanel() {
  const video = useEditor((s) => s.video);
  const segments = useEditor((s) => s.segments);
  const selectedId = useEditor((s) => s.selectedId);
  const selectedClipId = useEditor((s) => s.selectedClipId);
  const pxPerSec = useEditor((s) => s.pxPerSec);
  const tracks = useEditor((s) => s.tracks);
  const clips = useEditor((s) => s.clips);
  const assets = useEditor((s) => s.assets);
  const durationMs = useEditor(timelineEnd);
  const tool = useEditor((s) => s.tool);
  const snapOn = useEditor((s) => s.snap);
  const linkedSelection = useEditor((s) => s.linkedSelection);
  const st = useEditor.getState();
  const theme = useTheme((s) => s.resolved);
  const t = useT();

  const scrollRef = useRef<HTMLDivElement>(null);
  const rulerRef = useRef<HTMLCanvasElement>(null);
  const [view, setView] = useState({ left: 0, width: 800 });
  const [menu, setMenu] = useState<Menu | null>(null);
  const [renaming, setRenaming] = useState<string | null>(null);
  const [dropTrack, setDropTrack] = useState<string | null>(null);
  const contentW = Math.max(view.width, (durationMs / 1000) * pxPerSec + 400);
  const msToX = (ms: number) => (ms / 1000) * pxPerSec;
  const xToMs = (x: number) => (x / pxPerSec) * 1000;
  const fps = video?.fps || 30;
  const ordered = displayOrder(tracks);
  const assetById = new Map(assets.map((a) => [a.id, a]));
  const hasContent = !!video || clips.length > 0 || segments.length > 0;

  // Theo dõi vùng đang nhìn thấy để chỉ vẽ phần đó (video dài vài giờ vẫn nhẹ).
  useLayoutEffect(() => {
    const el = scrollRef.current!;
    const update = () => setView({ left: el.scrollLeft, width: el.clientWidth });
    update();
    const ro = new ResizeObserver(update);
    ro.observe(el);
    el.addEventListener("scroll", update);
    return () => {
      ro.disconnect();
      el.removeEventListener("scroll", update);
    };
  }, []);

  // Vừa khung khi đổi video chính.
  useEffect(() => {
    if (durationMs && scrollRef.current) fitAll();
  }, [video?.path]);

  // Highlight track đang được kéo file qua (sự kiện do App phát).
  useEffect(() => {
    const on = (e: Event) => setDropTrack((e as CustomEvent<string | null>).detail);
    window.addEventListener("timeline-drop-hover", on);
    return () => window.removeEventListener("timeline-drop-hover", on);
  }, []);

  function fitAll() {
    const el = scrollRef.current!;
    const end = timelineEnd(useEditor.getState());
    if (!end) return;
    st.set({ pxPerSec: Math.min(MAX_PX, Math.max(MIN_PX, (el.clientWidth - 40) / (end / 1000))) });
    el.scrollLeft = 0;
  }

  function zoomTo(next: number, anchorClientX?: number) {
    const el = scrollRef.current!;
    const px = Math.min(MAX_PX, Math.max(MIN_PX, next));
    const anchor = anchorClientX !== undefined ? anchorClientX - el.getBoundingClientRect().left : msToX(useEditor.getState().time) - el.scrollLeft;
    const anchorMs = xToMs(el.scrollLeft + anchor);
    st.set({ pxPerSec: px });
    requestAnimationFrame(() => (el.scrollLeft = (anchorMs / 1000) * px - anchor));
  }

  // Ctrl/Cmd + lăn chuột để zoom, Shift + lăn để cuộn ngang; lăn thường cuộn dọc qua các track.
  useEffect(() => {
    const el = scrollRef.current!;
    const onWheel = (e: WheelEvent) => {
      if (e.ctrlKey || e.metaKey) {
        e.preventDefault();
        zoomTo(useEditor.getState().pxPerSec * Math.exp(-e.deltaY * 0.004), e.clientX);
      } else if (e.shiftKey && Math.abs(e.deltaY) > Math.abs(e.deltaX)) {
        e.preventDefault();
        el.scrollLeft += e.deltaY;
      }
    };
    el.addEventListener("wheel", onWheel, { passive: false });
    return () => el.removeEventListener("wheel", onWheel);
  });

  // Vẽ thước thời gian.
  useEffect(() => {
    const c = rulerRef.current;
    if (!c) return;
    const dpr = window.devicePixelRatio || 1;
    c.width = view.width * dpr;
    c.height = RULER_H * dpr;
    const g = c.getContext("2d")!;
    g.scale(dpr, dpr);
    const css = getComputedStyle(c);
    g.clearRect(0, 0, view.width, RULER_H);
    g.strokeStyle = css.getPropertyValue("--tl-tick");
    g.fillStyle = css.getPropertyValue("--tl-label");
    g.font = "10px ui-monospace, Menlo, monospace";
    const step = pickStep(pxPerSec);
    const minor = step / 5;
    const startS = Math.floor(view.left / pxPerSec / minor) * minor;
    const endS = (view.left + view.width) / pxPerSec;
    g.beginPath();
    for (let s = startS; s <= endS; s += minor) {
      const x = Math.round(s * pxPerSec - view.left) + 0.5;
      const major = Math.abs(s / step - Math.round(s / step)) < 1e-6;
      g.moveTo(x, RULER_H);
      g.lineTo(x, major ? RULER_H - 12 : RULER_H - 5);
      if (major) g.fillText(formatTimecode(s * 1000, fps), x + 4, 11);
    }
    g.stroke();
  }, [view, pxPerSec, fps, theme]);

  // Đóng menu chuột phải khi bấm ra ngoài, cuộn hoặc Esc.
  useEffect(() => {
    if (!menu) return;
    const close = () => setMenu(null);
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && close();
    const el = scrollRef.current;
    window.addEventListener("pointerdown", close);
    window.addEventListener("blur", close);
    window.addEventListener("keydown", onKey);
    el?.addEventListener("scroll", close);
    return () => {
      window.removeEventListener("pointerdown", close);
      window.removeEventListener("blur", close);
      window.removeEventListener("keydown", onKey);
      el?.removeEventListener("scroll", close);
    };
  }, [menu]);

  // Kéo thước / vùng trống để tua.
  const scrub = (e: React.PointerEvent) => {
    if (!hasContent || e.button !== 0) return;
    const el = scrollRef.current!;
    const target = e.currentTarget as HTMLElement;
    target.setPointerCapture(e.pointerId);
    const go = (cx: number) => st.seek(xToMs(cx - el.getBoundingClientRect().left + el.scrollLeft));
    go(e.clientX);
    const move = (ev: PointerEvent) => go(ev.clientX);
    const up = () => {
      target.removeEventListener("pointermove", move);
      target.removeEventListener("pointerup", up);
    };
    target.addEventListener("pointermove", move);
    target.addEventListener("pointerup", up);
  };

  // Bàn tay: kéo để cuộn timeline ngang và dọc.
  const pan = (e: React.PointerEvent) => {
    if (useEditor.getState().tool !== "hand" || e.button !== 0) return;
    e.stopPropagation();
    e.preventDefault();
    const el = scrollRef.current!;
    const main = el.parentElement!;
    const x0 = e.clientX, y0 = e.clientY, sl = el.scrollLeft, stp = main.scrollTop;
    const target = e.currentTarget as HTMLElement;
    target.setPointerCapture(e.pointerId);
    const move = (ev: PointerEvent) => {
      el.scrollLeft = sl - (ev.clientX - x0);
      main.scrollTop = stp - (ev.clientY - y0);
    };
    const up = () => {
      target.removeEventListener("pointermove", move);
      target.removeEventListener("pointerup", up);
    };
    target.addEventListener("pointermove", move);
    target.addEventListener("pointerup", up);
  };

  /** Hít vào playhead và mép các clip khác trong phạm vi SNAP_PX; trả về độ lệch cần cộng thêm. */
  const snapDelta = (edges: number[], ignore: Set<string>) => {
    const s = useEditor.getState();
    if (!s.snap) return 0;
    const targets = [0, s.time, ...s.clips.filter((c) => !ignore.has(c.id)).flatMap((c) => [c.start, clipEnd(c)])];
    let best = 0;
    let bestPx = SNAP_PX;
    for (const e of edges)
      for (const tg of targets) {
        const px = Math.abs(msToX(tg - e));
        if (px < bestPx) {
          bestPx = px;
          best = tg - e;
        }
      }
    return best;
  };

  /**
   * Kéo clip media/chữ theo công cụ đang chọn:
   * - select: di chuyển (cả sang track cùng loại) hoặc trim 2 đầu; clip liên kết đi theo.
   * - trackFwd: kéo clip này cùng mọi clip và phụ đề bắt đầu từ nó trở về sau.
   * - ripple: trim mà các clip phía sau trên cùng track dịch theo, không để lại khoảng trống.
   * - slip: giữ nguyên vị trí và độ dài, đổi đoạn nguồn được dùng.
   * - pen: kéo dọc để đổi âm lượng (clip tiếng) hoặc độ mờ (clip hình/chữ).
   * - razor: tách tại điểm bấm.
   */
  const dragMediaClip = (e: React.PointerEvent, clip: Clip, mode: "move" | "start" | "end") => {
    if (e.button !== 0) return;
    const s0 = useEditor.getState();
    if (s0.tool === "hand" || s0.tool === "type") return; // để sự kiện đi tiếp xuống lane
    e.stopPropagation();
    const tk0 = s0.tracks.find((x) => x.id === clip.trackId);
    const lockedIds = new Set(s0.tracks.filter((x) => x.locked).map((x) => x.id));
    const tool = s0.tool;
    if (tool === "razor") {
      const el = scrollRef.current!;
      const frame = 1000 / (s0.video?.fps || 30);
      let ms = xToMs(e.clientX - el.getBoundingClientRect().left + el.scrollLeft);
      if (s0.snap && Math.abs(msToX(ms - s0.time)) < SNAP_PX) ms = s0.time;
      st.splitClip(clip.id, Math.round(Math.round(ms / frame) * frame));
      return;
    }
    st.selectClip(clip.id);
    if (tk0?.locked) return;
    let group = s0.linkedSelection && clip.linkId ? s0.clips.filter((c) => c.linkId === clip.linkId) : [clip];
    const segs0 = s0.segments;
    if (tool === "trackFwd") group = s0.clips.filter((c) => c.start >= clip.start && !lockedIds.has(c.trackId));
    const ids = new Set(group.map((c) => c.id));
    const srcLen = (c: Clip) => {
      const a = c.assetId ? assetById.get(c.assetId) : undefined;
      return a && a.kind !== "image" ? a.duration_ms : Infinity;
    };
    const target = e.currentTarget as HTMLElement;
    target.setPointerCapture(e.pointerId);
    const x0 = e.clientX;
    const y0 = e.clientY;
    const level0 = tk0?.kind === "audio" ? clipFx(clip).volume : clipFx(clip).opacity;
    const move = (ev: PointerEvent) => {
      const s = useEditor.getState();
      // Bước kéo theo frame để clip luôn bắt đầu/kết thúc đúng khung hình.
      const frame = 1000 / (s.video?.fps || 30);
      let d = Math.round(Math.round(xToMs(ev.clientX - x0) / frame) * frame);
      const patches: Record<string, Partial<Clip>> = {};

      if (tool === "pen") {
        const v = Math.min(1, Math.max(0, level0 - (ev.clientY - y0) / (trackH(tk0!) - 6 - CLIP_HEADER_H)));
        const fx = { ...clip.fx, ...(tk0?.kind === "audio" ? { volume: v } : { opacity: v }) };
        s.patchClips({ [clip.id]: { fx } });
        return;
      }
      if (tool === "slip") {
        if (mode !== "move") return;
        for (const c of group) {
          const len = srcLen(c);
          if (len === Infinity) continue;
          patches[c.id] = { in: Math.min(Math.max(0, c.in - d), len - c.dur) };
        }
        s.patchClips(patches);
        return;
      }

      if (mode === "move") {
        d += snapDelta([clip.start + d, clipEnd(clip) + d], ids);
        d = Math.max(d, -Math.min(...group.map((c) => c.start)));
        // Đổi track: chỉ khi kéo một clip (hoặc cặp liên kết), sang track cùng loại không khoá.
        let trackId = clip.trackId;
        if (tool === "select") {
          const lane = document.elementFromPoint(ev.clientX, ev.clientY)?.closest<HTMLElement>("[data-track]");
          const over = s.tracks.find((x) => x.id === lane?.dataset.track);
          if (over && over.kind === tk0?.kind && !over.locked) trackId = over.id;
        }
        for (const c of group) patches[c.id] = { start: c.start + d, ...(c.id === clip.id ? { trackId } : {}) };
      } else if (mode === "start") {
        d += snapDelta([clip.start + d], ids);
        // Không trim quá đầu file nguồn, không quá 0, chừa tối thiểu MIN_CLIP_MS.
        const lo = Math.max(...group.map((c) => (srcLen(c) === Infinity ? (tool === "ripple" ? -Infinity : -c.start) : Math.max(-c.in, tool === "ripple" ? -Infinity : -c.start))));
        const hi = Math.min(...group.map((c) => c.dur - MIN_CLIP_MS));
        d = Math.min(Math.max(d, lo), hi);
        // Ripple đầu clip: vị trí giữ nguyên, phần sau dồn sang trái đúng bằng phần bị cắt.
        for (const c of group) patches[c.id] = tool === "ripple" ? { dur: c.dur - d, in: c.in + d } : { start: c.start + d, dur: c.dur - d, in: c.in + d };
      } else {
        d += snapDelta([clipEnd(clip) + d], ids);
        const lo = Math.max(...group.map((c) => MIN_CLIP_MS - c.dur));
        const hi = Math.min(...group.map((c) => srcLen(c) - c.in - c.dur));
        d = Math.min(Math.max(d, lo), hi);
        for (const c of group) patches[c.id] = { dur: c.dur + d };
      }

      if (tool === "ripple" && mode !== "move") {
        // Các clip sau mép bị trim trên cùng track dịch theo.
        const shift = mode === "end" ? d : -d;
        for (const c of group)
          for (const o of s0.clips)
            if (o.trackId === c.trackId && !ids.has(o.id) && o.start >= clipEnd(c) && !lockedIds.has(o.trackId))
              patches[o.id] = { start: o.start + shift };
        s.patchClips(patches);
        return;
      }
      // Chỉ áp dụng nếu không chồng lên clip khác; nếu chồng thì giữ vị trí hợp lệ gần nhất.
      const others = s.clips.filter((c) => !ids.has(c.id));
      const ok = group.every((c) => {
        const n = { ...c, ...patches[c.id] };
        return fits(others, n.trackId, n.start, n.dur);
      });
      if (!ok) return;
      if (tool === "trackFwd" && segs0.length) {
        // Phụ đề phía sau đi cùng để giữ khớp hình/chữ; không cho chồng lên phụ đề đứng trước.
        const prevEnd = segs0.filter((g) => g.start < clip.start).reduce((m, g) => Math.max(m, g.end), 0);
        const firstAfter = segs0.find((g) => g.start >= clip.start);
        if (firstAfter && firstAfter.start + d < prevEnd) return;
        s.set({ segments: segs0.map((g) => (g.start >= clip.start ? { ...g, start: g.start + d, end: g.end + d } : g)) });
      }
      s.patchClips(patches);
    };
    const up = () => {
      target.removeEventListener("pointermove", move);
      target.removeEventListener("pointerup", up);
      useEditor.getState().pruneEmptyTracks();
    };
    target.addEventListener("pointermove", move);
    target.addEventListener("pointerup", up);
  };

  // Kéo phụ đề: di chuyển hoặc trim 2 đầu, không chồng lên phụ đề bên cạnh, hít vào playhead.
  const dragCaption = (e: React.PointerEvent, seg: Segment, mode: "move" | "start" | "end") => {
    e.stopPropagation();
    st.set({ selectedId: seg.id, selectedClipId: null, inspectorTab: "caption" });
    if (tracks.some((x) => x.kind === "caption" && x.locked)) return;
    const target = e.currentTarget as HTMLElement;
    target.setPointerCapture(e.pointerId);
    const idx = segments.findIndex((s) => s.id === seg.id);
    const lo = idx > 0 ? segments[idx - 1].end : 0;
    const hi = idx < segments.length - 1 ? segments[idx + 1].start : Math.max(durationMs, seg.end);
    const x0 = e.clientX;
    const len = seg.end - seg.start;
    const snap = (ms: number) => {
      const ph = useEditor.getState().time;
      return useEditor.getState().snap && Math.abs(msToX(ms) - msToX(ph)) < SNAP_PX ? ph : Math.round(ms);
    };
    const move = (ev: PointerEvent) => {
      const d = xToMs(ev.clientX - x0);
      if (mode === "move") {
        let start = snap(seg.start + d);
        const endSnap = snap(seg.end + d);
        if (endSnap !== Math.round(seg.end + d)) start = endSnap - len;
        start = Math.min(Math.max(lo, start), hi - len);
        st.updateSegment(seg.id, { start, end: start + len });
      } else if (mode === "start") {
        st.updateSegment(seg.id, { start: Math.min(Math.max(lo, snap(seg.start + d)), seg.end - MIN_LEN) });
      } else {
        st.updateSegment(seg.id, { end: Math.max(Math.min(hi, snap(seg.end + d)), seg.start + MIN_LEN) });
      }
    };
    const up = () => {
      target.removeEventListener("pointermove", move);
      target.removeEventListener("pointerup", up);
    };
    target.addEventListener("pointermove", move);
    target.addEventListener("pointerup", up);
  };

  const visStart = xToMs(view.left - 50);
  const visEnd = xToMs(view.left + view.width + 50);
  const visibleSegs = segments.filter((s) => s.end >= visStart && s.start <= visEnd);
  // Thao tác theo playhead đọc time lúc bấm: panel không theo dõi time để khỏi render lại mỗi khung hình khi phát.
  const now = () => useEditor.getState().time;
  const selClip = clips.find((c) => c.id === selectedClipId);

  const split = () => (selClip ? st.splitClip(selClip.id, now()) : st.splitAt(now()));
  const remove = () => (selClip ? st.removeClip(selClip.id) : selectedId && st.removeSegment(selectedId));
  const menuClip = menu && "clip" in menu ? clips.find((c) => c.id === menu.clip) : undefined;
  const menuTrack = menu && "track" in menu ? tracks.find((x) => x.id === menu.track) : undefined;

  return (
    <div className="timeline-root">
    <ToolPalette />
    <div className="timeline">
      <div className="tl-toolbar">
        <Timecode className="tc tl-tc" fps={fps} />
        <div className="tl-tools">
          <button className={`tl-ico ${snapOn ? "on" : ""}`} title={t("tl.snap")} onClick={() => st.set({ snap: !snapOn })}><IconMagnet /></button>
          <button className={`tl-ico ${linkedSelection ? "on" : ""}`} title={t("tl.linkedSelection")} onClick={() => st.set({ linkedSelection: !linkedSelection })}><IconLink /></button>
        </div>
        <div className="tl-tools">
          <button className="ibtn" title={t("tl.addTitle")} onClick={() => st.addSegmentAt(now())} disabled={!hasContent}>＋ {t("tl.add")}</button>
          <button className="ibtn" title={t("tl.addTextTitle")} onClick={() => st.addTextClip(now())}>＋ {t("tl.addText")}</button>
          <button className="tl-ico" title={t("tl.splitTitle")} onClick={split} disabled={!hasContent}><IconScissors /></button>
          <button className="tl-ico" title={t("tl.deleteTitle")} onClick={remove} disabled={!selectedId && !selClip}><IconTrash /></button>
        </div>
        <div className="tl-tools">
          <button className="ibtn" title={t("tl.addVideoTrack")} onClick={() => st.addTrack("video")}>＋V</button>
          <button className="ibtn" title={t("tl.addAudioTrack")} onClick={() => st.addTrack("audio")}>＋A</button>
          <button className="ibtn" title={t("tl.addTextTrack")} onClick={() => st.addTrack("text")}>＋T</button>
        </div>
        <span className="grow" />
        <span className="muted small">{t("tl.count", { n: segments.length })}</span>
        <button className="ibtn" title={t("tl.zoomOut")} onClick={() => zoomTo(pxPerSec / 1.5)}>−</button>
        <input type="range" className="tl-zoom" min={Math.log(MIN_PX)} max={Math.log(MAX_PX)} step={0.01}
          value={Math.log(pxPerSec)} onChange={(e) => zoomTo(Math.exp(Number(e.target.value)))} />
        <button className="ibtn" title={t("tl.zoomIn")} onClick={() => zoomTo(pxPerSec * 1.5)}>＋</button>
        <button className="ibtn" title={t("tl.fitTitle")} onClick={fitAll} disabled={!hasContent}>{t("tl.fit")}</button>
      </div>

      <div className="tl-main" onScroll={(e) => e.currentTarget.style.setProperty("--tl-scroll-y", `${e.currentTarget.scrollTop}px`)}>
        <div className="tl-heads" style={{ width: HEAD_W }}>
          <div className="tl-corner" style={{ height: RULER_H }} />
          {ordered.map((tk) => (
            <TrackHead key={tk.id} tk={tk} code={trackCode(tracks, tk)}
              fallback={tk.kind === "caption" ? t("tl.caption") : ""}
              renaming={renaming === tk.id} onRename={(on) => setRenaming(on ? tk.id : null)}
              onMenu={(e) => { e.preventDefault(); setMenu({ x: e.clientX, y: e.clientY, track: tk.id }); }} />
          ))}
        </div>

        <div ref={scrollRef} className={`tl-scroll tool-${tool}`} onPointerDownCapture={pan}>
          <div className="tl-content" style={{ width: contentW }}>
            <div className="tl-ruler" style={{ height: RULER_H }} onPointerDown={scrub}>
              <canvas ref={rulerRef} style={{ left: view.left, width: view.width, height: RULER_H }} />
            </div>

            {ordered.map((tk) => (
              <div key={tk.id} data-track={tk.id}
                className={`tl-track ${tk.kind} ${trackHidden(tk) || (tk.kind === "audio" && !trackAudible(tracks, tk)) ? "off" : ""} ${dropTrack === tk.id ? "drop-on" : ""}`}
                style={{ height: trackH(tk) }}
                onPointerDown={(e) => {
                  if (useEditor.getState().tool === "type" && e.button === 0) {
                    const el = scrollRef.current!;
                    st.addTextClip(xToMs(e.clientX - el.getBoundingClientRect().left + el.scrollLeft), tk.kind === "text" ? tk.id : undefined);
                    return;
                  }
                  st.set({ selectedId: null, selectedClipId: null });
                  scrub(e);
                }}>
                {tk.kind === "caption"
                  ? visibleSegs.map((s) => (
                      <div key={s.id} className={`clip cap-clip ${s.id === selectedId ? "selected" : ""}`}
                        style={{ left: msToX(s.start), width: Math.max(2, msToX(s.end - s.start)) }}
                        onPointerDown={(e) => dragCaption(e, s, "move")}
                        onDoubleClick={() => { st.seek(s.start); st.set({ selectedId: s.id, inspectorTab: "caption" }); }}
                        title={[s.src, s.vi].filter(Boolean).join("\n")}>
                        <i className="trim l" onPointerDown={(e) => dragCaption(e, s, "start")} />
                        <span className={`cap-src ${tk.srcHidden ? "dim" : ""}`}>{s.src}</span>
                        <span className={`cap-vi ${tk.hidden ? "dim" : ""}`}>{s.vi}</span>
                        <i className="trim r" onPointerDown={(e) => dragCaption(e, s, "end")} />
                      </div>
                    ))
                  : clips
                      .filter((c) => c.trackId === tk.id && clipEnd(c) >= visStart && c.start <= visEnd)
                      .map((c) => (
                        <ClipView key={c.id} clip={c} asset={c.assetId ? assetById.get(c.assetId) : undefined} kind={tk.kind}
                          level={tk.kind === "audio" ? clipFx(c).volume : tool === "pen" ? clipFx(c).opacity : undefined}
                          color={tk.kind === "audio" ? AUDIO_COLORS[tracks.filter((x) => x.kind === "audio").indexOf(tk) % AUDIO_COLORS.length] : undefined}
                          height={trackH(tk)} selected={c.id === selectedClipId}
                          linkedSelected={linkedSelection && !!selClip?.linkId && selClip.linkId === c.linkId && c.id !== selClip.id}
                          pxPerSec={pxPerSec} view={view} theme={theme}
                          onDrag={(e, mode) => dragMediaClip(e, c, mode)}
                          onMenu={(e) => { e.preventDefault(); st.selectClip(c.id); setMenu({ x: e.clientX, y: e.clientY, clip: c.id }); }} />
                      ))}
                {tk.dub && !clips.some((c) => c.trackId === tk.id) && hasContent && (
                  <span className="lane-hint" style={{ left: view.left + 12 }}>{t("tl.dubHint")}</span>
                )}
              </div>
            ))}

            {!hasContent && <div className="tl-empty" style={{ left: view.left }}>{t("tl.dropHint")}</div>}

            {hasContent && (
              <Playhead pxPerSec={pxPerSec} scrollRef={scrollRef} />
            )}
          </div>
        </div>
      </div>

      {menu && (
        <div className="ctx-menu" style={{ left: menu.x, top: menu.y }} onPointerDown={(e) => e.stopPropagation()}>
          {menuClip && (
            <>
              <button disabled={now() <= menuClip.start || now() >= clipEnd(menuClip)} onClick={() => { st.splitClip(menuClip.id, now()); setMenu(null); }}>{t("tl.splitHere")}</button>
              {menuClip.linkId && <button onClick={() => { st.unlinkClip(menuClip.id); setMenu(null); }}>{t("tl.unlink")}</button>}
              <button onClick={() => { st.removeClip(menuClip.id); setMenu(null); }}>{t("tl.deleteClip")}</button>
            </>
          )}
          {menuTrack && (
            <>
              <button onClick={() => { setRenaming(menuTrack.id); setMenu(null); }}>{t("tl.renameTrack")}</button>
              <button disabled={menuTrack.kind === "caption"}
                onClick={async () => {
                  const n = clips.filter((c) => c.trackId === menuTrack.id).length;
                  setMenu(null);
                  if (!n || (await confirmDialog(t("tl.confirmDeleteTrack", { n })))) st.removeTrack(menuTrack.id);
                }}>{t("tl.deleteTrack")}</button>
            </>
          )}
        </div>
      )}
    </div>
    </div>
  );
}

/** `fallback`: tên mặc định khi người dùng chưa đặt tên. Đổi tên bằng ô nhập tại chỗ (webview macOS không có `window.prompt`). */
function TrackHead({ tk, code, fallback, renaming, onRename, onMenu }: {
  tk: Track; code: string; fallback: string; renaming: boolean; onRename: (on: boolean) => void; onMenu: (e: React.MouseEvent) => void;
}) {
  const tracks = useEditor((s) => s.tracks);
  const setTrack = useEditor((s) => s.setTrack);
  const t = useT();
  const audio = tk.kind === "audio";
  const lr = audio && trackH(tk) >= AUDIO_LR_H;
  const resize = (e: React.PointerEvent) => {
    e.preventDefault();
    e.stopPropagation();
    const y0 = e.clientY;
    const h0 = trackH(tk);
    const move = (ev: PointerEvent) =>
      useEditor.getState().setTrack(tk.id, { height: Math.round(Math.min(AUDIO_MAX_H, Math.max(TRACK_H.audio, h0 + ev.clientY - y0))) });
    const up = () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
  };
  return (
    <div className={`tl-head ${tk.kind}`} style={{ height: trackH(tk) }} onContextMenu={onMenu}
      onDoubleClick={() => onRename(true)}>
      <div className="tl-head-row">
        <button className={`tk-btn ${tk.locked ? "on lock" : ""}`} title={t("tl.lock")} onClick={() => setTrack(tk.id, { locked: !tk.locked })}>
          <IconLock open={!tk.locked} />
        </button>
        <b className="tk-code">{code}</b>
        {audio ? (
          <>
            <button className={`mix-btn m ${tk.muted ? "on" : ""}`} title={t("tl.mute")} onClick={() => setTrack(tk.id, { muted: !tk.muted })}>M</button>
            <button className={`mix-btn s ${tk.solo ? "on" : ""}`} title={t("tl.solo")} onClick={() => setTrack(tk.id, { solo: !tk.solo })}>S</button>
          </>
        ) : tk.kind === "caption" ? null : (
          <button className={`tk-btn ${tk.hidden ? "on" : ""}`} title={t("tl.hide")} onClick={() => setTrack(tk.id, { hidden: !tk.hidden })}>
            <IconEye off={tk.hidden} />
          </button>
        )}
        {renaming ? (
          <input className="tl-head-name" autoFocus defaultValue={tk.name} placeholder={fallback} title={t("tl.renamePrompt")}
            onFocus={(e) => e.currentTarget.select()}
            onDoubleClick={(e) => e.stopPropagation()}
            onBlur={(e) => { setTrack(tk.id, { name: e.currentTarget.value.trim() }); onRename(false); }}
            onKeyDown={(e) => {
              if (e.key === "Enter") e.currentTarget.blur();
              else if (e.key === "Escape") { e.currentTarget.value = tk.name; e.currentTarget.blur(); }
            }} />
        ) : (
          <span className="tl-head-name" title={tk.name || fallback}>{tk.name || fallback}</span>
        )}
        {tk.kind === "caption" && (
          <div className="cap-eyes">
            <button className={`tk-btn cap-eye ${tk.srcHidden ? "on" : ""}`} title={t("tl.hideSrc")} onClick={() => setTrack(tk.id, { srcHidden: !tk.srcHidden })}>
              <IconEye off={!!tk.srcHidden} /><small>{t("tl.capSrc")}</small>
            </button>
            <button className={`tk-btn cap-eye ${tk.hidden ? "on" : ""}`} title={t("tl.hideVi")} onClick={() => setTrack(tk.id, { hidden: !tk.hidden })}>
              <IconEye off={tk.hidden} /><small>{t("tl.capVi")}</small>
            </button>
          </div>
        )}
      </div>
      {audio && (
        <div className={`tl-mix ${trackAudible(tracks, tk) ? "" : "silent"}`}>
          <input type="range" min={0} max={1} step={0.01} value={tk.gain} title={t("tl.gain", { n: Math.round(tk.gain * 100) })}
            onChange={(e) => setTrack(tk.id, { gain: Number(e.target.value) })}
            onDoubleClick={(e) => { e.stopPropagation(); setTrack(tk.id, { gain: 1 }); }} />
          <span className="tk-gain">{Math.round(tk.gain * 100)}</span>
        </div>
      )}
      {lr && (["gainL", "gainR"] as const).map((k) => (
        <div key={k} className={`tl-mix ch ${trackAudible(tracks, tk) ? "" : "silent"}`}>
          <b className="tk-ch">{t(k === "gainL" ? "tl.chL" : "tl.chR")}</b>
          <input type="range" min={0} max={1} step={0.01} value={tk[k] ?? 1} title={t(k === "gainL" ? "tl.gainL" : "tl.gainR", { n: Math.round((tk[k] ?? 1) * 100) })}
            onChange={(e) => setTrack(tk.id, { [k]: Number(e.target.value) })}
            onDoubleClick={(e) => { e.stopPropagation(); setTrack(tk.id, { [k]: 1 }); }} />
          <span className="tk-gain">{Math.round((tk[k] ?? 1) * 100)}</span>
        </div>
      ))}
      {audio && <div className="tl-resize" title={t("tl.resize")} onPointerDown={resize} onDoubleClick={(e) => { e.stopPropagation(); setTrack(tk.id, { height: undefined }); }} />}
    </div>
  );
}

interface ClipViewProps {
  clip: Clip;
  asset?: MediaAsset;
  kind: Track["kind"];
  /** Màu nền clip âm thanh (theo track). */
  color?: string;
  /** Mức âm lượng/độ mờ 0..1 vẽ thành đường ngang (công cụ Bút). */
  level?: number;
  height: number;
  selected: boolean;
  linkedSelected: boolean;
  pxPerSec: number;
  view: { left: number; width: number };
  theme: string;
  onDrag: (e: React.PointerEvent, mode: "move" | "start" | "end") => void;
  onMenu: (e: React.MouseEvent) => void;
}

const CLIP_HEADER_H = 15;

/** Clip kiểu Premiere: thanh tên ở trên; video có ảnh đầu/cuối clip, âm thanh có waveform phủ kín thân clip. */
function ClipView({ clip, asset, kind, color, level, height, selected, linkedSelected, pxPerSec, view, theme, onDrag, onMenu }: ClipViewProps) {
  const msToX = (ms: number) => (ms / 1000) * pxPerSec;
  const left = msToX(clip.start);
  const width = Math.max(2, msToX(clip.dur));
  const name = asset ? asset.path.replace(/^.*[\\/]/, "") : clip.text ?? "";
  const cls = kind === "audio" ? "audio-clip" : kind === "text" ? "text-clip" : asset?.kind === "image" ? "video-clip image" : "video-clip";
  const bodyH = height - 6 - CLIP_HEADER_H;
  const thumbW = Math.round((bodyH * 16) / 9);

  // Dải ảnh phủ kín thân clip, mỗi ô lấy thumbnail gần nhất theo thời gian nguồn; chỉ vẽ các ô đang nằm trong vùng nhìn thấy.
  let thumbs: React.ReactNode = null;
  if (kind === "video" && asset?.kind === "video" && asset.thumbs.length && thumbW > 0) {
    const at = (ms: number) => asset.thumbs[Math.min(asset.thumbs.length - 1, Math.max(0, Math.floor((ms / asset.duration_ms) * asset.thumbs.length)))];
    const n = Math.ceil(width / thumbW);
    const first = Math.max(0, Math.floor((view.left - left) / thumbW));
    const last = Math.min(n - 1, Math.floor((view.left + view.width - left) / thumbW));
    const tiles: React.ReactNode[] = [];
    for (let i = first; i <= last; i++)
      tiles.push(<img key={i} className="thumb" src={convertFileSrc(at(clip.in + ((i * thumbW) / pxPerSec) * 1000))} draggable={false} style={{ left: i * thumbW, width: thumbW }} />);
    thumbs = <>{tiles}</>;
  } else if (kind === "video" && asset?.kind === "image") {
    thumbs = <div className="thumb head" style={{ width: thumbW, backgroundImage: `url("${convertFileSrc(asset.path)}")` }} />;
  }

  return (
    <div className={`clip ${cls} ${selected ? "selected" : ""} ${linkedSelected ? "linked-sel" : ""}`}
      style={{ left, width, ...(color ? { ["--clip-color" as string]: color } : {}) }} title={name}
      onPointerDown={(e) => onDrag(e, "move")} onContextMenu={onMenu}>
      {kind === "text" ? (
        // Clip chữ: không có thanh tên riêng, nội dung chữ nằm giữa cả chiều cao clip.
        <>
          <span className="clip-name text-name"><IconType /><span className="tn">{name || "…"}</span></span>
          {level !== undefined && <div className="clip-level" style={{ top: `${(1 - level) * 100}%` }} />}
        </>
      ) : (
        <div className="clip-head">
          <span className="clip-name">{clip.linkId ? <IconLink /> : null}{name}</span>
        </div>
      )}
      {kind !== "text" && (
        <div className="clip-body">
          {thumbs}
          {kind === "audio" && asset && <ClipWave clip={clip} asset={asset} height={bodyH} pxPerSec={pxPerSec} view={view} theme={theme} />}
          {level !== undefined && <div className="clip-level" style={{ top: `${(1 - level) * 100}%` }} />}
        </div>
      )}
      <i className="trim l" onPointerDown={(e) => onDrag(e, "start")} />
      <i className="trim r" onPointerDown={(e) => onDrag(e, "end")} />
    </div>
  );
}

/** Waveform của phần clip đang nhìn thấy, vẽ trên canvas đúng bằng phần đó. */
function ClipWave({ clip, asset, height, pxPerSec, view, theme }: { clip: Clip; asset: MediaAsset; height: number; pxPerSec: number; view: { left: number; width: number }; theme: string }) {
  const ref = useRef<HTMLCanvasElement>(null);
  const peaksPerSec = useEditor((s) => s.peaksPerSec);
  const clipX = (clip.start / 1000) * pxPerSec;
  const a = Math.max(0, view.left - clipX);
  const b = Math.min((clip.dur / 1000) * pxPerSec, view.left + view.width - clipX);
  const w = Math.max(0, Math.ceil(b - a));
  useEffect(() => {
    const c = ref.current;
    if (!c || !w) return;
    const dpr = window.devicePixelRatio || 1;
    c.width = w * dpr;
    c.height = height * dpr;
    const g = c.getContext("2d")!;
    g.scale(dpr, dpr);
    g.clearRect(0, 0, w, height);
    const peaks = asset.peaks;
    if (!peaks.length) return;
    g.fillStyle = getComputedStyle(c).getPropertyValue("--clip-wave").trim() || getComputedStyle(c).getPropertyValue("--tl-wave");
    const mid = height / 2;
    const srcPx = (clip.in / 1000) * pxPerSec;
    for (let x = 0; x < w; x++) {
      const px = srcPx + a + x;
      const i0 = Math.floor((px / pxPerSec) * peaksPerSec);
      const i1 = Math.max(i0 + 1, Math.floor(((px + 1) / pxPerSec) * peaksPerSec));
      let m = 0;
      for (let i = i0; i < i1 && i < peaks.length; i++) m = Math.max(m, peaks[i]);
      const amp = Math.max(0.5, Math.sqrt(m) * (mid - 1));
      g.fillRect(x, mid - amp, 1, amp * 2);
    }
  }, [w, a, height, pxPerSec, clip.in, asset.peaks, peaksPerSec, theme]);
  if (!w) return null;
  return <canvas ref={ref} className="wave" style={{ left: a, width: w, height }} />;
}
