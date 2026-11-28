import { IconButton } from "../components/Icon.js";
import { useEffect, useState } from "react";
import ArtworkFetchSection from "../components/ArtworkFetchSection.js";
import FormDialog from "../components/FormDialog.js";
import { pathForFolderKey } from "../lib/folderTree.js";
import VersionSection from "../components/VersionSection.js";
import type { AppSettings, LocalFolder, MediaItem, NetworkShare, SiteProfile } from "../../shared/types.js";

const EMPTY_SHARE = { label: "", host: "", share: "", subPath: "", domain: "", username: "", password: "" };
const EMPTY_PROFILE = {
  label: "",
  searchUrlTemplate: "",
  resultItemSelector: "",
  titleSelector: "",
  linkSelector: "",
  sizeSelector: "",
  seedersSelector: "",
  detailPageLinkSelector: "",
  useHeadlessBrowser: false
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
      <VersionSection />
      <FoldersSection settings={settings} refreshSettings={refreshSettings} />
      <SharesSection settings={settings} refreshSettings={refreshSettings} />
      <ExcludedSection settings={settings} refreshSettings={refreshSettings} />
      <TmdbSection settings={settings} refreshSettings={refreshSettings} />
      <ArtworkFetchSection settings={settings} />
      <RemoteSection settings={settings} refreshSettings={refreshSettings} />
      <DownloadsSection settings={settings} refreshSettings={refreshSettings} />
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
  const [editingId, setEditingId] = useState<string | null>(null);
  const [label, setLabel] = useState("");

  async function addFolder() {
    const path = await window.api.pickFolder();
    if (!path) return;
    const folderLabel = path.split(/[\\/]/).filter(Boolean).pop() ?? path;
    await window.api.addFolder(path, folderLabel);
    await refreshSettings();
  }

  async function remove(id: string) {
    if (id === editingId) cancelEdit();
    await window.api.removeFolder(id);
    await refreshSettings();
  }

  function startEdit(folder: LocalFolder) {
    setEditingId(folder.id);
    setLabel(folder.label);
  }

  function cancelEdit() {
    setEditingId(null);
    setLabel("");
  }

  async function saveLabel(folder: LocalFolder) {
    await window.api.updateFolder({ ...folder, label });
    cancelEdit();
    await refreshSettings();
  }

  async function changeFolder(folder: LocalFolder) {
    const path = await window.api.pickFolder();
    if (!path) return;
    await window.api.updateFolder({ ...folder, path });
    await refreshSettings();
  }

  return (
    <section className="settings-section">
      <h2>Local folders</h2>
      <p className="hint">Folders on this device to scan for media.</p>
      <ul className="settings-list">
        {settings.localFolders.map((f) =>
          editingId === f.id ? (
            <li key={f.id}>
              <input className="text-input" value={label} onChange={(e) => setLabel(e.target.value)} />
              <span className="settings-item-sub">{f.path}</span>
              <button className="btn btn-small" onClick={() => void changeFolder(f)}>
                Change folder…
              </button>
              <button className="btn btn-small btn-primary" onClick={() => void saveLabel(f)}>
                Save
              </button>
              <button className="btn btn-small" onClick={cancelEdit}>
                Cancel
              </button>
            </li>
          ) : (
            <li key={f.id}>
              <span className="settings-item-label">{f.label}</span>
              <span className="settings-item-sub">{f.path}</span>
              <IconButton icon="edit" label="Edit" className="btn-small" onClick={() => startEdit(f)} />
              <IconButton icon="delete" label="Remove" className="btn-small btn-danger" onClick={() => void remove(f.id)} />
            </li>
          )
        )}
      </ul>
      <div className="form-actions">
        <button className="btn" onClick={() => void addFolder()}>
          Add folder…
        </button>
        <ScanButton label="Scan folders" disabled={settings.localFolders.length === 0} scan={() => window.api.scanLibrary()} source="local" />
      </div>
    </section>
  );
}

