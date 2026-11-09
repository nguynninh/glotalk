import { useEffect, useState } from "react";
import { api, settingsStore, ToolStatus } from "../lib/api";
import { useT } from "../i18n";

export function SettingsTab({ log }: { log: (msg: string) => void }) {
  const [server, setServer] = useState(settingsStore.get("serverUrl", "http://localhost:8080"));
  const [token, setToken] = useState(settingsStore.get("token"));
  const [tools, setTools] = useState<ToolStatus | null>(null);
  const t = useT();

  useEffect(() => {
    api.checkTools().then(setTools);
  }, []);

  function save() {
    settingsStore.set("serverUrl", server);
    settingsStore.set("token", token);
    log(t("settings.saved", { url: server }));
  }

  return (
    <div className="settings">
      <section>
        <h3>{t("settings.server")}</h3>
        <label>{t("settings.serverUrl")}</label>
        <input value={server} onChange={(e) => setServer(e.target.value)} />
        <label>{t("settings.token")}</label>
        <input type="password" value={token} onChange={(e) => setToken(e.target.value)} />
        <button className="primary" onClick={save}>
          {t("settings.save")}
        </button>
      </section>
      <section>
        <h3>{t("settings.tools")}</h3>
        {tools ? (
          <ul className="tools">
            <li className={tools.ffmpeg ? "ok" : "bad"}>{tools.ffmpeg ?? t("settings.missing", { tool: "ffmpeg" })}</li>
            <li className={tools.ffprobe ? "ok" : "bad"}>{tools.ffprobe ?? t("settings.missing", { tool: "ffprobe" })}</li>
            <li>{t("settings.gpu", { list: tools.encoders.length ? tools.encoders.join(", ") : t("settings.noGpu") })}</li>
          </ul>
        ) : (
          <p className="hint">{t("settings.checking")}</p>
        )}
      </section>
    </div>
  );
}
