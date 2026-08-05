import { useState } from "react";
import type { AppSettings, NetworkShare, SiteProfile } from "../../shared/types.js";

const EMPTY_SHARE = { label: "", host: "", share: "", subPath: "", domain: "", username: "", password: "" };
const EMPTY_PROFILE = {
  label: "",
  searchUrlTemplate: "",
  resultItemSelector: "",
  titleSelector: "",
  linkSelector: "",
  sizeSelector: "",
  seedersSelector: "",
  detailPageLinkSelector: ""
};

export default function SettingsPage({
  settings,
  refreshSettings
}: {
  settings: AppSettings;
  refreshSettings: () => Promise<void>;
}) {
  return (
    <div className="page">
      <div className="page-header">
        <h1>Settings</h1>
      </div>
      <FoldersSection settings={settings} refreshSettings={refreshSettings} />
      <SharesSection settings={settings} refreshSettings={refreshSettings} />
      <ProfilesSection settings={settings} refreshSettings={refreshSettings} />
    </div>
  );
}

function FoldersSection({
  settings,
  refreshSettings
}: {
  settings: AppSettings;
  refreshSettings: () => Promise<void>;
}) {
  async function addFolder() {
    const path = await window.api.pickFolder();
    if (!path) return;
    const label = path.split(/[\\/]/).filter(Boolean).pop() ?? path;
    await window.api.addFolder(path, label);
    await refreshSettings();
  }

  async function remove(id: string) {
    await window.api.removeFolder(id);
    await refreshSettings();
  }

  return (
    <section className="settings-section">
      <h2>Local folders</h2>
      <p className="hint">Folders on this device to scan for media.</p>
      <ul className="settings-list">
        {settings.localFolders.map((f) => (
          <li key={f.id}>
            <span className="settings-item-label">{f.label}</span>
            <span className="settings-item-sub">{f.path}</span>
            <button className="btn btn-small btn-danger" onClick={() => void remove(f.id)}>
              Remove
            </button>
          </li>
        ))}
      </ul>
      <button className="btn" onClick={() => void addFolder()}>
        Add folder…
      </button>
    </section>
  );
}

function SharesSection({
  settings,
  refreshSettings
}: {
  settings: AppSettings;
  refreshSettings: () => Promise<void>;
}) {
  const [form, setForm] = useState({ ...EMPTY_SHARE });
  const [testResult, setTestResult] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function set<K extends keyof typeof form>(key: K, value: string) {
    setForm((prev) => ({ ...prev, [key]: value }));
  }

  async function add() {
    if (!form.host || !form.share) return;
    setBusy(true);
    try {
      await window.api.addShare(form);
      setForm({ ...EMPTY_SHARE });
      await refreshSettings();
    } finally {
      setBusy(false);
    }
  }

  async function remove(id: string) {
    await window.api.removeShare(id);
    await refreshSettings();
  }

  async function test(share: NetworkShare) {
    setTestResult("Testing…");
    const result = await window.api.testShare(share);
    setTestResult(result.ok ? "Connection OK" : `Failed: ${result.error}`);
  }

  return (
    <section className="settings-section">
      <h2>Local network (SMB shares)</h2>
      <p className="hint">Windows/SMB shares to browse, e.g. a NAS. Credentials are stored locally, unencrypted.</p>
      <ul className="settings-list">
        {settings.networkShares.map((s) => (
          <li key={s.id}>
            <span className="settings-item-label">{s.label || `${s.host}/${s.share}`}</span>
            <span className="settings-item-sub">
              \\{s.host}\{s.share}
              {s.subPath ? `\\${s.subPath}` : ""}
            </span>
            <button className="btn btn-small" onClick={() => void test(s)}>
              Test
            </button>
            <button className="btn btn-small btn-danger" onClick={() => void remove(s.id)}>
              Remove
            </button>
          </li>
        ))}
      </ul>
      {testResult && <p className="hint-inline">{testResult}</p>}
      <div className="form-grid">
        <input className="text-input" placeholder="Label" value={form.label} onChange={(e) => set("label", e.target.value)} />
        <input className="text-input" placeholder="Host / IP" value={form.host} onChange={(e) => set("host", e.target.value)} />
        <input className="text-input" placeholder="Share name" value={form.share} onChange={(e) => set("share", e.target.value)} />
        <input className="text-input" placeholder="Sub-path (optional)" value={form.subPath} onChange={(e) => set("subPath", e.target.value)} />
        <input className="text-input" placeholder="Domain (optional)" value={form.domain} onChange={(e) => set("domain", e.target.value)} />
        <input className="text-input" placeholder="Username" value={form.username} onChange={(e) => set("username", e.target.value)} />
        <input
          className="text-input"
          type="password"
          placeholder="Password"
          value={form.password}
          onChange={(e) => set("password", e.target.value)}
        />
      </div>
      <button className="btn" onClick={() => void add()} disabled={busy || !form.host || !form.share}>
        Add share
      </button>
    </section>
  );
}

