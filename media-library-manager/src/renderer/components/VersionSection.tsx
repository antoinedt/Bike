import { useEffect, useState } from "react";
import type { UpdateStatus } from "../../shared/types.js";

function describe(s: UpdateStatus): string {
  switch (s.state) {
    case "checking":
      return "Checking for updates…";
    case "up-to-date":
      return "You have the latest version.";
    case "downloading":
      return `Downloading ${s.latest}… ${Math.round((s.progress ?? 0) * 100)}%`;
    case "ready":
      return `${s.latest} is downloaded — it installs when you restart or close the app.`;
    case "installing":
      return `Installing ${s.latest}…`;
    case "error":
      return `Couldn't check for updates: ${s.error}`;
    default:
      return "";
  }
}

/** Top of Settings: the version, update status and "Check for updates". */
export default function VersionSection() {
  const [status, setStatus] = useState<UpdateStatus | null>(null);
  useEffect(() => {
    void window.api.getUpdateStatus().then(setStatus);
    return window.api.onUpdateStatus(setStatus);
  }, []);
  if (!status) return null;
  const busy = status.state === "checking" || status.state === "downloading" || status.state === "installing";
  return (
    <div className="version-line">
      <span className="hint-inline">OmniPlay version {status.current}</span>
      <span className="hint-inline">{describe(status)}</span>
      {status.state === "ready" ? (
        <button className="btn btn-small btn-primary" onClick={() => void window.api.installUpdateNow()}>
          Restart to update
        </button>
      ) : (
        <button className="btn btn-small" onClick={() => void window.api.checkForUpdate()} disabled={busy}>
          Check for updates
        </button>
      )}
    </div>
  );
}
