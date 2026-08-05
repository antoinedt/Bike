import Store from "electron-store";
import { randomUUID } from "node:crypto";
import type { AppSettings, LocalFolder, NetworkShare, SiteProfile } from "../shared/types.js";

const defaults: AppSettings = {
  localFolders: [],
  networkShares: [],
  siteProfiles: []
};

const store = new Store<AppSettings>({
  name: "media-library.settings",
  defaults
});

export function getSettings(): AppSettings {
  return {
    localFolders: store.get("localFolders"),
    networkShares: store.get("networkShares"),
    siteProfiles: store.get("siteProfiles")
  };
}

export function addLocalFolder(path: string, label: string): LocalFolder {
  const folder: LocalFolder = { id: randomUUID(), path, label: label || path };
  const folders = store.get("localFolders");
  folders.push(folder);
  store.set("localFolders", folders);
  return folder;
}

export function removeLocalFolder(id: string): void {
  store.set(
    "localFolders",
    store.get("localFolders").filter((f) => f.id !== id)
  );
}

export function addNetworkShare(share: Omit<NetworkShare, "id">): NetworkShare {
  const full: NetworkShare = { ...share, id: randomUUID() };
  const shares = store.get("networkShares");
  shares.push(full);
  store.set("networkShares", shares);
  return full;
}

export function removeNetworkShare(id: string): void {
  store.set(
    "networkShares",
    store.get("networkShares").filter((s) => s.id !== id)
  );
}

export function addSiteProfile(profile: Omit<SiteProfile, "id">): SiteProfile {
  const full: SiteProfile = { ...profile, id: randomUUID() };
  const profiles = store.get("siteProfiles");
  profiles.push(full);
  store.set("siteProfiles", profiles);
  return full;
}

export function removeSiteProfile(id: string): void {
  store.set(
    "siteProfiles",
    store.get("siteProfiles").filter((p) => p.id !== id)
  );
}

export function updateSiteProfile(profile: SiteProfile): void {
  const profiles = store.get("siteProfiles").map((p) => (p.id === profile.id ? profile : p));
  store.set("siteProfiles", profiles);
}
