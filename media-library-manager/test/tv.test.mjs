// TV tab: the Fizz client (src/main/tv/fizzTv.ts) against a fake Fizz server — browser sign-in with
// PKCE, the saved login surviving restarts and network errors, session renewal, My plan + guide.
import { test, after } from "node:test";
import assert from "node:assert/strict";
import { FizzTvClient, SignInRequiredError, parseTime } from "../dist-electron/main/tv/fizzTv.js";
import { startFakeFizz } from "./fakeFizz.mjs";

const fake = await startFakeFizz();
after(() => fake.close());

function memoryStore() {
  let value = null;
  return { load: () => value, save: (v) => (value = v), deviceId: () => "0123456789abcdef", get value() { return value; } };
}

async function signIn(client) {
  const pending = client.startBrowserSignIn();
  const res = await fetch(pending.url, { redirect: "manual" });
  const redirect = res.headers.get("location");
  assert.ok(client.isRedirect(redirect));
  await client.finishBrowserSignIn(redirect, pending);
}

test("browser sign-in, My plan channels and guide", async () => {
  const store = memoryStore();
  const client = new FizzTvClient(store, fake.config);
  const pending = client.startBrowserSignIn();
  assert.match(pending.url, /code_challenge_method=S256/);
  assert.match(pending.url, /redirect_uri=fizztv%3A%2F%2Fcallback/);
  const bad = (await fetch(pending.url, { redirect: "manual" })).headers.get("location").replace(/state=[^&]+/, "state=x");
  await assert.rejects(client.finishBrowserSignIn(bad, pending), /interrupted/);
  await signIn(client);
  assert.equal(store.value.email, "viewer@example.com");
  assert.equal(store.value.deviceId, "0123456789abcdef");

  const channels = await client.myPlanChannels();
  assert.deepEqual(channels.map((c) => c.id), [10, 20]);
  assert.equal(channels[0].logoUrl, "https://images.example/api/images?id=500&t=4");

  const from = 1_800_000_000_000;
  const programs = await client.guide([20, 10], from, from + 90 * 60_000);
  assert.equal(programs.length, 6);
  const first = programs.find((p) => p.channelId === 10);
  assert.equal(first.title, "Morning Show");
  assert.equal(first.episode, "S2 E1 · Episode 1");
  assert.equal(first.end - first.start, 30 * 60_000);
  assert.deepEqual(fake.state.guideRequests.at(-1).ids, [10, 20]);
});

test("expired session is renewed; network errors keep the login; a rejected refresh token signs out", async () => {
  const store = memoryStore();
  const client = new FizzTvClient(store, fake.config);
  await signIn(client);
  const before = store.value;
  fake.expireSessions();
  assert.equal((await client.myPlanChannels(true)).length, 2);
  assert.notEqual(store.value.sessionId, before.sessionId);
  assert.notEqual(store.value.refreshToken, before.refreshToken);

  const restarted = new FizzTvClient(store, fake.config);
  assert.equal((await restarted.guide([10], 0, 3600_000)).length, 2);

  const offline = new FizzTvClient(store, { ...fake.config, apiBase: "http://127.0.0.1:1" });
  await assert.rejects(offline.myPlanChannels(true), (e) => !(e instanceof SignInRequiredError));
  assert.ok(store.value, "login kept after a network error");

  fake.revokeRefreshTokens();
  fake.expireSessions();
  await assert.rejects(client.myPlanChannels(true), SignInRequiredError);
  assert.equal(store.value, null);
  assert.ok(fake.state.logins.every((l) => l.appName === "F-GOOGLE-PLAY-MOBILE"));
});

test("parallel guide requests after the session expired renew it once and stay signed in", async () => {
  const store = memoryStore();
  const client = new FizzTvClient(store, fake.config);
  await signIn(client);
  fake.expireSessions();
  const loginsBefore = fake.state.logins.length;
  const results = await Promise.all([10, 20, 10, 20, 10, 20].map((id) => client.guide([id], 0, 3600_000)));
  assert.ok(results.every((r) => r.length === 2));
  assert.equal(fake.state.logins.length - loginsBefore, 1, "one renewal for all of them");
  assert.ok(store.value);
});

test("guide times without a zone are UTC", () => {
  assert.equal(parseTime("2026-11-26T05:00:00"), Date.UTC(2026, 10, 26, 5));
  assert.equal(parseTime("2026-11-26T05:00:00-05:00"), Date.UTC(2026, 10, 26, 10));
  assert.ok(Number.isNaN(parseTime("")));
});
