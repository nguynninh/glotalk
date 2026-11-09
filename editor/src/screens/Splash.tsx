import { useEffect } from "react";
import { api } from "../lib/api";
import { useEditor } from "../store/editor";
import { t as tr, useT } from "../i18n";

/** Màn hình chờ lúc mở app: kiểm tra ffmpeg/ffprobe và chạy `work` (khôi phục phiên), hiện tối thiểu một lúc để không chớp tắt. */
export function Splash({ onDone, work }: { onDone: () => void; work: () => Promise<void> }) {
  const t = useT();
  useEffect(() => {
    let alive = true;
    const minShow = new Promise((r) => setTimeout(r, 1200));
    const tools = api
      .checkTools()
      .then((st) => {
        const log = useEditor.getState().log;
        if (!st.ffmpeg) log(tr("settings.missing", { tool: "ffmpeg" }));
        if (!st.ffprobe) log(tr("settings.missing", { tool: "ffprobe" }));
      })
      .catch(() => {}); // ngoài Tauri thì không có lệnh này
    Promise.all([minShow, tools, work().catch(() => {})]).then(() => alive && onDone());
    return () => {
      alive = false;
    };
  }, [onDone, work]);

  return (
    <div className="splash" data-tauri-drag-region>
      <img className="splash-logo" src="/vlive-logo.png" alt="Vlive" draggable={false} />
      <div className="spinner" />
      <div className="splash-text">{t("splash.loading")}</div>
    </div>
  );
}
