/** Channel names shared between main, preload, and renderer to avoid typos on either side. */
export const IPC = {
  settingsGet: "settings:get",
  folderPick: "folder:pick",
  folderAdd: "folder:add",
  folderRemove: "folder:remove",
  shareAdd: "share:add",
  shareRemove: "share:remove",
  shareTest: "share:test",
  siteProfileAdd: "siteProfile:add",
  siteProfileUpdate: "siteProfile:update",
  siteProfileRemove: "siteProfile:remove",
  libraryScan: "library:scan",
  libraryGetItems: "library:getItems",
  libraryOpenItem: "library:openItem",
  networkScan: "network:scan",
  searchRun: "search:run",
  torrentDownload: "torrent:download"
} as const;
