import { app } from "electron";
import path from "node:path";

// The app was renamed to OmniPlay. Its data (settings, library, downloads list) stays in the
// folder it always used, so nothing is lost on update. Imported first in index.ts, before any
// store opens that folder.
if (app.isPackaged) {
  app.setPath("userData", path.join(app.getPath("appData"), "Media Library Manager"));
}
