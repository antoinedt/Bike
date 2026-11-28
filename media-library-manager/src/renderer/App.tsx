import { useCallback, useEffect, useState } from "react";
import type { AppSettings } from "../shared/types.js";
import CastBar from "./components/CastBar.js";
import Sidebar from "./components/Sidebar.js";
import DownloadsPage from "./pages/DownloadsPage.js";
import LibraryPage from "./pages/LibraryPage.js";
import UpdateBanner from "./components/UpdateBanner.js";
import SettingsPage from "./pages/SettingsPage.js";
import TvPage from "./pages/TvPage.js";

export type Tab = "home" | "folders" | "tv" | "downloads" | "settings";

export default function App() {
  const [tab, setTab] = useState<Tab>("home");
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
        <UpdateBanner />
        {!settings ? (
          <div className="empty-state">Loading…</div>
        ) : tab === "home" || tab === "folders" ? (
          <LibraryPage
            settings={settings}
            refreshSettings={refreshSettings}
            view={tab}
            onNavigate={setTab}
          />
        ) : tab === "tv" ? (
          <TvPage />
        ) : tab === "downloads" ? (
          <DownloadsPage onOpenSettings={() => setTab("settings")} remoteEnabled={!!(settings.remoteShareId || settings.remoteLocalFolder)} />
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
