import { useEffect, useState } from "react";
import { api, formatTime, formatTimecode, OcrModel, STT_LANGUAGES, ToolStatus } from "../lib/api";
import { defaultSubStyle, SubStyle } from "../lib/subStyle";
import { ColorGrade, defaultGrade } from "../lib/colorGrade";
import { InspectorTab, timelineEnd, useEditor } from "../store/editor";
import { ChromaKey, ClipFx, clipFx, trackCode } from "../lib/timeline";
import { cancelOcr, cancelRender, cancelStt, cancelTranslate, chooseOutDir, ExportRes, exportVideo, extractAudioForStt, runOcr, runStt, runTranslate } from "../store/actions";
import { Panel } from "../components/Panel";
import { Check, ColorBtn, Num, Prop, PropGroup, Slider } from "../components/ui";
import { TKey, useLangName, useT } from "../i18n";

const TABS: { id: InspectorTab; label: TKey }[] = [
  { id: "caption", label: "insp.tabCaption" },
  { id: "style", label: "insp.tabStyle" },
  { id: "color", label: "insp.tabColor" },
  { id: "detect", label: "insp.tabDetect" },
  { id: "export", label: "insp.tabExport" },
];
const FONTS = ["Arial Black", "Arial", "Roboto", "Montserrat", "Be Vietnam Pro", "Times New Roman", "Tahoma"];

export function InspectorPanel() {
  const tab = useEditor((s) => s.inspectorTab);
  const set = useEditor((s) => s.set);
  const t = useT();
  return (
    <Panel tabs={TABS.map((x) => ({ id: x.id, label: t(x.label) }))} active={tab} onTab={(id) => set({ inspectorTab: id })} className="inspector">
      {tab === "caption" && <CaptionTab />}
      {tab === "style" && <StyleTab />}
      {tab === "color" && <ColorTab />}
      {tab === "detect" && <DetectTab />}
      {tab === "export" && <ExportTab />}
    </Panel>
  );
}

function CaptionTab() {
  const clipSelected = useEditor((s) => s.selectedClipId !== null);
  return clipSelected ? <ClipTab /> : <SegmentTab />;
}

