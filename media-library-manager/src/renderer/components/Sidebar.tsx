import { useState } from "react";
import type { Tab } from "../App.js";
import { Icon, type IconName } from "./Icon.js";

export const TABS: { id: Tab; label: string; icon: IconName }[] = [
  { id: "home", label: "Home", icon: "home" },
  { id: "folders", label: "Folders", icon: "folder" },
  { id: "tv", label: "TV guide", icon: "tv" },
  { id: "downloads", label: "Downloads", icon: "download" },
  { id: "settings", label: "Settings", icon: "settings" }
];

/** The side menu: ≡ shows or hides the labels (remembered); the same five places as on Android. */
export default function Sidebar({ active, onSelect }: { active: Tab; onSelect: (t: Tab) => void }) {
  const [open, setOpen] = useState(() => {
    try {
      return localStorage.getItem("sidebar.open") !== "0";
    } catch {
      return true;
    }
  });
  function toggle() {
    setOpen((o) => {
      try {
        localStorage.setItem("sidebar.open", o ? "0" : "1");
      } catch {
        // not remembered; fine
      }
      return !o;
    });
  }
  return (
    <nav className={`sidebar${open ? " open" : ""}`}>
      <div className="sidebar-top">
        <button className="sidebar-item sidebar-menu" onClick={toggle} title={open ? "Collapse menu" : "Expand menu"} aria-label="Menu" aria-expanded={open}>
          <span className="sidebar-icon">
            <Icon name="menu" size={24} />
          </span>
        </button>
        {open && (
          <span className="sidebar-brand">
            <img className="sidebar-logo" src="./omniplay.svg" alt="" /> OmniPlay
          </span>
        )}
      </div>
      {TABS.map((item) => (
        <button
          key={item.id}
          className={`sidebar-item${active === item.id ? " active" : ""}`}
          onClick={() => onSelect(item.id)}
          title={item.label}
          aria-label={item.label}
          aria-current={active === item.id ? "page" : undefined}
        >
          <span className="sidebar-icon">
            <Icon name={item.icon} size={24} />
          </span>
          {open && <span className="sidebar-label">{item.label}</span>}
        </button>
      ))}
    </nav>
  );
}
