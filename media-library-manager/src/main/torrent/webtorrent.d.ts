// Minimal typings for the parts of webtorrent 2.x / parse-torrent 11.x this app uses (neither
// ships its own; @types/webtorrent describes a different major version).

declare module "webtorrent" {
  import { EventEmitter } from "node:events";

  export interface TorrentFile {
    name: string;
    path: string;
    length: number;
  }

  export interface Torrent extends EventEmitter {
    infoHash: string;
    name: string;
    magnetURI: string;
    /** The .torrent file contents, once metadata is known. */
    torrentFile: Uint8Array;
    files: TorrentFile[];
    length: number;
    downloaded: number;
    uploaded: number;
    /** 0..1 */
    progress: number;
    downloadSpeed: number;
    uploadSpeed: number;
    numPeers: number;
    /** Milliseconds; Infinity when unknown. */
    timeRemaining: number;
    path: string;
    done: boolean;
    /** True once metadata is known and existing data has been verified. */
    ready: boolean;
    addPeer(peer: string): boolean;
    destroy(opts?: { destroyStore?: boolean }, cb?: (err?: Error | null) => void): void;
  }

  export interface WebTorrentOptions {
    dht?: boolean | object;
    tracker?: boolean | object;
    lsd?: boolean;
    natUpnp?: boolean;
    natPmp?: boolean;
    utp?: boolean;
    torrentPort?: number;
    maxConns?: number;
  }

  export interface AddOptions {
    path?: string;
    announce?: string[];
  }

  export default class WebTorrent extends EventEmitter {
    constructor(opts?: WebTorrentOptions);
    torrents: Torrent[];
    torrentPort: number;
    add(torrentId: string | Uint8Array, opts?: AddOptions, onTorrent?: (torrent: Torrent) => void): Torrent;
    seed(input: string | string[], opts?: object, onSeed?: (torrent: Torrent) => void): Torrent;
    get(torrentId: string | Uint8Array): Promise<Torrent | null>;
    destroy(cb?: (err?: Error | null) => void): void;
  }
}

declare module "parse-torrent" {
  export interface ParsedTorrent {
    infoHash: string;
    name?: string;
    length?: number;
    announce?: string[];
  }
  export default function parseTorrent(torrentId: string | Uint8Array): Promise<ParsedTorrent>;
  export function toMagnetURI(parsed: ParsedTorrent): string;
}
