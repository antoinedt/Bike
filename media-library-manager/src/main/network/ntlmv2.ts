import { createHash, createHmac, randomBytes } from "node:crypto";
import { createRequire } from "node:module";

// NTLMv2 sign-in for the smb2 package, which only does NTLMv1 out of the box. NTLMv1 is refused by
// default on most current servers (Samba 4.5+ "ntlm auth = ntlmv2-only", Synology DSM 7, Windows 11
// 24H2), so without this the desktop app can't open those shares at all. MS-NLMP sections 2.2.1 and
// 3.3.2. No signing/sealing (the smb2 package doesn't sign), so no session key exchange or MIC.

const NEGOTIATE_UNICODE = 0x00000001;
const REQUEST_TARGET = 0x00000004;
const NEGOTIATE_NTLM = 0x00000200;
const NEGOTIATE_ALWAYS_SIGN = 0x00008000;
const NEGOTIATE_EXTENDED_SESSIONSECURITY = 0x00080000;
const NEGOTIATE_TARGET_INFO = 0x00800000;
const NEGOTIATE_128 = 0x20000000;
const NEGOTIATE_56 = 0x80000000;

const CLIENT_FLAGS =
  (NEGOTIATE_UNICODE |
    REQUEST_TARGET |
    NEGOTIATE_NTLM |
    NEGOTIATE_ALWAYS_SIGN |
    NEGOTIATE_EXTENDED_SESSIONSECURITY |
    NEGOTIATE_TARGET_INFO |
    NEGOTIATE_128 |
    NEGOTIATE_56) >>>
  0;

const SIGNATURE = Buffer.from("NTLMSSP\0", "ascii");
const AV_EOL = 0;
const AV_TIMESTAMP = 7;

export function encodeNegotiate(): Buffer {
  const buf = Buffer.alloc(32);
  SIGNATURE.copy(buf, 0);
  buf.writeUInt32LE(1, 8);
  buf.writeUInt32LE(CLIENT_FLAGS, 12);
  // Empty domain and workstation fields (length 0, offset 32).
  buf.writeUInt32LE(32 << 16, 16);
  buf.writeUInt32LE(32, 20);
  buf.writeUInt32LE(32 << 16, 24);
  buf.writeUInt32LE(32, 28);
  return buf;
}

export interface Challenge {
  serverChallenge: Buffer;
  flags: number;
  targetInfo: Buffer;
}

export function decodeChallenge(buf: Buffer): Challenge {
  if (buf.length < 32 || !buf.subarray(0, 8).equals(SIGNATURE) || buf.readUInt32LE(8) !== 2) {
    throw new Error("The server didn't answer with an NTLM challenge.");
  }
  const flags = buf.readUInt32LE(20);
  const serverChallenge = Buffer.from(buf.subarray(24, 32));
  let targetInfo = Buffer.alloc(0);
  if (buf.length >= 48) {
    const len = buf.readUInt16LE(40);
    const offset = buf.readUInt32LE(44);
    if (len > 0 && offset + len <= buf.length) targetInfo = Buffer.from(buf.subarray(offset, offset + len));
  }
  return { serverChallenge, flags, targetInfo };
}

function md4(data: Buffer): Buffer {
  return createHash("md4").update(data).digest();
}

function hmacMd5(key: Buffer, ...data: Buffer[]): Buffer {
  const h = createHmac("md5", key);
  for (const d of data) h.update(d);
  return h.digest();
}

/** The server's timestamp from the challenge's target info, if it sent one (8-byte FILETIME). */
function timestampFrom(targetInfo: Buffer): Buffer | null {
  let pos = 0;
  while (pos + 4 <= targetInfo.length) {
    const id = targetInfo.readUInt16LE(pos);
    const len = targetInfo.readUInt16LE(pos + 2);
    if (id === AV_EOL) break;
    if (id === AV_TIMESTAMP && len === 8) return Buffer.from(targetInfo.subarray(pos + 4, pos + 12));
    pos += 4 + len;
  }
  return null;
}

function fileTimeNow(): Buffer {
  const buf = Buffer.alloc(8);
  // 100 ns intervals since 1601-01-01.
  buf.writeBigUInt64LE((BigInt(Date.now()) + 11644473600000n) * 10000n);
  return buf;
}

