import type { Tab } from "../App.js";

const ITEMS: { id: Tab; label: string; icon: string }[] = [
  { id: "library", label: "Library", icon: "🎞" },
  { id: "network", label: "Network", icon: "🖧" },
  { id: "search", label: "Search", icon: "🔎" },
  { id: "settings", label: "Settings", icon: "⚙" }
];

export default function Sidebar({ active, onSelect }: { active: Tab; onSelect: (t: Tab) => void }) {
  return (
    <nav className="sidebar">
      <div className="sidebar-title">Media Library</div>
      {ITEMS.map((item) => (
        <button
          key={item.id}
          className={`sidebar-item${active === item.id ? " active" : ""}`}
          onClick={() => onSelect(item.id)}
        >
          <span className="sidebar-icon">{item.icon}</span>
          {item.label}
        </button>
      ))}
    </nav>
  );
}
