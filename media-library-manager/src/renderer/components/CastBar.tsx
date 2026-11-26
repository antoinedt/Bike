import { IconButton } from "./Icon.js";
import { useEffect, useState } from "react";
import type { CastState } from "../../shared/types.js";

/** 0:05, 12:34, 1:02:03 */
function clock(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  const h = Math.floor(s / 3600);
  const mm = String(Math.floor((s % 3600) / 60));
  const ss = String(s % 60).padStart(2, "0");
  return h ? `${h}:${mm.padStart(2, "0")}:${ss}` : `${mm}:${ss}`;
}

/** Remote control for whatever is being cast, along the bottom of the window. */
export default function CastBar() {
  const [state, setState] = useState<CastState | null>(null);
  const [dragging, setDragging] = useState<number | null>(null);

  useEffect(() => {
    void window.api.getCastState().then(setState);
    return window.api.onCastState(setState);
  }, []);

  if (!state) return null;
  const busy = state.state === "loading" || state.state === "buffering";
  const position = dragging ?? state.position;

  return (
    <div className={`cast-bar cast-${state.state}`}>
      <div className="cast-info">
        <div className="cast-title">{state.itemName}</div>
        <div className="cast-sub">
          {state.state === "error"
            ? state.error
            : `${
                { loading: "Starting on", stopped: "Finished on", paused: "Paused on", buffering: "Loading on" }[state.state as string] ?? "Playing on"
              } ${state.deviceName}`}
        </div>
      </div>
      {state.state !== "error" && state.state !== "stopped" && (
        <>
          <IconButton icon="replay" badge="30" label="Back 30 seconds" className="btn-small" disabled={busy} onClick={() => void window.api.castControl("seek", Math.max(0, state.position - 30))} />
          <IconButton
            icon={state.state === "paused" ? "play" : "pause"}
            label={state.state === "paused" ? "Play" : "Pause"}
            className="btn-small btn-primary"
            disabled={state.state === "loading"}
            onClick={() => void window.api.castControl(state.state === "paused" ? "play" : "pause")}
          />
          <IconButton icon="forward" badge="30" label="Forward 30 seconds" className="btn-small" disabled={busy} onClick={() => void window.api.castControl("seek", state.position + 30)} />
          <span className="cast-time">{clock(position)}</span>
          <input
            className="cast-seek"
            type="range"
            min={0}
            max={Math.max(1, Math.round(state.duration))}
            value={Math.round(position)}
            disabled={!state.duration}
            onChange={(e) => setDragging(Number(e.target.value))}
            onMouseUp={() => {
              if (dragging !== null) void window.api.castControl("seek", dragging);
              setDragging(null);
            }}
          />
          <span className="cast-time">{state.duration ? clock(state.duration) : "--:--"}</span>
        </>
      )}
      <IconButton
        icon={state.state === "error" || state.state === "stopped" ? "close" : "stop"}
        label={state.state === "error" || state.state === "stopped" ? "Close" : "Stop"}
        className="btn-small btn-danger"
        onClick={() => void window.api.stopCasting()}
      />
    </div>
  );
}
