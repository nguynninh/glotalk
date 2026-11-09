import { useEffect, useState } from "react";
import { api, DEFAULT_SERVER, settingsStore, ToolStatus } from "../lib/api";
import { useT } from "../i18n";

export function SettingsTab({ log }: { log: (msg: string) => void }) {
  const [server, setServer] = useState(settingsStore.get("serverUrl", DEFAULT_SERVER));
  const [token, setToken] = useState(settingsStore.get("token"));
  const [tools, setTools] = useState<ToolStatus | null>(null);
  const [testing, setTesting] = useState(false);
  const t = useT();

  useEffect(() => {
    api.checkTools().then(setTools);
  }, []);

  function save() {
    settingsStore.set("serverUrl", server);
    settingsStore.set("token", token);
    log(t("settings.saved", { url: server }));
  }

  async function test() {
    setTesting(true);
    try {
      log(t("settings.testOk", { ms: await api.sttCheck(server) }));
    } catch (e) {
      log(t("settings.testFail", { err: String(e) }));
    } finally {
      setTesting(false);
    }
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
        <button onClick={test} disabled={testing}>
          {testing ? t("settings.testing") : t("settings.test")}
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
