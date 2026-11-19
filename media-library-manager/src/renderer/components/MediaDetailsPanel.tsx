import { useState } from "react";
import type { MediaItem } from "../../shared/types.js";
import { KIND_ICON, formatDuration, formatSize } from "../lib/format.js";

export default function MediaDetailsPanel({
  item,
  allTags,
  onOpen,
  onDownload,
  onClose,
  onItemUpdated
}: {
  item: MediaItem;
  allTags: string[];
  onOpen: () => void;
  onDownload?: () => void;
  onClose: () => void;
  onItemUpdated: (item: MediaItem) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [title, setTitle] = useState(item.title ?? "");
  const [year, setYear] = useState(item.year ?? "");
  const [tags, setTags] = useState<string[]>(item.tags ?? []);
  const [tagInput, setTagInput] = useState("");
  const [saving, setSaving] = useState(false);

  function startEdit() {
    setTitle(item.title ?? "");
    setYear(item.year ?? "");
    setTags(item.tags ?? []);
    setTagInput("");
    setEditing(true);
  }

  function addTag(raw: string) {
    const tag = raw.trim();
    if (!tag || tags.includes(tag)) {
      setTagInput("");
      return;
    }
    setTags([...tags, tag]);
    setTagInput("");
  }

  function removeTag(tag: string) {
    setTags(tags.filter((t) => t !== tag));
  }

  async function save() {
    setSaving(true);
    try {
      const updated = await window.api.updateItemMetadata(item.id, { title, year, tags });
      const fresh = updated.find((i) => i.id === item.id);
      if (fresh) onItemUpdated(fresh);
      setEditing(false);
    } finally {
      setSaving(false);
    }
  }

  const suggestions = allTags.filter((t) => !tags.includes(t));

  return (
    <div className="player-overlay" onClick={onClose}>
      <div className="details-panel" onClick={(e) => e.stopPropagation()}>
        <div className="details-header">
          <span className="player-title">{item.title || item.name}</span>
          <button className="btn btn-small" onClick={onClose}>
            Close
          </button>
        </div>
        <div className="details-body">
          {item.artworkUrl ? (
            <img className="details-artwork" src={item.artworkUrl} alt="" />
          ) : (
            <div className="details-artwork-placeholder">{KIND_ICON[item.kind]}</div>
          )}
          {editing ? (
            <div className="details-edit-form">
              <input
                className="text-input"
                placeholder="Title"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
              />
              <input className="text-input" placeholder="Year" value={year} onChange={(e) => setYear(e.target.value)} />
              <div className="tag-chip-row">
                {tags.map((tag) => (
                  <span key={tag} className="tag-chip">
                    {tag}
                    <button type="button" onClick={() => removeTag(tag)} aria-label={`Remove tag ${tag}`}>
                      ×
                    </button>
                  </span>
                ))}
              </div>
              <input
                className="text-input"
                list="tag-suggestions"
                placeholder="Add tag, press Enter"
                value={tagInput}
                onChange={(e) => setTagInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    e.preventDefault();
                    addTag(tagInput);
                  }
                }}
              />
              <datalist id="tag-suggestions">
                {suggestions.map((t) => (
                  <option key={t} value={t} />
                ))}
              </datalist>
            </div>
          ) : (
            <dl className="details-meta">
              <dt>Year</dt>
              <dd>{item.year || "—"}</dd>
              <dt>Length</dt>
              <dd>{formatDuration(item.durationSeconds)}</dd>
              <dt>Size</dt>
              <dd>{formatSize(item.sizeBytes)}</dd>
              <dt>Format</dt>
              <dd>{item.extension.toUpperCase()}</dd>
              {item.tags && item.tags.length > 0 && (
                <>
                  <dt>Tags</dt>
                  <dd>
                    <div className="tag-chip-row">
                      {item.tags.map((tag) => (
                        <span key={tag} className="tag-chip">
                          {tag}
                        </span>
                      ))}
                    </div>
                  </dd>
                </>
              )}
            </dl>
          )}
        </div>
        <div className="form-actions">
          {editing ? (
            <>
              <button className="btn btn-primary" onClick={() => void save()} disabled={saving}>
                {saving ? "Saving…" : "Save"}
              </button>
              <button className="btn btn-small" onClick={() => setEditing(false)} disabled={saving}>
                Cancel
              </button>
            </>
          ) : (
            <>
              <button className="btn btn-primary" onClick={onOpen}>
                {item.source.type === "network" ? "Play" : "Open"}
              </button>
              {onDownload && (
                <button className="btn" onClick={onDownload}>
                  Download…
                </button>
              )}
              <button className="btn" onClick={startEdit}>
                Edit…
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