/** Thuộc tính clip media/chữ đang chọn trên timeline. */
function ClipTab() {
  const clip = useEditor((s) => s.clips.find((c) => c.id === s.selectedClipId));
  const tracks = useEditor((s) => s.tracks);
  const asset = useEditor((s) => s.assets.find((a) => a.id === clip?.assetId));
  const fps = useEditor((s) => s.video?.fps || 30);
  // Chỉ theo dõi kết quả boolean, không theo time, để khi phát panel không render lại mỗi khung hình.
  const canSplit = useEditor((s) => {
    const c = s.clips.find((x) => x.id === s.selectedClipId);
    return !!c && s.time > c.start && s.time < c.start + c.dur;
  });
  const picking = useEditor((s) => s.pickingKeyFor);
  const { patchClips, removeClip, splitClip, unlinkClip, seek, set } = useEditor.getState();
  const t = useT();
  if (!clip) return null;
  const tk = tracks.find((x) => x.id === clip.trackId);
  const fx = clipFx(clip);
  const setFx = (p: Partial<ClipFx>) => patchClips({ [clip.id]: { fx: { ...clip.fx, ...p } } });
  const setKey = (p: Partial<ChromaKey>) => setFx({ key: { ...fx.key, ...p } });
  const visual = tk?.kind === "video" || tk?.kind === "text";
  const pct = (v: number) => `${Math.round(v * 100)}%`;
  return (
    <div className="props">
      <PropGroup title={t("insp.clipTitle", { track: tk ? trackCode(tracks, tk) : "?" })}>
        {asset && <Prop label={t("insp.file")}><span className="ellipsis" title={asset.path}>{asset.path.replace(/^.*[\\/]/, "")}</span></Prop>}
        <Prop label={t("insp.start")}><span className="mono accent">{formatTimecode(clip.start, fps)}</span></Prop>
        <Prop label={t("insp.duration")}><span className="mono">{formatTime(clip.dur).slice(3)}</span></Prop>
        {asset && asset.kind !== "image" && <Prop label={t("insp.sourceIn")}><span className="mono">{formatTimecode(clip.in, fps)}</span></Prop>}
        {clip.linkId && <p className="muted small">🔗 {t("insp.linked")}</p>}
      </PropGroup>
      {clip.text !== undefined && (
        <PropGroup title={t("insp.textContent")}>
          <textarea rows={3} value={clip.text} disabled={tk?.locked} onChange={(e) => patchClips({ [clip.id]: { text: e.target.value } })} />
          <Prop label={t("insp.fontSize")}><Num value={fx.fontSize} onChange={(v) => setFx({ fontSize: Math.max(8, v) })} step={2} min={8} /></Prop>
          <Prop label={t("insp.color")}><ColorBtn value={fx.color} onChange={(v) => setFx({ color: v })} /></Prop>
        </PropGroup>
      )}
      {visual && (
        <PropGroup title={t("insp.motion")}>
          <Prop label={t("insp.scale")}><Slider value={fx.scale} min={0.1} max={3} step={0.01} def={1} format={pct} onChange={(v) => setFx({ scale: v })} /></Prop>
          <Prop label={t("insp.offsetX")}><Slider value={fx.x} min={-1} max={1} step={0.005} def={0} format={pct} onChange={(v) => setFx({ x: v })} /></Prop>
          <Prop label={t("insp.offsetY")}><Slider value={fx.y} min={-1} max={1} step={0.005} def={0} format={pct} onChange={(v) => setFx({ y: v })} /></Prop>
          <Prop label={t("insp.opacity")}><Slider value={fx.opacity} min={0} max={1} step={0.01} def={1} format={pct} onChange={(v) => setFx({ opacity: v })} /></Prop>
        </PropGroup>
      )}
      {tk?.kind === "video" && (
        <PropGroup title={t("insp.chromaKey")}>
          <Check checked={fx.key.enabled} onChange={(v) => setKey({ enabled: v })}>{fx.key.enabled ? t("insp.on") : t("insp.off")}</Check>
          <Prop label={t("insp.keyColor")}>
            <ColorBtn value={fx.key.color} onChange={(v) => setKey({ color: v, enabled: true })} />
          </Prop>
          <button className={picking === clip.id ? "pressed" : ""} onClick={() => set({ pickingKeyFor: picking === clip.id ? null : clip.id })}>
            {t("insp.pickColor")}
          </button>
          <Prop label={t("insp.similarity")}><Slider value={fx.key.similarity} min={0} max={1} step={0.01} def={0.4} format={pct} onChange={(v) => setKey({ similarity: v })} /></Prop>
          <Prop label={t("insp.smoothness")}><Slider value={fx.key.smoothness} min={0} max={1} step={0.01} def={0.08} format={pct} onChange={(v) => setKey({ smoothness: v })} /></Prop>
          <Prop label={t("insp.spill")}><Slider value={fx.key.spill} min={0} max={1} step={0.01} def={0.1} format={pct} onChange={(v) => setKey({ spill: v })} /></Prop>
        </PropGroup>
      )}
      {tk?.kind === "audio" && (
        <PropGroup title={t("insp.clipVolume")}>
          <Prop label={t("insp.clipVolume")}><Slider value={fx.volume} min={0} max={1} step={0.01} def={1} format={pct} onChange={(v) => setFx({ volume: v })} /></Prop>
        </PropGroup>
      )}
      <div className="btn-row">
        <button onClick={() => seek(clip.start)}>{t("insp.goToStart")}</button>
        <button disabled={!canSplit} onClick={() => splitClip(clip.id, useEditor.getState().time)}>{t("insp.splitAtPlayhead")}</button>
        {clip.linkId && <button onClick={() => unlinkClip(clip.id)}>{t("tl.unlink")}</button>}
        <button className="danger" onClick={() => removeClip(clip.id)}>{t("insp.delete")}</button>
      </div>
    </div>
  );
}

