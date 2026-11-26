import crypto from "node:crypto";
import type { TvChannel, TvProgram } from "../../shared/types.js";

/** Where the Fizz TV service lives. Only tests change it. */
export interface FizzConfig {
  oktaBase: string;
  apiBase: string;
  /** Phone app's Okta client: browser sign-in (code + PKCE). */
  browserClientId: string;
  redirectUri: string;
  scope: string;
  appName: string;
  siteId: number;
  imageBase: string;
}

export const FIZZ_CONFIG: FizzConfig = {
  oktaBase: "https://auth.fizz.ca/oauth2/aus4ah1q96x2bjArV697/v1",
  apiBase: "https://a2.vmedia.ca",
  browserClientId: "0oa4ah2n6j5msTWe4697",
  redirectUri: "fizztv://callback",
  scope: "openid email profile offline_access",
  appName: "F-GOOGLE-PLAY-MOBILE",
  siteId: 176,
  imageBase: "https://images.cdn.vmedia.ca/api/images"
};

/** What stays saved between launches. */
export interface TvCredentials {
  refreshToken: string;
  sessionId: string | null;
  email: string | null;
  deviceId: string;
}

export interface TvCredentialStore {
  load(): TvCredentials | null;
  save(credentials: TvCredentials | null): void;
  /** Made once and kept (also across sign-outs), so the service always sees the same device. */
  deviceId(): string;
}

export interface BrowserSignIn {
  url: string;
  state: string;
  verifier: string;
}

/** The saved sign-in is gone or was refused: show the sign-in screen. */
export class SignInRequiredError extends Error {
  readonly signInRequired = true;
  constructor(message = "Sign in to your Fizz account.") {
    super(message);
  }
}

class HttpError extends Error {
  constructor(
    readonly status: number,
    readonly body: string
  ) {
    super(`HTTP ${status}`);
  }
}

const b64url = (b: Buffer) => b.toString("base64url");

/** The service's maximum page size for the guide. */
export const PAGE_SIZE = 500;
const MAX_PAGES = 10;

/** A device id like the Fizz app's (Android's 16-hex-digit ANDROID_ID). */
export const randomDeviceId = () => crypto.randomBytes(8).toString("hex");

type Json = Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any

/**
 * The bits of the Fizz TV app this app uses: sign-in (Okta, then the VMedia platform's own session)
 * and the guide for the channels in your plan. Same calls and saved login as the phone app: the
 * refresh token is kept until the server says it's invalid (a network error never wipes it), and
 * the session id is renewed with it when the server says the session expired.
 * Mirrors FizzTvClient.kt in the Android app.
 */
export class FizzTvClient {
  private channelCache: TvChannel[] | null = null;

  constructor(
    private readonly store: TvCredentialStore,
    readonly config: FizzConfig = FIZZ_CONFIG
  ) {}

  credentials(): TvCredentials | null {
    return this.store.load();
  }

  // ---- Sign-in in a browser window ----

  startBrowserSignIn(): BrowserSignIn {
    const verifier = b64url(crypto.randomBytes(48));
    const challenge = b64url(crypto.createHash("sha256").update(verifier).digest());
    const state = b64url(crypto.randomBytes(16));
    const params = new URLSearchParams({
      client_id: this.config.browserClientId,
      response_type: "code",
      scope: this.config.scope,
      redirect_uri: this.config.redirectUri,
      state,
      nonce: b64url(crypto.randomBytes(16)),
      code_challenge: challenge,
      code_challenge_method: "S256"
    });
    return { url: `${this.config.oktaBase}/authorize?${params.toString().replace(/\+/g, "%20")}`, state, verifier };
  }

  /** True for the address Okta sends the browser to once you've signed in. */
  isRedirect(url: string): boolean {
    return url.startsWith(this.config.redirectUri);
  }

  async finishBrowserSignIn(redirectUrl: string, pending: BrowserSignIn): Promise<void> {
    const query = new URLSearchParams(redirectUrl.split("?")[1]?.split("#")[0] ?? "");
    const error = query.get("error");
    if (error) throw new Error(query.get("error_description") ?? error);
    if (query.get("state") !== pending.state) throw new Error("Sign-in was interrupted. Please try again.");
    const code = query.get("code");
    if (!code) throw new Error("Sign-in didn't return a code.");
    const token = await this.postForm(`${this.config.oktaBase}/token`, {
      grant_type: "authorization_code",
      client_id: this.config.browserClientId,
      redirect_uri: this.config.redirectUri,
      code,
      code_verifier: pending.verifier
    });
    const accessToken = token.access_token as string | undefined;
    if (!accessToken) throw new Error(token.error_description || token.error || "Sign-in failed.");
    await this.login({ accessToken, oktaRefreshToken: token.refresh_token });
  }

  signOut(): void {
    this.channelCache = null;
    this.store.save(null);
  }

  // ---- Guide ----

