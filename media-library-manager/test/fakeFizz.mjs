// A stand-in for Fizz's Okta + VMedia TV servers, just enough for the TV tab's tests: device-code
// and browser (PKCE) sign-in, /api/client/login/okta with rotating refresh tokens, sessions that
// can be expired, the "My plan" channel list and the v3 guide.
// Used by test/tv.test.mjs and by ../media-library-manager-android/engine-test (run directly:
// `node fakeFizz.mjs serve` prints {"base": "..."}).
import http from "node:http";
import crypto from "node:crypto";

export async function startFakeFizz() {
  const state = {
    pendingPolls: 1, // device-code polls answered "authorization_pending" before approval
    challenges: new Map(), // auth code -> PKCE challenge
    refreshTokens: new Set(),
    tokenSeq: 0,
    sessions: new Set(),
    sessionSeq: 0,
    logins: [],
    guideRequests: [],
    channelRequests: 0
  };

  const json = (res, code, body) => {
    res.writeHead(code, { "Content-Type": "application/json" });
    res.end(JSON.stringify(body));
  };
  const readBody = (req) =>
    new Promise((resolve) => {
      let data = "";
      req.on("data", (c) => (data += c));
      req.on("end", () => resolve(data));
    });

  const issueLogin = (device) => {
    const session = `s${++state.sessionSeq}`;
    state.sessions.add(session);
    const rt = `rt${++state.tokenSeq}`;
    state.refreshTokens.clear(); // refresh tokens rotate: only the newest works
    state.refreshTokens.add(rt);
    return {
      clientCredentials: { sessionID: session, Email: "viewer@example.com", StbMacAddress: device },
      RefreshToken: rt,
      appSettings: { appName: "F-GOOGLE-PLAY-MOBILE", siteID: 176, settings: [] }
    };
  };

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, "http://x");
    const body = await readBody(req);
    const p = url.pathname;
    const form = new URLSearchParams(body);
    const param = (k) => url.searchParams.get(k) ?? form.get(k);

    if (p === "/okta/v1/device/authorize" && req.method === "POST") {
      if (param("client_id") !== "tv-client") return json(res, 400, { error: "unauthorized_client" });
      return json(res, 200, {
        device_code: "dev-1",
        user_code: "ABCD-EFGH",
        verification_uri: "https://auth.example/activate",
        verification_uri_complete: "https://auth.example/activate?user_code=ABCD-EFGH",
        expires_in: 600,
        interval: 1
      });
    }
    if (p === "/okta/v1/authorize" && req.method === "GET") {
      if (param("client_id") !== "phone-client" || param("code_challenge_method") !== "S256") return json(res, 400, { error: "bad" });
      const code = crypto.randomBytes(8).toString("hex");
      state.challenges.set(code, param("code_challenge"));
      const redirect = `${param("redirect_uri")}?code=${code}&state=${encodeURIComponent(param("state"))}`;
      res.writeHead(302, { Location: redirect });
      return res.end();
    }
    if (p === "/okta/v1/token" && req.method === "POST") {
      const grant = param("grant_type");
      if (grant === "urn:ietf:params:oauth:grant-type:device_code") {
        if (param("device_code") !== "dev-1") return json(res, 400, { error: "invalid_grant" });
        if (state.pendingPolls-- > 0) return json(res, 400, { error: "authorization_pending", error_description: "waiting" });
        return json(res, 200, { access_token: "okta-access-device", token_type: "Bearer" });
      }
      if (grant === "authorization_code") {
        const challenge = state.challenges.get(param("code"));
        const expected = crypto.createHash("sha256").update(param("code_verifier") ?? "").digest("base64url");
        if (!challenge || challenge !== expected) return json(res, 400, { error: "invalid_grant", error_description: "PKCE check failed" });
        state.challenges.delete(param("code"));
        return json(res, 200, { access_token: "okta-access-browser", token_type: "Bearer" });
      }
      return json(res, 400, { error: "unsupported_grant_type" });
    }
    if (p === "/api/client/login/okta" && req.method === "POST") {
      const r = JSON.parse(body || "{}");
      const device = r?.cc?.clientCredentials?.StbMacAddress;
      state.logins.push({ access: r.AccessToken ?? null, refresh: r.RefreshToken ?? null, device, appName: r?.cc?.appSettings?.appName });
      if (r.AccessToken && r.AccessToken.startsWith("okta-access-")) return json(res, 200, issueLogin(device));
      if (r.RefreshToken && state.refreshTokens.has(r.RefreshToken)) return json(res, 200, issueLogin(device));
      return json(res, 200, {
        appSettings: { settings: [{ key: "errorCode", value: "INVALID_REFRESH_TOKEN" }, { key: "errorMessage", value: "Refresh token is inactive or invalid" }] },
        clientCredentials: { StbMacAddress: device }
      });
    }
    if (p === "/api/channel/client" && req.method === "POST") {
      state.channelRequests++;
      const r = JSON.parse(body || "{}");
      if (!state.sessions.has(r.sessionID)) return json(res, 500, { Message: `Session ${r.sessionID} is not valid anymore` });
      if (r?.request?.filter?.availableOnly !== true) return json(res, 400, { Message: "expected availableOnly" });
      return json(res, 200, {
        items: [
          { id: 20, accessNum: 12, name: "News 24", Images: [{ Id: 501, Type: 8 }], visible: true },
          { id: 10, accessNum: 3, name: "City TV", Images: [{ Id: 500, Type: "4" }] },
          { id: 30, accessNum: 99, name: "Hidden", visible: false }
        ]
      });
    }
    if (p === "/api/v3/guide/client" && req.method === "GET") {
      const session = req.headers["x-session-id"];
      if (!state.sessions.has(session)) return json(res, 500, { Message: "Object reference not set to an instance of an object." });
      const ids = (url.searchParams.get("ChannelIds") ?? "").split(",").map(Number);
      const from = Number(url.searchParams.get("StartDate"));
      state.guideRequests.push({ ids, from, to: Number(url.searchParams.get("EndDate")) });
      const items = [];
      for (const ch of ids) {
        for (let i = 0; i < 3; i++) {
          const start = new Date(from + i * 30 * 60000).toISOString();
          items.push({
            id: ch * 100 + i,
            name: `Episode ${i + 1}`,
            SeriesName: ch === 10 ? "Morning Show" : null,
            SeasonNumber: ch === 10 ? 2 : null,
            episodeNum: ch === 10 ? i + 1 : null,
            description: `About show ${i + 1}`,
            length: 30,
            Images: [{ Id: 900 + i, Type: 29 }],
            recentAirTime: { id: ch * 1000 + i, channelID: ch, startTime: start }
          });
        }
      }
      return json(res, 200, { items });
    }
    json(res, 404, { Message: "not found" });
  });

  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const base = `http://127.0.0.1:${server.address().port}`;
  return {
    base,
    state,
    config: {
      oktaBase: `${base}/okta/v1`,
      apiBase: base,
      browserClientId: "phone-client",
      deviceClientId: "tv-client",
      redirectUri: "fizztv://callback",
      scope: "openid email profile offline_access",
      appName: "F-GOOGLE-PLAY-MOBILE",
      siteId: 176,
      imageBase: "https://images.example/api/images"
    },
    expireSessions: () => state.sessions.clear(),
    revokeRefreshTokens: () => state.refreshTokens.clear(),
    close: () => new Promise((r) => server.close(r))
  };
}

if (process.argv[2] === "serve") {
  const fake = await startFakeFizz();
  // Lets the JVM test drive the fake: /control/expire and /control/revoke.
  const control = http.createServer((req, res) => {
    if (req.url === "/expire") fake.expireSessions();
    if (req.url === "/revoke") fake.revokeRefreshTokens();
    if (req.url === "/state") return res.end(JSON.stringify({ logins: fake.state.logins, guideRequests: fake.state.guideRequests }));
    res.end("ok");
  });
  await new Promise((r) => control.listen(0, "127.0.0.1", r));
  console.log(JSON.stringify({ ...fake.config, control: `http://127.0.0.1:${control.address().port}` }));
}