function ProfilesSection({
  settings,
  refreshSettings
}: {
  settings: AppSettings;
  refreshSettings: () => Promise<void>;
}) {
  const [form, setForm] = useState({ ...EMPTY_PROFILE });
  const [busy, setBusy] = useState(false);

  function set<K extends keyof typeof form>(key: K, value: string) {
    setForm((prev) => ({ ...prev, [key]: value }));
  }

  async function add() {
    if (!form.label || !form.searchUrlTemplate || !form.resultItemSelector) return;
    setBusy(true);
    try {
      await window.api.addSiteProfile(form as Omit<SiteProfile, "id">);
      setForm({ ...EMPTY_PROFILE });
      await refreshSettings();
    } finally {
      setBusy(false);
    }
  }

  async function remove(id: string) {
    await window.api.removeSiteProfile(id);
    await refreshSettings();
  }

  return (
    <section className="settings-section">
      <h2>Search sites</h2>
      <p className="hint">
        Configure a site you have the legal right to search. The search page issues a GET to the URL
        template below (use <code>{"{query}"}</code> as the search-term placeholder) and parses the
        results HTML with the CSS selectors you provide. Only add sites you're authorized to use.
      </p>
      <ul className="settings-list">
        {settings.siteProfiles.map((p) => (
          <li key={p.id}>
            <span className="settings-item-label">{p.label}</span>
            <span className="settings-item-sub">{p.searchUrlTemplate}</span>
            <button className="btn btn-small btn-danger" onClick={() => void remove(p.id)}>
              Remove
            </button>
          </li>
        ))}
      </ul>
      <div className="form-grid">
        <input className="text-input" placeholder="Label" value={form.label} onChange={(e) => set("label", e.target.value)} />
        <input
          className="text-input wide"
          placeholder="Search URL template, e.g. https://example.com/search?q={query}"
          value={form.searchUrlTemplate}
          onChange={(e) => set("searchUrlTemplate", e.target.value)}
        />
        <input
          className="text-input"
          placeholder="Result item selector, e.g. table.results tr"
          value={form.resultItemSelector}
          onChange={(e) => set("resultItemSelector", e.target.value)}
        />
        <input
          className="text-input"
          placeholder="Title selector, e.g. .title a"
          value={form.titleSelector}
          onChange={(e) => set("titleSelector", e.target.value)}
        />
        <input
          className="text-input"
          placeholder="Link selector, e.g. a.magnet"
          value={form.linkSelector}
          onChange={(e) => set("linkSelector", e.target.value)}
        />
        <input
          className="text-input"
          placeholder="Size selector (optional)"
          value={form.sizeSelector}
          onChange={(e) => set("sizeSelector", e.target.value)}
        />
        <input
          className="text-input"
          placeholder="Seeders selector (optional)"
          value={form.seedersSelector}
          onChange={(e) => set("seedersSelector", e.target.value)}
        />
        <input
          className="text-input wide"
          placeholder="Detail-page link selector (optional, if the link goes to a detail page first)"
          value={form.detailPageLinkSelector}
          onChange={(e) => set("detailPageLinkSelector", e.target.value)}
        />
      </div>
      <button
        className="btn"
        onClick={() => void add()}
        disabled={busy || !form.label || !form.searchUrlTemplate || !form.resultItemSelector}
      >
        Add site
      </button>
    </section>
  );
}