function SegmentTab() {
  const seg = useEditor((s) => s.segments.find((x) => x.id === s.selectedId) ?? null);
  const fps = useEditor((s) => s.video?.fps || 30);
  const video = useEditor((s) => s.video);
  const now = () => useEditor.getState().time;
  const { updateSegment, removeSegment, seek, addSegmentAt, splitAt } = useEditor.getState();
  const t = useT();

  if (!seg)
    return (
      <div className="empty-state">
        <p>{t("insp.pickCaption")}</p>
        <button className="primary" disabled={!video} onClick={() => addSegmentAt(now())}>{t("insp.addAtPlayhead")}</button>
        <p className="muted small">{t("insp.shortcuts")} <kbd>N</kbd> {t("insp.scAdd")}, <kbd>S</kbd> {t("insp.scSplit")}, <kbd>Del</kbd> {t("insp.scDelete")}, <kbd>Space</kbd> {t("insp.scPlay")}</p>
      </div>
    );

  const step = 1000 / fps;
  return (
    <div className="props">
      <PropGroup title={t("insp.captionN", { id: seg.id })}>
        <Prop label={t("insp.start")}>
          <TimeNudge value={seg.start} fps={fps} onChange={(v) => updateSegment(seg.id, { start: Math.min(v, seg.end - 100) })} step={step} />
        </Prop>
        <Prop label={t("insp.end")}>
          <TimeNudge value={seg.end} fps={fps} onChange={(v) => updateSegment(seg.id, { end: Math.max(v, seg.start + 100) })} step={step} />
        </Prop>
        <Prop label={t("insp.duration")}><span className="mono">{formatTime(seg.end - seg.start).slice(3)}</span></Prop>
        <Prop label={t("insp.speaker")}>
          <input value={seg.speaker ?? ""} placeholder={t("insp.unknown")} onChange={(e) => updateSegment(seg.id, { speaker: e.target.value })} />
        </Prop>
      </PropGroup>
      <PropGroup title={t("insp.content")}>
        <label className="field">
          <span>{t("insp.translation")}</span>
          <textarea rows={3} value={seg.vi} onChange={(e) => updateSegment(seg.id, { vi: e.target.value })} />
        </label>
        <label className="field">
          <span>{t("insp.source")}</span>
          <textarea rows={2} value={seg.src} onChange={(e) => updateSegment(seg.id, { src: e.target.value })} />
        </label>
      </PropGroup>
      <div className="btn-row">
        <button onClick={() => seek(seg.start)}>{t("insp.goToStart")}</button>
        <button onClick={() => splitAt(now())}>{t("insp.splitAtPlayhead")}</button>
        <button className="danger" onClick={() => removeSegment(seg.id)}>{t("insp.delete")}</button>
      </div>
    </div>
  );
}

function TimeNudge({ value, onChange, step, fps }: { value: number; onChange: (v: number) => void; step: number; fps: number }) {
  return (
    <div className="nudge">
      <button onClick={() => onChange(value - step)}>‹</button>
      <span className="mono accent">{formatTimecode(value, fps)}</span>
      <button onClick={() => onChange(value + step)}>›</button>
    </div>
  );
}

function StyleTab() {
  const sub = useEditor((s) => s.subStyle);
  const setStore = useEditor((s) => s.set);
  const t = useT();
  const set = <K extends keyof SubStyle>(k: K) => (v: SubStyle[K]) => setStore({ subStyle: { ...useEditor.getState().subStyle, [k]: v } });
  return (
    <div className="props">
      <PropGroup title={t("insp.text")} enabled={sub.enabled} onToggle={set("enabled")}>
        <Prop label="Font">
          <select value={sub.font} onChange={(e) => set("font")(e.target.value)}>{FONTS.map((f) => <option key={f}>{f}</option>)}</select>
        </Prop>
        <Prop label={t("insp.fontSize")}><Num value={sub.size} onChange={set("size")} step={1} min={8} /></Prop>
        <Prop label={t("insp.color")}><ColorBtn value={sub.color} onChange={set("color")} /></Prop>
        <Prop label={t("insp.bold")}><Check checked={sub.bold} onChange={set("bold")}>{sub.bold ? t("insp.on") : t("insp.off")}</Check></Prop>
        <Prop label="Scale X %"><Num value={sub.scaleX} onChange={set("scaleX")} step={1} /></Prop>
        <Prop label="Scale Y %"><Num value={sub.scaleY} onChange={set("scaleY")} step={1} /></Prop>
        <Prop label={t("insp.posY")}>
          <div className="slider-num">
            <input type="range" min={0} max={0.95} step={0.01} value={sub.posY} onChange={(e) => set("posY")(Number(e.target.value))} />
            <span>{Math.round(sub.posY * 100)}%</span>
          </div>
        </Prop>
      </PropGroup>
      <PropGroup title={t("insp.outline")}>
        <Prop label={t("insp.thickness")}><Num value={sub.outline} onChange={set("outline")} min={0} /></Prop>
        <Prop label={t("insp.color")}><ColorBtn value={sub.outlineColor} onChange={set("outlineColor")} /></Prop>
        <Prop label={t("insp.shadow")}><Num value={sub.shadow} onChange={set("shadow")} min={0} /></Prop>
      </PropGroup>
      <PropGroup title={t("insp.glow")} enabled={sub.glow} onToggle={set("glow")}>
        <Prop label={t("insp.width")}><Num value={sub.glowWidth} onChange={set("glowWidth")} min={0} /></Prop>
        <Prop label="Blur"><Num value={sub.glowBlur} onChange={set("glowBlur")} min={0} /></Prop>
        <Prop label={t("insp.color")}><ColorBtn value={sub.glowColor} onChange={set("glowColor")} /></Prop>
        <Prop label="Opacity %"><Num value={sub.glowOpacity} onChange={set("glowOpacity")} step={1} min={0} max={100} /></Prop>
      </PropGroup>
      <PropGroup title={t("insp.textBg")} enabled={sub.background} onToggle={set("background")} defaultOpen={false}>
        <Prop label={t("insp.color")}><ColorBtn value={sub.backgroundColor} onChange={set("backgroundColor")} /></Prop>
        <Prop label="Opacity %"><Num value={sub.backgroundOpacity} onChange={set("backgroundOpacity")} step={1} min={0} max={100} /></Prop>
      </PropGroup>
      <div className="btn-row">
        <button onClick={() => setStore({ subStyle: defaultSubStyle })}>{t("insp.resetDefault")}</button>
      </div>
    </div>
  );
}