function TmdbSection({
  settings,
  refreshSettings
}: {
  settings: AppSettings;
  refreshSettings: () => Promise<void>;
}) {
  const [key, setKey] = useState(settings.tmdbApiKey);
  const [saved, setSaved] = useState(false);

  async function save() {
    await window.api.updateTmdbApiKey(key);
    setSaved(true);
    await refreshSettings();
  }

  return (
    <section className="settings-section">
      <h2>Movie &amp; TV artwork (TMDb)</h2>
      <p className="hint">
        When a video item isn't found on iTunes, the app falls back to{" "}
        <a href="https://www.themoviedb.org/" target="_blank" rel="noreferrer">
          TMDb (The Movie Database)
        </a>{" "}
        for its poster and release year — a free, official API. Get a free API key at{" "}
        <a href="https://www.themoviedb.org/settings/api" target="_blank" rel="noreferrer">
          themoviedb.org/settings/api
        </a>{" "}
        and paste it below. Leave this empty to skip that fallback. (Music/audio artwork uses
        MusicBrainz instead, which needs no key.)
      </p>
      <div className="form-grid">
        <input
          className="text-input wide"
          placeholder="TMDb API key"
          value={key}
          onChange={(e) => {
            setKey(e.target.value);
            setSaved(false);
          }}
        />
      </div>
      <div className="form-actions">
        <button className="btn" onClick={() => void save()} disabled={key === settings.tmdbApiKey}>
          Save
        </button>
        {saved && <span className="hint-inline">Saved.</span>}
      </div>
    </section>
  );
}

