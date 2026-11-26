// NTLMv2 against the worked example in Microsoft's spec (MS-NLMP 4.2.4).
import { test } from "node:test";
import assert from "node:assert/strict";
import { encodeAuthenticate, encodeNegotiate, decodeChallenge } from "../dist-electron/main/network/ntlmv2.js";

const hex = (s) => Buffer.from(s.replace(/\s+/g, ""), "hex");
const targetInfo = hex("02000c0044006f006d00610069006e00 01000c00530065007200760065007200 00000000");

function field(msg, at) {
  const len = msg.readUInt16LE(at);
  const off = msg.readUInt32LE(at + 4);
  return msg.subarray(off, off + len);
}

test("NTLMv2 responses match MS-NLMP 4.2.4", () => {
  const challenge = { serverChallenge: hex("0123456789abcdef"), flags: 0xe28a8233, targetInfo };
  const msg = encodeAuthenticate(challenge, "User", "Password", "Domain", "COMPUTER", hex("aaaaaaaaaaaaaaaa"), Buffer.alloc(8));
  assert.equal(msg.subarray(0, 8).toString("latin1"), "NTLMSSP\0");
  assert.equal(msg.readUInt32LE(8), 3);
  assert.equal(field(msg, 12).toString("hex"), "86c35097ac9cec102554764a57cccc19aaaaaaaaaaaaaaaa", "LMv2 response");
  const nt = field(msg, 20);
  assert.equal(nt.subarray(0, 16).toString("hex"), "68cd0ab851e51c96aabc927bebef6a1c", "NTProofStr");
  assert.equal(nt.subarray(16, 32).toString("hex"), "0101000000000000" + "0000000000000000", "blob header + time");
  assert.equal(field(msg, 28).toString("utf16le"), "Domain");
  assert.equal(field(msg, 36).toString("utf16le"), "User");
});

test("negotiate / challenge framing", () => {
  const neg = encodeNegotiate();
  assert.equal(neg.length, 32);
  assert.equal(neg.readUInt32LE(8), 1);
  const type2 = Buffer.alloc(48 + targetInfo.length);
  Buffer.from("NTLMSSP\0", "latin1").copy(type2, 0);
  type2.writeUInt32LE(2, 8);
  type2.writeUInt32LE(0xe28a8233, 20);
  hex("0123456789abcdef").copy(type2, 24);
  type2.writeUInt16LE(targetInfo.length, 40);
  type2.writeUInt16LE(targetInfo.length, 42);
  type2.writeUInt32LE(48, 44);
  targetInfo.copy(type2, 48);
  const c = decodeChallenge(type2);
  assert.equal(c.serverChallenge.toString("hex"), "0123456789abcdef");
  assert.ok(c.targetInfo.equals(targetInfo));
  assert.throws(() => decodeChallenge(Buffer.alloc(40)), /NTLM challenge/);
});
