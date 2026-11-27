import { useCallback, useEffect, useState } from "react";
import type { AppSettings } from "../shared/types.js";
import CastBar from "./components/CastBar.js";
import Sidebar from "./components/Sidebar.js";
import DownloadsPage from "./pages/DownloadsPage.js";
import LibraryPage from "./pages/LibraryPage.js";
import SettingsPage from "./pages/SettingsPage.js";
import TvPage from "./pages/TvPage.js";

export type Tab = "library" | "tv" | "downloads" | "settings";

export default function App() {
  const [tab, setTab] = useState<Tab>("library");
  const [settings, setSettings] = useState<AppSettings | null>(null);

  const refreshSettings = useCallback(async () => {
    setSettings(await window.api.getSettings());
  }, []);

  useEffect(() => {
    void refreshSettings();
  }, [refreshSettings]);

  // "Started X on TV" / "Started X, sent from Phone": shown wherever you are in the app.
  const [toast, setToast] = useState<string | null>(null);
  useEffect(
    () =>
      window.api.onRemoteEvent((message) => {
        setToast(message);
        window.setTimeout(() => setToast((current) => (current === message ? null : current)), 8000);
      }),
    []
  );

  return (
    <div className="app-shell">
      <Sidebar active={tab} onSelect={setTab} />
      <main className="app-content">
        {!settings ? (
          <div className="empty-state">Loading…</div>
        ) : tab === "library" ? (
          <LibraryPage settings={settings} onOpenDownloads={() => setTab("downloads")} />
        ) : tab === "tv" ? (
          <TvPage />
        ) : tab === "downloads" ? (
          <DownloadsPage onOpenSettings={() => setTab("settings")} remoteEnabled={!!settings.remoteShareId} />
        ) : (
          <SettingsPage settings={settings} refreshSettings={refreshSettings} />
        )}
      </main>
      <CastBar />
      {toast && (
        <div className="toast" onClick={() => setToast(null)}>
          {toast}
        </div>
      )}
    </div>
  );
}