function ColorTab() {
  const g = useEditor((s) => s.grade);
  const setStore = useEditor((s) => s.set);
  const t = useT();
  const set = <K extends keyof ColorGrade>(k: K) => (v: ColorGrade[K]) => setStore({ grade: { ...useEditor.getState().grade, [k]: v } });
  const tip = t("insp.sliderResetTip");
  const n1 = (v: number) => v.toFixed(1);
  return (
    <div className="props">
      <PropGroup title={t("insp.basicColor")} enabled={g.enabled} onToggle={set("enabled")}>
        <div className="prop-sub">{t("insp.whiteBalance")}</div>
        <Prop label={t("insp.temperature")}>
          <Slider value={g.temperature} onChange={set("temperature")} min={-100} max={100} step={0.5} def={0} format={n1} title={tip}
            track="linear-gradient(90deg, #3d7bff, #d9d9d9 50%, #ff9a2e)" />
        </Prop>
        <Prop label="Tint">
          <Slider value={g.tint} onChange={set("tint")} min={-100} max={100} step={0.5} def={0} format={n1} title={tip}
            track="linear-gradient(90deg, #3ccf4e, #d9d9d9 50%, #e04ce0)" />
        </Prop>
        <Prop label={t("insp.saturation")}>
          <Slider value={g.saturation} onChange={set("saturation")} min={0} max={200} step={0.5} def={100} format={n1} title={tip}
            track="linear-gradient(90deg, #8a8a8a, #e85d5d 50%, #ff2a2a)" />
        </Prop>
        <div className="prop-sub">{t("insp.light")}</div>
        <Prop label={t("insp.exposure")}>
          <Slider value={g.exposure} onChange={set("exposure")} min={-3} max={3} step={0.1} def={0} format={n1} title={tip}
            track="linear-gradient(90deg, #111, #eee)" />
        </Prop>
        <Prop label={t("insp.contrast")}>
          <Slider value={g.contrast} onChange={set("contrast")} min={-100} max={100} step={0.5} def={0} format={n1} title={tip} />
        </Prop>
      </PropGroup>
      <p className="hint muted">{t("insp.colorPreviewNote")}</p>
      <div className="btn-row">
        <button onClick={() => setStore({ grade: defaultGrade })}>{t("insp.resetDefault")}</button>
      </div>
    </div>
  );
}

const mb = (bytes: number) => (bytes / 1048576).toFixed(1);

