import multicastDns from "multicast-dns";

export interface CastDevice {
  id: string;
  name: string;
  host: string;
  port: number;
}

/** Looks for Google Cast devices (Google TV, Chromecast, Nest) on the local network via mDNS. */
export async function discoverCast(timeoutMs = 3000): Promise<CastDevice[]> {
  const mdns = multicastDns();
  const found = new Map<string, CastDevice>();
  const srv = new Map<string, { target: string; port: number }>();
  const txt = new Map<string, Record<string, string>>();
  const addresses = new Map<string, string>();

  const consider = () => {
    for (const [instance, s] of srv) {
      const host = addresses.get(s.target);
      if (!host) continue;
      const t = txt.get(instance) ?? {};
      const id = t.id || instance;
      found.set(id, { id, name: t.fn || instance.split("._googlecast")[0], host, port: s.port });
    }
  };

  mdns.on("response", (response) => {
    for (const record of [...response.answers, ...(response.additionals ?? [])]) {
      if (record.type === "SRV" && record.name.includes("_googlecast._tcp")) {
        const data = record.data as { target: string; port: number };
        srv.set(record.name, { target: data.target, port: data.port });
      } else if (record.type === "TXT" && record.name.includes("_googlecast._tcp")) {
        const entries: Record<string, string> = {};
        for (const chunk of record.data as Buffer[]) {
          const s = chunk.toString("utf8");
          const eq = s.indexOf("=");
          if (eq > 0) entries[s.slice(0, eq)] = s.slice(eq + 1);
        }
        txt.set(record.name, entries);
      } else if (record.type === "A") {
        addresses.set(record.name, record.data as string);
      }
    }
    consider();
  });

  const query = () => mdns.query({ questions: [{ name: "_googlecast._tcp.local", type: "PTR" }] });
  query();
  const again = setTimeout(query, Math.min(1000, timeoutMs / 2));
  await new Promise((r) => setTimeout(r, timeoutMs));
  clearTimeout(again);
  mdns.destroy();
  return [...found.values()];
}
