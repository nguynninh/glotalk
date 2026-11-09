import { useEffect, useRef } from "react";
import { convertFileSrc } from "@tauri-apps/api/core";
import { Compositor, Layer } from "../lib/compositor";
import { Clip, clipAt, clipFx, MediaAsset, Track, trackAudible } from "../lib/timeline";
import { sequenceSize, timelineEnd, useEditor } from "../store/editor";

type Media = HTMLVideoElement | HTMLAudioElement | HTMLImageElement;
const S = () => useEditor.getState();

/** Lệch quá ngưỡng này khi đang phát thì tua hẳn phần tử media (giây); nhỏ hơn thì chỉnh tốc độ phát cho bắt kịp. */
const DRIFT_S = 1;
/** Lệch dưới ngưỡng này coi như khớp (giây). */
const SYNC_S = 0.03;
/** Nạp trước clip sắp tới để chuyển cảnh không bị khựng (ms). */
const PRELOAD_MS = 1500;
/** Giữ lại phần tử media của clip cách playhead trong khoảng này để tua qua lại không phải nạp lại (ms). */
const KEEP_MS = 10_000;


/** Thống kê một lần phát, ghi vào log khi dừng để tìm chỗ khựng. */
export interface PlayStats {
  sec: number;
  frames: number;
  /** Số khung rAF cách nhau quá 50 ms (luồng chính bị nghẽn) */
  jank: number;
  maxGap: number;
  /** Thời gian ghép hình trung bình mỗi khung (ms) */
  renderMs: number;
  seeks: number;
  rates: number;
  dropped: number;
  total: number;
  mode: string;
}

/**
 * Bộ phát của Program monitor: đồng hồ theo timeline (không theo một thẻ <video> nào), mỗi clip một phần tử media
 * được tua/phát theo đồng hồ đó, các lớp hình ghép bằng Compositor, mỗi clip âm thanh một thẻ <audio> với âm lượng
 * = tổng × track × clip. Video trên track hình luôn tắt tiếng: tiếng của nó nằm ở clip âm thanh liên kết.
 */