function DetectTab() {
  const video = useEditor((s) => s.video);
  const regions = useEditor((s) => s.regions);
  const linkOcr = useEditor((s) => s.linkOcr);
  const selecting = useEditor((s) => s.selectingRegion);
  const ocr = useEditor((s) => s.ocr);
  const ocrResume = useEditor((s) => s.ocrResume);
  const stt = useEditor((s) => s.stt);
  const set = useEditor((s) => s.set);
  const [sttFrom, setSttFrom] = useState("auto");
  const [sttTo, setSttTo] = useState("vi");
  const [method, setMethod] = useState<"ocr" | "stt">("ocr");
  const [scanFps, setScanFps] = useState(10);
  const [model, setModel] = useState<OcrModel>("small");
  const [everyFrame, setEveryFrame] = useState(false);
  const [fullFrame, setFullFrame] = useState(false);
  const t = useT();
  const langName = useLangName();
  const txt = (k: "dialog" | "ocr") => {
    const r = regions[k];
    return r ? `${(r.x * 100).toFixed(0)}, ${(r.y * 100).toFixed(0)} · ${(r.w * 100).toFixed(0)}×${(r.h * 100).toFixed(0)}%` : t("insp.regionUnset");
  };
  const pct = ocr.total ? Math.min(100, (ocr.done / ocr.total) * 100) : 0;
  const resume = video && ocrResume?.path === video.path ? ocrResume : null;
  const ocrOpts = { scanFps, model, everyFrame, fullFrame };
  const sttPct = stt.totalS ? Math.min(100, (stt.doneS / stt.totalS) * 100) : 0;
  return (
    <div className="props">
      <PropGroup title={t("insp.subSource")}>
        <div className="seg">
          <button className={method === "ocr" ? "on" : ""} onClick={() => setMethod("ocr")}>{t("insp.methodOcr")}</button>
          <button className={method === "stt" ? "on" : ""} onClick={() => setMethod("stt")}>{t("insp.methodStt")}</button>
        </div>
        {method === "stt" && <p className="muted small">{t("insp.sttHint")}</p>}
      </PropGroup>
      {method === "stt" && (
        <>
          <PropGroup title={t("insp.methodStt")}>
            <Prop label={t("insp.sttFrom")}>
              <select value={sttFrom} onChange={(e) => setSttFrom(e.target.value)} disabled={stt.running}>
                <option value="auto">{t("insp.sttAuto")}</option>
                {STT_LANGUAGES.map((l) => <option key={l} value={l}>{langName(l)}</option>)}
              </select>
            </Prop>
            <Prop label={t("insp.sttTo")}>
              <select value={sttTo} onChange={(e) => setSttTo(e.target.value)} disabled={stt.running}>
                <option value="">{t("insp.sttNoTranslate")}</option>
                {STT_LANGUAGES.map((l) => <option key={l} value={l}>{langName(l)}</option>)}
              </select>
            </Prop>
            <button disabled={!video || stt.running} onClick={extractAudioForStt}>{t("insp.extractAudio")}</button>
          </PropGroup>
          <div className="ocr-run">
            {stt.running ? (
              <>
                <div className="ocr-status">
                  <span>
                    {stt.phase === "audio" ? t("insp.sttAudio")
                      : stt.phase === "connect" ? t("insp.sttConnect")
                      : t("insp.sttProgress", { done: stt.doneS.toFixed(0), total: stt.totalS.toFixed(0) })}
                  </span>
                </div>
                <div className="bar"><div style={{ width: `${sttPct}%` }} /></div>
                {stt.lastText && <p className="ocr-last">{stt.lastText}</p>}
                <button onClick={cancelStt}>{t("insp.cancel")}</button>
              </>
            ) : (
              <button className="primary big" disabled={!video}
                onClick={() => runStt({ sourceLanguage: sttFrom, targetLanguage: sttTo })}>
                {t("insp.runStt")}
              </button>
            )}
          </div>
        </>
      )}
      {method === "ocr" && (
        <>
          <PropGroup title={t("insp.detectRegion")}>
            <Check checked={fullFrame} onChange={setFullFrame}>{t("insp.fullFrame")}</Check>
            {!fullFrame && (
              <>
                <Prop label={t("insp.dialogRegion")}>
                  <button className={selecting === "dialog" ? "pressed" : ""} disabled={!video}
                    onClick={() => set({ selectingRegion: selecting === "dialog" ? null : "dialog" })}>
                    {selecting === "dialog" ? t("insp.selecting") : t("insp.pickOnVideo")}
                  </button>
                </Prop>
                <p className="muted small region-txt">{txt("dialog")}</p>
                <Check checked={!linkOcr} onChange={(v) => set({ linkOcr: !v })}>{t("insp.separateOcr")}</Check>
                {!linkOcr && (
                  <>
                    <Prop label={t("insp.ocrRegion")}>
                      <button className={selecting === "ocr" ? "pressed" : ""} disabled={!video}
                        onClick={() => set({ selectingRegion: selecting === "ocr" ? null : "ocr" })}>
                        {selecting === "ocr" ? t("insp.selecting") : t("insp.pickOnVideo")}
                      </button>
                    </Prop>
                    <p className="muted small region-txt">{txt("ocr")}</p>
                  </>
                )}
              </>
            )}
          </PropGroup>
          <PropGroup title={t("insp.ocrConfig")}>
            <Prop label="Model">
              <select value={model} onChange={(e) => setModel(e.target.value as OcrModel)} disabled={ocr.running}>
                <option value="tiny">{t("insp.modelTiny")}</option>
                <option value="small">{t("insp.modelSmall")}</option>
                <option value="medium">{t("insp.modelMedium")}</option>
              </select>
            </Prop>
            <Prop label={t("insp.scanFps")}>
              <Num value={scanFps} onChange={setScanFps} step={1} min={1} max={30} />
            </Prop>
            <p className="muted small">{t("insp.scanFpsHint", { ms: Math.round(500 / Math.max(1, scanFps)) })}</p>
            <Check checked={everyFrame} onChange={setEveryFrame}>{t("insp.everyFrame")}</Check>
          </PropGroup>
          <div className="ocr-run">
            {ocr.running ? (
              <>
                <div className="ocr-status">
                  <span>
                    {ocr.phase === "download" ? t("insp.downloadingModel", { done: mb(ocr.done), total: mb(ocr.total) })
                      : ocr.phase === "model" ? t("insp.loadingModel")
                      : t("insp.scanning", { pct: pct.toFixed(1), time: formatTime(((video?.duration_ms ?? 0) * pct) / 100), total: formatTime(video?.duration_ms ?? 0) })}
                  </span>
                  {ocr.phase === "ocr" && <span className="muted mono">{t("insp.ocrCalls", { n: ocr.ocrCalls })}</span>}
                </div>
                <div className="bar"><div style={{ width: `${pct}%` }} /></div>
                {ocr.lastText && <p className="ocr-last">{ocr.lastText}</p>}
                <button onClick={cancelOcr}>{t("insp.cancel")}</button>
              </>
            ) : resume ? (
              <>
                <p className="muted small">
                  {t("insp.ocrResumeAt", { time: formatTime(resume.doneMs), pct: Math.round((resume.doneMs / Math.max(1, video!.duration_ms)) * 100) })}
                </p>
                <button className="primary big" onClick={() => runOcr(ocrOpts, true)}>{t("insp.resumeOcr")}</button>
                <button onClick={() => runOcr(ocrOpts)}>{t("insp.restartOcr")}</button>
              </>
            ) : (
              <button className="primary big" disabled={!video} onClick={() => runOcr(ocrOpts)}>
                {t("insp.runOcr")}
              </button>
            )}
          </div>
        </>
      )}
      <TranslatePanel />
    </div>
  );
}