  /** The channels in your plan ("My plan" in the Fizz app), in channel-number order. */
  async myPlanChannels(forceRefresh = false): Promise<TvChannel[]> {
    if (!forceRefresh && this.channelCache) return this.channelCache;
    const body = await this.withSession((session) =>
      this.http("POST", `${this.config.apiBase}/api/channel/client`, {
        json: {
          request: {
            type: "4",
            filter: { contentType: "4", availableOnly: true, HasImagesOnly: false },
            paging: { itemsOnPage: 1000, pageNumber: 1 },
            UseUtcTime: true
          },
          sessionID: session
        }
      })
    );
    const items: Json[] = JSON.parse(body).items ?? [];
    const seen = new Set<number>();
    const channels = items
      .filter((c) => c.visible !== false)
      .map((c) => ({
        id: Number(c.id),
        number: toInt(c.accessNum) ?? toInt(c.orderNum),
        name: String(c.name || c.CallSign || ""),
        logoUrl: this.imageUrl(c.Images, [8, 4, 10])
      }))
      .filter((c) => !seen.has(c.id) && seen.add(c.id))
      .sort((a, b) => (a.number ?? Infinity) - (b.number ?? Infinity) || a.name.localeCompare(b.name));
    this.channelCache = channels;
    return channels;
  }

  /** Programs on these channels between from and to (epoch ms). */
  async guide(channelIds: number[], from: number, to: number): Promise<TvProgram[]> {
    if (channelIds.length === 0) return [];
    const ids = [...new Set(channelIds)].sort((a, b) => a - b).join(",");
    // The service allows at most 500 results per request: page through them.
    const items: Json[] = [];
    for (let page = 1; page <= MAX_PAGES; page++) {
      const params = new URLSearchParams({ ChannelIds: ids, StartDate: String(from), EndDate: String(to), PageNumber: String(page), PageSize: String(PAGE_SIZE) });
      const body = await this.withSession((session) =>
        this.http("GET", `${this.config.apiBase}/api/v3/guide/client?${params}`, { headers: { "X-Session-Id": session } })
      );
      const pageItems: Json[] = JSON.parse(body).items ?? [];
      items.push(...pageItems);
      if (pageItems.length < PAGE_SIZE) break;
    }
    const seen = new Set<string>();
    const programs: TvProgram[] = [];
    for (const p of items) {
      const air = p.recentAirTime;
      const channelId = toInt(air?.channelID);
      const start = parseTime(air?.startTime);
      if (channelId == null || Number.isNaN(start)) continue;
      const minutes = Math.max(1, toInt(p.length) ?? 0);
      const series: string | null = p.SeriesName || null;
      const name: string = p.name || p.shortName || "";
      const season = toInt(p.SeasonNumber);
      const ep = toInt(p.episodeNum);
      const parts = [season != null && ep != null ? `S${season} E${ep}` : null, series && name && name !== series ? name : null].filter(Boolean);
      const program: TvProgram = {
        key: `${air.id ?? 0}-${p.id ?? 0}-${start}`,
        channelId,
        title: (series ?? name) || "—",
        episode: parts.length ? parts.join(" · ") : null,
        description: p.description || p.shortDescription || null,
        start,
        end: start + minutes * 60_000,
        imageUrl: this.imageUrl(p.Images, [29, 28]) ?? this.imageUrl(p.SeriesImages, [29, 28])
      };
      if (program.end <= from || program.start >= to || seen.has(program.key)) continue;
      seen.add(program.key);
      programs.push(program);
    }
    return programs.sort((a, b) => a.start - b.start);
  }

  // ---- Session handling ----

  /** Runs call with the saved session, renewing it with the refresh token when the server refuses it. */
  private async withSession(call: (session: string) => Promise<string>): Promise<string> {
    const saved = this.store.load();
    if (!saved) throw new SignInRequiredError();
    const session = saved.sessionId ?? (await this.renew(""));
    try {
      return await call(session);
    } catch (e) {
      if (!(e instanceof HttpError) || ![401, 403, 500].includes(e.status)) throw e;
    }
    // The server says the session is no longer valid (it does that with an HTTP 500): renew it once.
    const renewed = await this.renew(session);
    try {
      return await call(renewed);
    } catch (e) {
      if (e instanceof HttpError) throw new Error(`The TV service didn't answer (${e.status}). Try again in a moment.`);
      throw e;
    }
  }

  private renewing: Promise<string> | null = null;

  /**
   * One renewal at a time: the guide loads several requests in parallel, and Fizz replaces the
   * refresh token on each renewal, so parallel renewals would knock each other out. Whoever comes
   * second just uses the session the first one got.
   */
  private async renew(failedSession: string): Promise<string> {
    while (this.renewing) {
      try {
        await this.renewing;
      } catch {
        /* the caller that started it reports the error */
      }
    }
    const current = this.store.load();
    if (!current) throw new SignInRequiredError();
    if (current.sessionId && current.sessionId !== failedSession) return current.sessionId;
    this.renewing = this.refresh(current);
    try {
      return await this.renewing;
    } finally {
      this.renewing = null;
    }
  }

