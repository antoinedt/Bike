import type SMB2 from "smb2";
import type { NetworkShare } from "../../shared/types.js";
import { sharePath, smbMkdirp } from "../transfer/smbWrite.js";
import type { Mailbox } from "./remoteQueue.js";

function call<T>(fn: (cb: (err: Error | null, result: T) => void) => void): Promise<T> {
  return new Promise((resolve, reject) => fn((err, result) => (err ? reject(err) : resolve(result))));
}

/** The mailbox folder on a share, over one SMB connection. */
export function smbMailbox(client: SMB2, share: NetworkShare): Mailbox {
  const full = (p: string) => sharePath(share, p);
  const parent = (p: string) => p.split("\\").slice(0, -1).join("\\");
  return {
    list: (dir) => call<string[]>((cb) => client.readdir(full(dir), cb)).catch(() => []),
    read: (p) => call<Buffer>((cb) => client.readFile(full(p), cb)),
    write: async (p, data) => {
      await smbMkdirp(client, parent(full(p)));
      await call<void>((cb) => client.writeFile(full(p), data, (err) => cb(err, undefined)));
    },
    rename: (from, to) => call<void>((cb) => client.rename(full(from), full(to), (err) => cb(err, undefined))),
    remove: (p) => call<void>((cb) => client.unlink(full(p), (err) => cb(err, undefined)))
  };
}