/** Dịch cột gốc đã có (từ OCR, STT không dịch hoặc SRT nhập) sang ngôn ngữ đích. */
function TranslatePanel() {
  const tr = useEditor((s) => s.translate);
  const busy = useEditor((s) => s.stt.running);
  const ocrRunning = useEditor((s) => s.ocr.running);
  const sources = useEditor((s) => s.segments.filter((x) => x.src.trim()).length);
  const [from, setFrom] = useState("auto");
  const [to, setTo] = useState("vi");
  const [onlyEmpty, setOnlyEmpty] = useState(true);
  const t = useT();
  const langName = useLangName();
  return (
    <>
      <PropGroup title={t("insp.trTitle")}>
        <p className="muted small">{t("insp.trHint")}</p>
        <Prop label={t("insp.trFrom")}>
          <select value={from} onChange={(e) => setFrom(e.target.value)} disabled={tr.running}>
            <option value="auto">{t("insp.sttAuto")}</option>
            {STT_LANGUAGES.map((l) => <option key={l} value={l}>{langName(l)}</option>)}
          </select>
        </Prop>
        <Prop label={t("insp.sttTo")}>
          <select value={to} onChange={(e) => setTo(e.target.value)} disabled={tr.running}>
            {STT_LANGUAGES.map((l) => <option key={l} value={l}>{langName(l)}</option>)}
          </select>
        </Prop>
        <Check checked={onlyEmpty} onChange={setOnlyEmpty}>{t("insp.trOnlyEmpty")}</Check>
      </PropGroup>
      <div className="ocr-run">
        {tr.running ? (
          <>
            <div className="ocr-status"><span>{t("insp.trProgress", { done: tr.done, total: tr.total })}</span></div>
            <div className="bar"><div style={{ width: `${tr.total ? (tr.done / tr.total) * 100 : 0}%` }} /></div>
            <button onClick={cancelTranslate}>{t("insp.cancel")}</button>
          </>
        ) : (
          <button className="primary big" disabled={(!sources && !ocrRunning) || busy}
            onClick={() => runTranslate({ source: from, target: to, onlyEmpty })}>
            {t("insp.runTranslate", { n: sources })}
          </button>
        )}
      </div>
    </>
  );
}

