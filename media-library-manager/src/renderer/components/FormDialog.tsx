import { useEffect, type ReactNode } from "react";

/** A popup form (Add / Edit share, Add / Edit search site). Escape or clicking outside closes it. */
export default function FormDialog({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);
  return (
    <div className="player-overlay" onClick={onClose}>
      <div className="details-panel form-dialog" onClick={(e) => e.stopPropagation()} role="dialog" aria-label={title}>
        <div className="details-header">
          <span className="player-title">{title}</span>
        </div>
        {children}
      </div>
    </div>
  );
}