export function encodeAuthenticate(
  challenge: Challenge,
  username: string,
  password: string,
  domain: string,
  workstation: string,
  clientChallenge: Buffer = randomBytes(8),
  now?: Buffer
): Buffer {
  const ntHash = md4(Buffer.from(password, "utf16le"));
  const ntV2Hash = hmacMd5(ntHash, Buffer.from(username.toUpperCase() + domain, "utf16le"));
  const serverTime = timestampFrom(challenge.targetInfo);
  const timestamp = serverTime ?? now ?? fileTimeNow();

  const temp = Buffer.concat([
    Buffer.from([1, 1, 0, 0, 0, 0, 0, 0]),
    timestamp,
    clientChallenge,
    Buffer.alloc(4),
    challenge.targetInfo,
    Buffer.alloc(4)
  ]);
  const ntProof = hmacMd5(ntV2Hash, challenge.serverChallenge, temp);
  const ntResponse = Buffer.concat([ntProof, temp]);
  // With a server timestamp, the LM response is all zeros (MS-NLMP 3.1.5.1.2); otherwise LMv2.
  const lmResponse = serverTime
    ? Buffer.alloc(24)
    : Buffer.concat([hmacMd5(ntV2Hash, challenge.serverChallenge, clientChallenge), clientChallenge]);

  const domainBuf = Buffer.from(domain, "utf16le");
  const userBuf = Buffer.from(username, "utf16le");
  const hostBuf = Buffer.from(workstation.toUpperCase(), "utf16le");
  const flags = (challenge.flags & CLIENT_FLAGS) >>> 0 || CLIENT_FLAGS;

  const header = Buffer.alloc(64);
  SIGNATURE.copy(header, 0);
  header.writeUInt32LE(3, 8);
  let offset = 64;
  const fields: Array<[number, Buffer]> = [];
  const field = (at: number, data: Buffer) => {
    header.writeUInt16LE(data.length, at);
    header.writeUInt16LE(data.length, at + 2);
    header.writeUInt32LE(offset, at + 4);
    fields.push([offset, data]);
    offset += data.length;
  };
  field(28, domainBuf);
  field(36, userBuf);
  field(44, hostBuf);
  field(12, lmResponse);
  field(20, ntResponse);
  field(52, Buffer.alloc(0)); // no encrypted session key
  header.writeUInt32LE(flags, 60);
  return Buffer.concat([header, ...fields.map(([, data]) => data)]);
}

let installed = false;

/**
 * Makes the smb2 package sign in with NTLMv2 instead of NTLMv1. It looks its protocol messages up
 * by name from Node's module cache, so swapping the two session-setup steps here changes every
 * connection it makes. Call once at startup (idempotent).
 */
export function useNtlmV2ForSmb2(): void {
  if (installed) return;
  installed = true;
  const require = createRequire(import.meta.url);
  const SMB2Message = require("smb2/lib/tools/smb2-message.js");
  const step1 = require("smb2/lib/messages/session_setup_step1.js");
  const step2 = require("smb2/lib/messages/session_setup_step2.js");

  step1.generate = (connection: { ProcessId: unknown }) =>
    new SMB2Message({
      headers: { Command: "SESSION_SETUP", ProcessId: connection.ProcessId },
      request: { Buffer: encodeNegotiate() }
    });
  step1.onSuccess = (
    connection: { SessionId: unknown; ntlmChallenge?: Challenge },
    response: { getHeaders(): { SessionId: unknown }; getResponse(): { Buffer: Buffer } }
  ) => {
    connection.SessionId = response.getHeaders().SessionId;
    connection.ntlmChallenge = decodeChallenge(response.getResponse().Buffer);
  };
  step2.generate = (connection: {
    SessionId: unknown;
    ProcessId: unknown;
    ntlmChallenge: Challenge;
    username: string;
    password: string;
    domain: string;
  }) =>
    new SMB2Message({
      headers: { Command: "SESSION_SETUP", SessionId: connection.SessionId, ProcessId: connection.ProcessId },
      request: {
        Buffer: encodeAuthenticate(connection.ntlmChallenge, connection.username, connection.password, connection.domain || "", "MEDIALIBRARY")
      }
    });
}
