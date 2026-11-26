import { createRequire } from "node:module";
import { useNtlmV2ForSmb2 } from "./ntlmv2.js";

// Fixes to the smb2 npm package (0.2.x), applied once at startup through its module cache:
//  1. Sign in with NTLMv2 (it only does NTLMv1, which current NAS/Windows defaults refuse).
//  2. Ignore "STATUS_PENDING" interim replies. A busy server (e.g. Samba with several reads or
//     writes in flight) first answers "still working on it" and sends the real answer later under
//     the same message id; the package took the interim reply as the answer, and as an error.

const STATUS_PENDING = 0x00000103;
let applied = false;

export function applySmb2Fixes(): void {
  if (applied) return;
  applied = true;
  useNtlmV2ForSmb2();

  const require = createRequire(import.meta.url);
  const SMB2Forge = require("smb2/lib/tools/smb2-forge.js");
  const SMB2Message = require("smb2/lib/tools/smb2-message.js");

  // Same as the package's own response reader, plus the STATUS_PENDING check.
  SMB2Forge.response = function (c: {
    responses: Record<string, unknown>;
    responsesCB: Record<string, (message: unknown) => void>;
    responseBuffer: Buffer;
  }) {
    c.responses = {};
    c.responsesCB = {};
    c.responseBuffer = Buffer.alloc(0);
    return function (data: Buffer) {
      c.responseBuffer = Buffer.concat([c.responseBuffer, data]);
      for (;;) {
        if (c.responseBuffer.length < 4) return;
        const msgLength = (c.responseBuffer.readUInt8(1) << 16) + c.responseBuffer.readUInt16BE(2);
        if (c.responseBuffer.length < msgLength + 4) return;
        const message = new SMB2Message();
        message.parseBuffer(c.responseBuffer.subarray(4, msgLength + 4));
        c.responseBuffer = c.responseBuffer.subarray(msgLength + 4);
        const headers = message.getHeaders();
        if ((headers.Status as Buffer).readUInt32LE(0) === STATUS_PENDING) continue; // the real reply follows
        const id = (headers.MessageId as Buffer).toString("hex");
        const cb = c.responsesCB[id];
        if (cb) {
          delete c.responsesCB[id];
          cb(message);
        } else {
          c.responses[id] = message;
        }
      }
    };
  };
}
