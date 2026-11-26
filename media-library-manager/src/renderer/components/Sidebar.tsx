import type { Tab } from "../App.js";
import { Icon, type IconName } from "./Icon.js";

const ITEMS: { id: Tab; label: string; icon: IconName }[] = [
  { id: "library", label: "Library", icon: "library" },
  { id: "search", label: "Search", icon: "search" },
  { id: "tv", label: "TV", icon: "tv" },
  { id: "downloads", label: "Downloads", icon: "download" },
  { id: "settings", label: "Settings", icon: "settings" }
];

export default function Sidebar({ active, onSelect }: { active: Tab; onSelect: (t: Tab) => void }) {
  return (
    <nav className="sidebar">
      <div className="sidebar-title" title="OmniPlay"><img className="sidebar-logo" src="./omniplay.svg" alt="OmniPlay" /></div>
      {ITEMS.map((item) => (
        <button
          key={item.id}
          className={`sidebar-item${active === item.id ? " active" : ""}`}
          onClick={() => onSelect(item.id)}
          title={item.label}
          aria-label={item.label}
        >
          <span className="sidebar-icon">
            <Icon name={item.icon} size={24} />
          </span>
        </button>
      ))}
    </nav>
  );
}
