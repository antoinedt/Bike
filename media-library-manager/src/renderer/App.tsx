import { useCallback, useEffect, useState } from "react";
import type { AppSettings } from "../shared/types.js";
import Sidebar from "./components/Sidebar.js";
import LibraryPage from "./pages/LibraryPage.js";
import NetworkPage from "./pages/NetworkPage.js";
import SearchPage from "./pages/SearchPage.js";
import SettingsPage from "./pages/SettingsPage.js";

export type Tab = "library" | "network" | "search" | "settings";

export default function App() {
  const [tab, setTab] = useState<Tab>("library");
  const [settings, setSettings] = useState<AppSettings | null>(null);

  const refreshSettings = useCallback(async () => {
    setSettings(await window.api.getSettings());
  }, []);

  useEffect(() => {
    void refreshSettings();
  }, [refreshSettings]);

  return (
    <div className="app-shell">
      <Sidebar active={tab} onSelect={setTab} />
      <main className="app-content">
        {!settings ? (
          <div className="empty-state">Loading…</div>
        ) : tab === "library" ? (
          <LibraryPage settings={settings} />
        ) : tab === "network" ? (
          <NetworkPage settings={settings} />
        ) : tab === "search" ? (
          <SearchPage settings={settings} />
        ) : (
          <SettingsPage settings={settings} refreshSettings={refreshSettings} />
        )}
      </main>
    </div>
  );
}
