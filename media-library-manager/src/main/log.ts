import { app } from "electron";
import { appendFileSync, renameSync, rmSync, statSync, writeFileSync } from "node:fs";
import path from "node:path";

/** OmniPlay's own log (search, updates…): <userData>/omniplay.log, e.g. %APPDATA%\OmniPlay\omniplay.log. */
export function logPath(): string {
  return path.join(app.getPath("userData"), "omniplay.log");
}

const MAX_BYTES = 2 * 1024 * 1024;

/** Appends a timestamped line; keeps one previous file (omniplay.log.old) once it passes 2 MB. */
export function log(area: string, message: string): void {
  const file = logPath();
  try {
    if ((statSync(file, { throwIfNoEntry: false })?.size ?? 0) > MAX_BYTES) renameSync(file, `${file}.old`);
  } catch {
    // rotation is best effort
  }
  try {
    appendFileSync(file, `${new Date().toISOString()} [${area}] ${message}\n`);
  } catch {
    // never let logging break the app
  }
}

/** Empties the log (and drops the previous one). */
export function clearLog(): void {
  try {
    writeFileSync(logPath(), "");
    rmSync(`${logPath()}.old`, { force: true });
  } catch {
    // nothing to clear
  }
}