  private refresh(saved: TvCredentials): Promise<string> {
    return this.login({ refreshToken: saved.refreshToken, deviceId: saved.deviceId });
  }

  /** The platform's own sign-in, with an Okta access token (first time) or the saved refresh token. Returns the session id. */
  private async login(opts: { accessToken?: string; refreshToken?: string; deviceId?: string; oktaRefreshToken?: string }): Promise<string> {
    const device = opts.deviceId ?? this.store.load()?.deviceId ?? this.store.deviceId();
    const body: Json = {
      cc: {
        clientCredentials: { StbMacAddress: device },
        appSettings: { appName: this.config.appName, siteID: this.config.siteId }
      }
    };
    if (opts.accessToken) body.AccessToken = opts.accessToken;
    if (opts.refreshToken) body.RefreshToken = opts.refreshToken;
    let response: Json;
    try {
      response = JSON.parse(await this.http("POST", `${this.config.apiBase}/api/client/login/okta`, { json: body }));
    } catch (e) {
      if (e instanceof HttpError) throw new Error(`Couldn't sign in to the TV service (${e.status}).`);
      throw e;
    }
    const settings: Json[] = response.appSettings?.settings ?? [];
    const kv = (key: string) => settings.find((s) => s?.key === key)?.value as string | undefined;
    // Every reply carries a status; "OK" (with "You have successfully logged in…") is success.
    const status = kv("errorCode");
    const errorCode = status && status.toUpperCase() !== "OK" ? status : undefined;
    if (errorCode) {
      if (opts.refreshToken && /REFRESH_TOKEN/i.test(errorCode)) {
        // Only a refresh token the server really rejects signs you out.
        this.signOut();
        throw new SignInRequiredError("Your Fizz sign-in expired. Please sign in again.");
      }
      throw new Error(kv("errorMessage") ?? errorCode);
    }
    const creds: Json = response.clientCredentials ?? {};
    const session: string | undefined = creds.sessionID;
    if (!session) throw new Error("The TV service didn't start a session.");
    // Like the Fizz app: the first sign-in keeps Okta's own refresh token; renewals may hand back a new one.
    const refreshToken: string | undefined = response.RefreshToken || creds.RefreshToken || opts.refreshToken || opts.oktaRefreshToken;
    if (!refreshToken) throw new Error("The TV service didn't return a saved login.");
    const email: string | null = creds.Email || creds.UserLogin || this.store.load()?.email || null;
    this.store.save({ refreshToken, sessionId: session, email, deviceId: device });
    return session;
  }

  // ---- HTTP ----

  private async http(method: string, url: string, opts: { json?: unknown; form?: string; headers?: Record<string, string> } = {}): Promise<string> {
    const headers: Record<string, string> = { Accept: "application/json", ...opts.headers };
    let body: string | undefined;
    if (opts.json !== undefined) {
      headers["Content-Type"] = "application/json; charset=utf-8";
      body = JSON.stringify(opts.json);
    } else if (opts.form !== undefined) {
      headers["Content-Type"] = "application/x-www-form-urlencoded";
      body = opts.form;
    }
    const res = await fetch(url, { method, headers, body, signal: AbortSignal.timeout(30_000) });
    const text = await res.text();
    if (res.status >= 400) throw new HttpError(res.status, text);
    return text;
  }

  /** Okta replies with JSON on errors too, so those are returned, not thrown. */
  private async postForm(url: string, fields: Record<string, string>): Promise<Json> {
    try {
      return JSON.parse(await this.http("POST", url, { form: new URLSearchParams(fields).toString() }));
    } catch (e) {
      if (e instanceof HttpError) {
        try {
          return JSON.parse(e.body);
        } catch {
          throw new Error(`Sign-in service error (${e.status}).`);
        }
      }
      throw e;
    }
  }

  private imageUrl(images: Json[] | undefined, preferredTypes: number[]): string | null {
    if (!Array.isArray(images)) return null;
    for (const type of preferredTypes) {
      const pick = images.find((i) => Number(i?.Type) === type);
      if (pick) return `${this.config.imageBase}?id=${pick.Id}&t=${pick.Type}`;
    }
    return null;
  }
}

/** ISO time; one without a zone is UTC (the guide is asked for in UTC). */
export function parseTime(text: unknown): number {
  if (typeof text !== "string" || !text) return NaN;
  const zoned = /(Z|[+-]\d\d:?\d\d)$/i.test(text);
  return Date.parse(zoned ? text : `${text}Z`);
}

function toInt(v: unknown): number | null {
  if (v === null || v === undefined || v === "") return null;
  const n = Number(v);
  return Number.isFinite(n) ? Math.trunc(n) : null;
}
