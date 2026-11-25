import { useEffect, useState } from "react";
import type { RemoteDeviceInfo } from "../../shared/types.js";

function seen(ms: number): string {
  const minutes = Math.round((Date.now() - ms) / 60000);
  if (minutes < 1) return "just now";
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  return hours < 48 ? `${hours} h ago` : `${Math.round(hours / 24)} days ago`;
}

/** Pick another device (same mailbox share) to start a download on. */
export default function SendToDialog({
  source,
  title,
  onClose,
  onSent
}: {
  source: string;
  title?: string;
  onClose: () => void;
  onSent: (message: string) => void;
}) {
  const [devices, setDevices] = useState<RemoteDeviceInfo[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [sending, setSending] = useState<string | null>(null);

  useEffect(() => {
    window.api
      .getRemoteDevices()
      .then(setDevices)
      .catch((err: Error) => setError(clean(err)));
  }, []);

  async function send(device: RemoteDeviceInfo) {
    setSending(device.id);
    setError(null);
    try {
      const name = await window.api.sendToDevice(device.id, source, title);
      onSent(`Sent "${name}" to ${device.name}. It starts as soon as ${device.name} picks it up${device.online ? "" : " (next time the app is open there)"}.`);
    } catch (err) {
      setError(clean(err as Error));
      setSending(null);
    }
  }

  return (
    <div className="player-overlay" onClick={onClose}>
      <div className="confirm-dialog copy-dialog" onClick={(e) => e.stopPropagation()}>
        <h3>Download on another device</h3>
        {title && <div className="copy-path">{title}</div>}
        {devices === null && !error && <p className="hint">Looking for devices…</p>}
        {devices?.length === 0 && (
          <p className="hint">
            No other devices yet. Open the app on the other device and pick the same network share under Settings → Send to another device.
          </p>
        )}
        {devices && devices.length > 0 && (
          <ul className="copy-list">
            {devices.map((d) => (
              <li key={d.id}>
                <button className="folder-card" disabled={sending !== null} onClick={() => void send(d)}>
                  <span className={d.online ? "device-dot online" : "device-dot"} /> {d.name}
                  <span className="device-seen">
                    {d.platform} · {d.online ? "online" : `seen ${seen(d.lastSeen)}`}
                    {sending === d.id ? " · sending…" : ""}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
        {error && <p className="error">{error}</p>}
        <div className="form-actions">
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
}

function clean(err: Error): string {
  return err.message.replace(/^Error invoking remote method '[^']+': (Error: )?/, "");
}
