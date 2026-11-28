/** The last three TV channels watched or cast, newest first, for Home's "On now" row. */
export interface RecentChannel {
  id: number;
  name: string;
}

const KEY = "tv.recent";

export function recentChannels(): RecentChannel[] {
  try {
    const list = JSON.parse(localStorage.getItem(KEY) ?? "[]") as RecentChannel[];
    return Array.isArray(list) ? list.filter((c) => typeof c?.id === "number").slice(0, 3) : [];
  } catch {
    return [];
  }
}

export function recordRecentChannel(channel: RecentChannel): void {
  const next = [{ id: channel.id, name: channel.name }, ...recentChannels().filter((c) => c.id !== channel.id)].slice(0, 3);
  try {
    localStorage.setItem(KEY, JSON.stringify(next));
  } catch {
    // not remembered; fine
  }
}
