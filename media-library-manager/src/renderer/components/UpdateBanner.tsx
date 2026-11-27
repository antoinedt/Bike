import { useEffect, useState } from "react";
import type { UpdateStatus } from "../../shared/types.js";

const RESTART_AFTER_S = 10;

/**
 * Shown once a newer build has been downloaded: restarts into it after a short countdown,
 * unless you pick "Later" (then it installs when you close the app).
 */
export default function UpdateBanner() {
  const [status, setStatus] = useState<UpdateStatus | null>(null);
  const [later, setLater] = useState(false);
  const [left, setLeft] = useState(RESTART_AFTER_S);

  useEffect(() => {
    void window.api.getUpdateStatus().then(setStatus);
    return window.api.onUpdateStatus(setStatus);
  }, []);

  const ready = status?.state === "ready" && !later;
  useEffect(() => {
    if (!ready) return;
    setLeft(RESTART_AFTER_S);
    const timer = setInterval(() => {
      setLeft((s) => {
        if (s <= 1) {
          clearInterval(timer);
          void window.api.installUpdateNow();
          return 0;
        }
        return s - 1;
      });
    }, 1000);
    return () => clearInterval(timer);
  }, [ready]);

  if (!status || (!ready && status.state !== "installing")) return null;
  return (
    <div className="update-banner" role="status">
      {status.state === "installing" ? (
        <span>Installing OmniPlay {status.latest}…</span>
      ) : (
        <>
          <span>
            OmniPlay {status.latest} is ready. Restarting to update in {left} s.
          </span>
          <button className="btn btn-primary btn-small" onClick={() => void window.api.installUpdateNow()}>
            Restart now
          </button>
          <button
            className="btn btn-small"
            onClick={() => {
              setLater(true);
              void window.api.installUpdateLater();
            }}
          >
            Later
          </button>
        </>
      )}
    </div>
  );
}