const STEPS: TKey[] = ["insp.stepExtract", "insp.stepTranslate", "insp.stepAnalyze", "insp.stepDub", "insp.stepExport"];

function ExportTab() {
  const video = useEditor((s) => s.video);
  const outDir = useEditor((s) => s.outDir);
  const log = useEditor((s) => s.log);
  const [tools, setTools] = useState<ToolStatus | null>(null);
  const [steps, setSteps] = useState(STEPS.map(() => true));
  const [res, setRes] = useState<ExportRes>("source");
  const render = useEditor((s) => s.render);
  const end = useEditor(timelineEnd);
  const [enc, setEnc] = useState("auto");
  const [checkpoint, setCheckpoint] = useState(true);
  const t = useT();
  useEffect(() => {
    api.checkTools().then(setTools).catch(() => {});
  }, []);
  const active = STEPS.filter((_, i) => steps[i]);
  return (
    <div className="props">
      <PropGroup title="Pipeline">
        <ol className="steps">
          {STEPS.map((s, i) => (
            <li key={s} className={steps[i] ? "on" : "off"}>
              <span className="num">{i + 1}</span>
              <span className="grow">{t(s)}</span>
              <input type="checkbox" checked={steps[i]} onChange={(e) => setSteps((x) => x.map((v, j) => (j === i ? e.target.checked : v)))} />
            </li>
          ))}
        </ol>
        <Check checked={checkpoint} onChange={setCheckpoint}>{t("insp.checkpoint")}</Check>
      </PropGroup>
      <PropGroup title={t("insp.outVideo")}>
        <Prop label={t("insp.resolution")}>
          <select value={res} onChange={(e) => setRes(e.target.value as ExportRes)}>
            <option value="source">{t("insp.resSource")}</option><option value="1080">1080p</option><option value="720">720p</option><option value="1080v">{t("insp.resVertical")}</option>
          </select>
        </Prop>
        <Prop label="Encoder">
          <select value={enc} onChange={(e) => setEnc(e.target.value)}>
            <option value="auto">{t("insp.encAuto")}</option><option value="cpu">CPU</option>
            {tools?.encoders.map((e) => <option key={e} value={e}>{e}</option>)}
          </select>
        </Prop>
        <Prop label={t("insp.folder")}>
          <button className="path-btn" onClick={chooseOutDir} title={outDir}>{outDir ? outDir.replace(/^.*[\\/]/, "") : t("insp.choose")}</button>
        </Prop>
        {render.running ? (
          <div className="render-progress">
            <progress max={render.totalMs || 1} value={render.doneMs} />
            <span className="mono small">{Math.round((render.doneMs / (render.totalMs || 1)) * 100)}%</span>
            <button onClick={cancelRender}>{t("insp.cancel")}</button>
          </div>
        ) : (
          <button className="primary" disabled={!end} onClick={() => {
            // Tự động: ưu tiên encoder phần cứng H.264 nếu có, không thì libx264.
            const hw = tools?.encoders.find((e) => e.startsWith("h264_"));
            exportVideo({ res, encoder: enc === "auto" ? hw ?? "libx264" : enc === "cpu" ? "libx264" : enc });
          }}>{t("insp.exportVideo")}</button>
        )}
      </PropGroup>
      <button className="primary big" disabled={!video || !active.length}
        onClick={() => log(t("insp.pipelineLog", { steps: active.map((k) => t(k)).join(" → ") }))}>
        ▶ {t("insp.runSteps", { n: active.length })}
      </button>
    </div>
  );
}