export function useProgram(
  hostRef: React.RefObject<HTMLDivElement | null>,
  onFallback: (err: string) => void,
  onPlayError: (err: string) => void,
  onStats: (stats: PlayStats) => void,
) {
  const pool = useRef(new Map<string, Media>());
  const compRef = useRef<Compositor | null>(null);

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;
    const comp = new Compositor(host);
    compRef.current = comp;
    let reported = !!comp.fallbackReason;
    if (comp.fallbackReason) onFallback(comp.fallbackReason);
    const media = pool.current;
    let dirty = true;
    let raf = 0;
    let anchor = { perf: performance.now(), ms: S().time };
    let wasPlaying = false;
    let seekN = S().seekTo?.n ?? 0;
    const unsub = useEditor.subscribe((s, p) => {
      if (s.time !== p.time || s.clips !== p.clips || s.tracks !== p.tracks || s.assets !== p.assets || s.mix !== p.mix || s.video !== p.video)
        dirty = true;
    });

    const create = (clip: Clip, asset: MediaAsset, kind: Track["kind"]): Media => {
      let el: Media;
      if (asset.kind === "image") {
        el = new Image();
      } else {
        const m = document.createElement(kind === "audio" ? "audio" : "video");
        m.preload = "auto";
        m.muted = kind !== "audio";
        if (m instanceof HTMLVideoElement) {
          m.playsInline = true;
          // WebKit dừng ngầm video tắt tiếng không nằm trong trang/không nhìn thấy (paused vẫn false), nên gắn
          // vào khung program, nằm dưới canvas đục (canvas che hoàn toàn).
          m.className = "program-src";
          host.prepend(m);
        }
        m.addEventListener("seeked", () => (dirty = true));
        m.addEventListener("loadeddata", () => (dirty = true));
        el = m;
      }
      // Cần CORS để WebGL đọc được khung hình; giao thức asset của Tauri trả Access-Control-Allow-Origin theo origin cửa sổ.
      el.crossOrigin = "anonymous";
      if (el instanceof HTMLImageElement) el.addEventListener("load", () => (dirty = true));
      el.dataset.path = asset.path;
      el.dataset.clip = clip.id;
      el.src = convertFileSrc(asset.path);
      return el;
    };
    // Âm lượng riêng L/R cần Web Audio. Chỉ nối thẻ <audio> vào khi track có chỉnh L/R (một khi đã nối thì không gỡ ra
    // được), còn lại vẫn phát thẳng như cũ. Khi đã nối, mọi âm lượng đặt ở nút gain, thẻ <audio> để volume = 1.
    let actx: AudioContext | null = null;
    const routes = new Map<string, { src: MediaElementAudioSourceNode; l: GainNode; r: GainNode }>();
    const route = (id: string, el: HTMLAudioElement) => {
      let rt = routes.get(id);
      if (rt) return rt;
      actx ??= new AudioContext();
      // Ép về 2 kênh trước khi tách để file mono cũng có cả tai phải.
      const up = new GainNode(actx, { channelCount: 2, channelCountMode: "explicit", channelInterpretation: "speakers" });
      const split = actx.createChannelSplitter(2);
      const merge = actx.createChannelMerger(2);
      rt = { src: actx.createMediaElementSource(el), l: actx.createGain(), r: actx.createGain() };
      rt.src.connect(up).connect(split);
      split.connect(rt.l, 0).connect(merge, 0, 0);
      split.connect(rt.r, 1).connect(merge, 0, 1);
      merge.connect(actx.destination);
      routes.set(id, rt);
      return rt;
    };

    const drop = (id: string) => {
      const el = media.get(id);
      if (!el) return;
      routes.get(id)?.src.disconnect();
      routes.delete(id);
      if (!(el instanceof HTMLImageElement)) {
        el.pause();
        el.removeAttribute("src");
        el.load();
        el.remove();
      }
      media.delete(id);
    };

    let st = { t0: 0, last: 0, frames: 0, jank: 0, maxGap: 0, render: 0, seeks: 0, rates: 0, q0: { d: 0, t: 0 }, el: null as HTMLVideoElement | null, direct: 0 };
    const quality = (el: HTMLVideoElement | null) => {
      const q = el?.getVideoPlaybackQuality?.();
      return { d: q?.droppedVideoFrames ?? 0, t: q?.totalVideoFrames ?? 0 };
    };

    const frame = () => {
      raf = requestAnimationFrame(frame);
      const s = S();
      const tNow = performance.now();
      if (s.playing && !wasPlaying) st = { ...st, t0: tNow, last: tNow, frames: 0, jank: 0, maxGap: 0, render: 0, seeks: 0, rates: 0, el: null, direct: 0 };
      if (s.playing && wasPlaying) {
        const gap = tNow - st.last;
        st.frames++;
        if (gap > 50) st.jank++;
        st.maxGap = Math.max(st.maxGap, gap);
        st.last = tNow;
      }
      if (!s.playing && wasPlaying && st.frames > 0) {
        const q = quality(st.el);
        onStats({
          sec: Math.round((tNow - st.t0) / 100) / 10, frames: st.frames, jank: st.jank, maxGap: Math.round(st.maxGap),
          renderMs: Math.round((st.render / st.frames) * 10) / 10, seeks: st.seeks, rates: st.rates,
          dropped: q.d - st.q0.d, total: q.t - st.q0.t, mode: st.direct > st.frames / 2 ? "video" : "canvas",
        });
      }
      let now = s.time;
      const seekNow = s.seekTo?.n ?? 0;
      if (s.playing) {
        if (!wasPlaying || seekNow !== seekN) anchor = { perf: performance.now(), ms: s.time };
        now = anchor.ms + (performance.now() - anchor.perf);
        const end = timelineEnd(s);
        if (now >= end) {
          now = end;
          s.set({ playing: false, time: end });
        } else s.set({ time: now });
      }
      const playStarted = s.playing !== wasPlaying;
      wasPlaying = s.playing;
      seekN = seekNow;

      // Đồng bộ các phần tử media với đồng hồ.
      const assets = new Map(s.assets.map((a) => [a.id, a]));
      const tracks = new Map(s.tracks.map((t) => [t.id, t]));
      const keep = new Set<string>();
      for (const c of s.clips) {
        const tk = tracks.get(c.trackId);
        const asset = c.assetId ? assets.get(c.assetId) : undefined;
        if (!tk || !asset || (tk.kind === "video" && tk.hidden)) continue;
        if (c.start + c.dur < now - KEEP_MS || c.start > now + KEEP_MS) continue;
        keep.add(c.id);
        let el = media.get(c.id);
        if (!el || el.dataset.path !== asset.path) {
          if (el) drop(c.id);
          el = create(c, asset, tk.kind);
          media.set(c.id, el);
        }
        if (el instanceof HTMLImageElement) continue;
        const active = clipAt(c, now);
        const upcoming = !active && c.start > now && c.start - now < PRELOAD_MS;
        const target = active ? (now - c.start + c.in) / 1000 : c.in / 1000;
        if (el instanceof HTMLAudioElement) {
          const fx = clipFx(c);
          const vol = s.mix.masterMuted || !trackAudible(s.tracks, tk) ? 0 : Math.min(1, s.mix.master * tk.gain * fx.volume);
          const [gl, gr] = [tk.gainL ?? 1, tk.gainR ?? 1];
          if (routes.has(c.id) || gl !== 1 || gr !== 1) {
            const rt = route(c.id, el);
            el.volume = 1;
            rt.l.gain.value = vol * gl;
            rt.r.gain.value = vol * gr;
            if (s.playing && actx!.state === "suspended") actx!.resume().catch(() => {});
          } else el.volume = vol;
        }
        if (active && s.playing) {
          const drift = el.currentTime - target;
          if (el.paused) {
            if (Math.abs(drift) > 0.04) el.currentTime = target;
            if (el.playbackRate !== 1) el.playbackRate = 1;
            el.play().catch((e) => {
              // Báo một lần mỗi phần tử: trình duyệt chặn phát hoặc không giải mã được file.
              if (el.dataset.playErr) return;
              el.dataset.playErr = "1";
              onPlayError(`${el.dataset.path?.split("/").pop()}: ${e?.name ?? ""} ${e?.message ?? e}`);
            });
          } else if (playStarted || (!el.seeking && Math.abs(drift) > DRIFT_S)) {
            if (!playStarted) st.seeks++;
            el.currentTime = target;
            if (el.playbackRate !== 1) el.playbackRate = 1;
          } else if (!el.seeking) {
            // Lệch nhỏ: nhanh/chậm tối đa 10% cho bắt kịp thay vì tua (tua gây khựng hình và tiếng).
            // Chỉ 3 mức để không gán tốc độ mới mỗi khung hình (đổi tốc độ liên tục làm khựng trên WebKit).
            const rate = Math.abs(drift) <= SYNC_S ? 1 : drift > 0 ? 0.95 : 1.05;
            if (el.playbackRate !== rate) {
              el.playbackRate = rate;
              st.rates++;
            }
          }
        } else {
          if (!el.paused) el.pause();
          // Tua khi dừng: chỉ khi lệch hơn nửa frame và không đang tua dở, tránh dồn lệnh khi kéo playhead.
          const half = 0.5 / (s.video?.fps || 30);
          if ((active || upcoming) && !el.seeking && Math.abs(el.currentTime - target) > half) el.currentTime = target;
        }
      }
      for (const id of [...media.keys()]) if (!keep.has(id)) drop(id);

      if (!dirty && !s.playing) return;
      dirty = false;
      // Ghép hình: V1 dưới cùng, track video sau nằm trên.
      const layers: Layer[] = [];
      for (const tk of s.tracks) {
        if (tk.kind !== "video" || tk.hidden) continue;
        for (const c of s.clips) {
          if (c.trackId !== tk.id || !clipAt(c, now)) continue;
          const el = media.get(c.id);
          if (!el) continue;
          const w = el instanceof HTMLImageElement ? el.naturalWidth : (el as HTMLVideoElement).videoWidth;
          const h = el instanceof HTMLImageElement ? el.naturalHeight : (el as HTMLVideoElement).videoHeight;
          const ready = el instanceof HTMLImageElement ? el.complete && w > 0 : (el as HTMLVideoElement).readyState >= 2;
          if (!ready || !w || !h) continue;
          const fx = clipFx(c);
          layers.push({ source: el as HTMLVideoElement | HTMLImageElement, w, h, opacity: fx.opacity, scale: fx.scale, x: fx.x, y: fx.y, key: fx.key });
        }
      }
      // Chế độ nhanh: một lớp video không hiệu ứng thì để thẻ <video> tự hiển thị (giải mã và vẽ bằng phần cứng),
      // khỏi chép từng khung vào WebGL. object-fit: contain trong khung tỉ lệ sequence trùng với layerRect.
      const one = layers.length === 1 ? layers[0] : null;
      const direct = one && one.source instanceof HTMLVideoElement && one.opacity === 1 && one.scale === 1 && one.x === 0 && one.y === 0 && !one.key?.enabled
        ? one.source : null;
      for (const el of media.values()) if (el instanceof HTMLVideoElement) el.style.zIndex = el === direct ? "0" : "-1";
      comp.setVisible(!direct);
      const vid = layers.find((l) => l.source instanceof HTMLVideoElement)?.source as HTMLVideoElement | undefined;
      if (s.playing && vid && st.el !== vid) {
        st.el = vid;
        st.q0 = quality(vid);
      }
      if (direct) {
        if (s.playing) st.direct++;
        return;
      }
      const { W, H } = sequenceSize(s);
      const r0 = performance.now();
      comp.render(layers, W, H);
      if (s.playing) st.render += performance.now() - r0;
      if (!reported && comp.fallbackReason) {
        reported = true;
        onFallback(comp.fallbackReason);
      }
    };
    raf = requestAnimationFrame(frame);
    return () => {
      cancelAnimationFrame(raf);
      unsub();
      for (const id of [...media.keys()]) drop(id);
      actx?.close().catch(() => {});
      comp.dispose();
      compRef.current = null;
    };
  }, []);

  /** Phần tử media đang dùng cho một clip (để lấy màu khoá). */
  return { mediaOf: (clipId: string) => pool.current.get(clipId) };
}