function RemoteSection({
  settings,
  refreshSettings
}: {
  settings: AppSettings;
  refreshSettings: () => Promise<void>;
}) {
  const [name, setName] = useState(settings.deviceName);
  const LOCAL = "__local__";
  const [shareId, setShareId] = useState(settings.remoteLocalFolder ? LOCAL : settings.remoteShareId);
  const [localFolder, setLocalFolder] = useState(settings.remoteLocalFolder);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function save() {
    setError(null);
    try {
      if (shareId === LOCAL && !localFolder) throw new Error("Choose the folder first.");
      await window.api.updateRemoteSettings(name, shareId === LOCAL ? "" : shareId, shareId === LOCAL ? localFolder : "");
      await refreshSettings();
      setSaved(true);
    } catch (err) {
      setError((err as Error).message.replace(/^Error invoking remote method '[^']+': (Error: )?/, ""));
    }
  }

  return (
    <section className="settings-section">
      <h2>Send to another device</h2>
      <p className="hint">
        Start a download on another computer, phone or TV running this app (“Send to…” on search results and the
        Downloads page), and receive ones sent here. Devices find each other through a hidden folder that they all reach:
        a network share, or a folder on this PC that it shares on the network (the phone adds that folder as a network
        share). Downloads sent here start while the app is open.
      </p>
      <div className="form-grid">
        <input
          className="text-input"
          placeholder="This computer's name"
          value={name}
          onChange={(e) => {
            setName(e.target.value);
            setSaved(false);
          }}
        />
        <select
          className="text-input"
          value={shareId}
          onChange={(e) => {
            setShareId(e.target.value);
            setSaved(false);
          }}
        >
          <option value="">Off</option>
          <option value={LOCAL}>Use a folder on this PC</option>
          {settings.networkShares.map((s) => (
            <option key={s.id} value={s.id}>
              Use share: {s.label}
            </option>
          ))}
        </select>
      </div>
      {shareId === LOCAL && (
        <div className="form-row folder-choice">
          <span className="folder-choice-path">📁 {localFolder || "No folder chosen"}</span>
          <button
            className="btn"
            onClick={async () => {
              const picked = await window.api.pickFolder();
              if (picked) {
                setLocalFolder(picked);
                setSaved(false);
              }
            }}
          >
            Choose…
          </button>
        </div>
      )}
      {shareId === LOCAL && (
        <p className="hint">
          Pick the folder you share with the phone — the same folder its network share opens (including any sub-path).
        </p>
      )}
      <div className="form-actions">
        <button className="btn" onClick={() => void save()} disabled={
            name === settings.deviceName &&
            (shareId === LOCAL ? localFolder === settings.remoteLocalFolder : shareId === settings.remoteShareId && !settings.remoteLocalFolder)
          }>
          Save
        </button>
        {saved && <span className="hint-inline">Saved.</span>}
        {error && <span className="error">{error}</span>}
      </div>
    </section>
  );
}

function DownloadsSection({
  settings,
  refreshSettings
}: {
  settings: AppSettings;
  refreshSettings: () => Promise<void>;
}) {
  const [folder, setFolder] = useState("");

  useEffect(() => {
    void window.api.getTorrentDownloadFolder().then(setFolder);
  }, [settings.torrentDownloadFolder]);

  async function change(next: string) {
    await window.api.updateTorrentDownloadFolder(next);
    await refreshSettings();
  }

  async function pick() {
    const picked = await window.api.pickFolder();
    if (picked) await change(picked);
  }

  return (
    <section className="settings-section">
      <h2>Torrent downloads</h2>
      <p className="hint">
        Where the Downloads page saves files. Pick one of your Library folders (or a folder inside
        one) and finished downloads show up in the Library by themselves.
      </p>
      <ul className="settings-list">
        <li>
          <span className="settings-item-label">Save to</span>
          <span className="settings-item-sub" title={folder}>
            {folder}
            {!settings.torrentDownloadFolder && " (default)"}
          </span>
          <button className="btn btn-small" onClick={() => void pick()}>
            Change…
          </button>
          {settings.torrentDownloadFolder && (
            <button className="btn btn-small" onClick={() => void change("")}>
              Use default
            </button>
          )}
        </li>
      </ul>
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
  const [editingId, setEditingId] = useState<string | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [testResult, setTestResult] = useState<string | null>(null);
  const [formTest, setFormTest] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function set<K extends keyof typeof form>(key: K, value: string) {
    setForm((prev) => ({ ...prev, [key]: value }));
  }

  function edit(share: NetworkShare) {
    const { id, ...rest } = share;
    setForm(rest);
    setEditingId(id);
    setFormTest(null);
    setDialogOpen(true);
  }

  function add() {
    setForm({ ...EMPTY_SHARE });
    setEditingId(null);
    setFormTest(null);
    setDialogOpen(true);
  }

  function cancelEdit() {
    setForm({ ...EMPTY_SHARE });
    setEditingId(null);
    setDialogOpen(false);
  }

  async function testForm() {
    setFormTest("Testing…");
    const result = await window.api.testShare({ ...form, id: editingId ?? "new" });
    setFormTest(result.ok ? "Connection OK" : `Failed: ${result.error}`);
  }

  async function save() {
    if (!form.host || !form.share) return;
    setBusy(true);
    try {
      if (editingId) {
        await window.api.updateShare({ ...form, id: editingId });
      } else {
        await window.api.addShare(form);
      }
      setForm({ ...EMPTY_SHARE });
      setEditingId(null);
      setDialogOpen(false);
      await refreshSettings();
    } finally {
      setBusy(false);
    }
  }

  async function remove(id: string) {
    if (id === editingId) cancelEdit();
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
            <IconButton icon="edit" label="Edit" className="btn-small" onClick={() => edit(s)} />
            <IconButton icon="delete" label="Remove" className="btn-small btn-danger" onClick={() => void remove(s.id)} />
          </li>
        ))}
      </ul>
      {testResult && <p className="hint-inline">{testResult}</p>}
      <div className="form-actions">
        <button className="btn" onClick={add}>
          Add share…
        </button>
        {settings.networkShares.length > 0 && (
          <ScanButton label="Scan shares" scan={() => window.api.scanNetwork()} source="network" />
        )}
      </div>
      {dialogOpen && (
      <FormDialog title={editingId ? `Edit “${form.label || form.host}”` : "Add a network share"} onClose={cancelEdit}>
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
      {formTest && <p className="hint-inline">{formTest}</p>}
      <div className="form-actions">
        <button className="btn btn-primary" onClick={() => void save()} disabled={busy || !form.host || !form.share}>
          {editingId ? "Save changes" : "Add share"}
        </button>
        <button className="btn" onClick={() => void testForm()} disabled={!form.host || !form.share}>
          Test
        </button>
        <button className="btn" onClick={cancelEdit}>
          Cancel
        </button>
      </div>
      </FormDialog>
      )}
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
  const [editingId, setEditingId] = useState<string | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [busy, setBusy] = useState(false);

  function set<K extends keyof typeof form>(key: K, value: (typeof form)[K]) {
    setForm((prev) => ({ ...prev, [key]: value }));
  }

  function edit(profile: SiteProfile) {
    const { id, ...rest } = profile;
    setForm(rest);
    setEditingId(id);
    setDialogOpen(true);
  }

  function add() {
    setForm({ ...EMPTY_PROFILE });
    setEditingId(null);
    setDialogOpen(true);
  }

  function cancelEdit() {
    setForm({ ...EMPTY_PROFILE });
    setEditingId(null);
    setDialogOpen(false);
  }

  async function save() {
    if (!form.label || !form.searchUrlTemplate || !form.resultItemSelector) return;
    setBusy(true);
    try {
      if (editingId) {
        await window.api.updateSiteProfile({ ...form, id: editingId });
      } else {
        await window.api.addSiteProfile(form as Omit<SiteProfile, "id">);
      }
      setForm({ ...EMPTY_PROFILE });
      setEditingId(null);
      setDialogOpen(false);
      await refreshSettings();
    } finally {
      setBusy(false);
    }
  }

  async function remove(id: string) {
    if (id === editingId) cancelEdit();
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
            <span className="settings-item-label">
              {p.label}
              {p.useHeadlessBrowser && <span className="hint-inline"> · JS-rendered</span>}
            </span>
            <span className="settings-item-sub">{p.searchUrlTemplate}</span>
            <IconButton icon="edit" label="Edit" className="btn-small" onClick={() => edit(p)} />
            <IconButton icon="delete" label="Remove" className="btn-small btn-danger" onClick={() => void remove(p.id)} />
          </li>
        ))}
      </ul>
      <div className="form-actions">
        <button className="btn" onClick={add}>
          Add site…
        </button>
      </div>
      {dialogOpen && (
      <FormDialog title={editingId ? `Edit “${form.label}”` : "Add a search site"} onClose={cancelEdit}>
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
      <label className="profile-toggle" style={{ marginBottom: 12 }}>
        <input
          type="checkbox"
          checked={form.useHeadlessBrowser}
          onChange={(e) => set("useHeadlessBrowser", e.target.checked)}
        />
        Site renders results with JavaScript (use a hidden browser window to fetch it — slower,
        but works when "View Page Source" doesn't show the results)
      </label>
      <div className="form-actions">
        <button
          className="btn btn-primary"
          onClick={() => void save()}
          disabled={busy || !form.label || !form.searchUrlTemplate || !form.resultItemSelector}
        >
          {editingId ? "Save changes" : "Add site"}
        </button>
        <button className="btn" onClick={cancelEdit}>
          Cancel
        </button>
      </div>
      </FormDialog>
      )}
    </section>
  );
}

/** Scans folders or shares for media (moved here from the Library page), and says how many were found. */
function ScanButton({ label, scan, source, disabled }: { label: string; scan: () => Promise<MediaItem[]>; source: "local" | "network"; disabled?: boolean }) {
  const [scanning, setScanning] = useState(false);
  const [result, setResult] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  async function run() {
    setScanning(true);
    setResult(null);
    try {
      const items = await scan();
      const count = items.filter((i) => i.source.type === source).length;
      setFailed(false);
      setResult(`Found ${count} item${count === 1 ? "" : "s"}. They're in the Library.`);
    } catch (err) {
      setFailed(true);
      setResult((err as Error).message);
    } finally {
      setScanning(false);
    }
  }
  return (
    <>
      <button className="btn btn-primary" onClick={() => void run()} disabled={scanning || disabled}>
        {scanning ? "Scanning…" : label}
      </button>
      {result && <span className={failed ? "error" : "notice"}>{result}</span>}
    </>
  );
}

/** Folders left out of scans: list them, include one again, or show them in the Folders view. */
function ExcludedSection({ settings, refreshSettings }: { settings: AppSettings; refreshSettings: () => Promise<void> }) {
  const excluded = settings.excludedFolders ?? [];
  return (
    <section className="settings-section">
      <h2>Excluded folders</h2>
      <p className="hint">
        In the Library's Folders view, open a folder and tick "Exclude this folder" to leave it (and everything in
        it) out of the library.
      </p>
      <label className="checkbox-label">
        <input
          type="checkbox"
          checked={settings.showExcludedFolders}
          onChange={async (e) => {
            await window.api.setShowExcludedFolders(e.target.checked);
            await refreshSettings();
          }}
        />
        Show excluded folders in the Folders view (greyed out), so they can be included again
      </label>
      {excluded.length > 0 && (
        <ul className="settings-list">
          {excluded.map((key) => (
            <li key={key}>
              <span className="settings-item-label">🚫 {(pathForFolderKey(key, settings) ?? [key]).join(" / ")}</span>
              <button
                className="btn btn-small"
                onClick={async () => {
                  await window.api.setFolderExcluded(key, false);
                  await refreshSettings();
                }}
              >
                Include again
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
