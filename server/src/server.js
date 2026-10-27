import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createAccounts } from "./accounts.js";
import { createAdmin } from "./admin.js";
import { createGlasses } from "./glasses.js";
import { AUDIO_TYPES, probeAudio } from "./audio.js";
import { buildTrack, mergeRoute } from "./content.js";
import { npcErrorStatus } from "./npc.js";
import { createPlayers } from "./players.js";
import { createStatic } from "./static.js";

const MAX_BODY = 64 * 1024;
const MAX_BLOB = 40 * 1024 * 1024;
const HERE = path.dirname(fileURLToPath(import.meta.url));
// Pages: the admin page, the players' account page and the web player.
const PAGES = { "/admin": path.resolve(HERE, "../admin"), "/account": path.resolve(HERE, "../account"), "/play": path.resolve(HERE, "../play") };
/** The app's bundled music and mission, which the web player plays too. */
export const DEFAULT_ASSETS_DIR = path.resolve(HERE, "../../app/src/main/assets");
// Reads anyone may make: what a web player needs before (or without) signing in. Premium stems
// stay behind an account (checked where they're served).
const PUBLIC_READ = /^\/v1\/(mission|broadcasts|routes\/[\w-]+|tracks\/[\w-]+|blobs\/[0-9a-f]{64}\.(ogg|wav|flac))$/;

/** Tiny per-IP token bucket so one phone (or a leaked token) can't run up the model bill. */
function limiter(perMinute) {
  const buckets = new Map();
  return (key) => {
    const now = Date.now();
    const b = buckets.get(key) ?? { tokens: perMinute, at: now };
    b.tokens = Math.min(perMinute, b.tokens + ((now - b.at) / 60_000) * perMinute);
    b.at = now;
    if (b.tokens < 1) {
      buckets.set(key, b);
      return false;
    }
    b.tokens -= 1;
    buckets.set(key, b);
    return true;
  };
}

const LOOPBACK = new Set(["127.0.0.1", "::1", "::ffff:127.0.0.1"]);

/**
 * The address a request came from, for the per-address limits. Behind a reverse proxy on the same
 * host (TRUST_PROXY, e.g. Caddy on the VPS) every request arrives from loopback, so the client is the
 * last address the proxy added to X-Forwarded-For; anything before it was sent by the client and can
 * be made up. Requests that reach the port directly keep their own address.
 */
export function clientIp(req, trustProxy = false) {
  const socket = req.socket.remoteAddress ?? "?";
  if (!trustProxy || !LOOPBACK.has(socket)) return socket;
  const hops = String(req.headers["x-forwarded-for"] ?? "").split(",").map((s) => s.trim()).filter(Boolean);
  return hops.at(-1) ?? socket;
}

function send(res, status, body, type = "application/json") {
  const payload = Buffer.isBuffer(body) ? body : Buffer.from(typeof body === "string" ? body : JSON.stringify(body));
  res.writeHead(status, { "content-type": type, "content-length": payload.length, "cache-control": "no-store" });
  res.end(payload);
}

async function readBody(req, max) {
  if (Number(req.headers["content-length"] ?? 0) > max) throw Object.assign(new Error("body too large"), { status: 413 });
  let size = 0;
  const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > max) throw Object.assign(new Error("body too large"), { status: 413 });
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

async function readJson(req) {
  const body = await readBody(req, MAX_BODY);
  try {
    return JSON.parse(body.toString("utf8") || "{}");
  } catch {
    throw Object.assign(new Error("invalid JSON"), { status: 400 });
  }
}

function bearer(req) {
  const h = req.headers.authorization ?? "";
  return h.startsWith("Bearer ") ? h.slice(7) : "";
}

/**
 * Routes:
 *   GET    /health                          liveness and which features are configured
 *   POST   /v1/npc                          a character's reply             (event token)
 *   POST   /v1/tts                          a line in a character's voice   (event token)
 *   GET    /v1/routes/:missionId            shared venue route and names    (event token)
 *   PUT    /v1/routes/:missionId            change placements or names      (admin)
 *   GET    /v1/broadcasts?since=ms          live operator messages          (event token)
 *   POST   /v1/broadcasts                   send a live message             (admin)
 *   GET    /v1/tracks/:missionId            published deployed soundtracks  (event token; ?all=1 for admins)
 *   PUT    /v1/tracks/:missionId/:trackId   deploy or update a soundtrack   (admin)
 *   DELETE /v1/tracks/:missionId/:trackId   take a soundtrack down          (admin)
 *   PUT    /v1/blobs?name=file.ogg          upload a stem (raw body)        (admin)
 *   GET    /v1/blobs/:sha256.:ext           download a stem                 (event token)
 *   POST   /v1/admin/login                  admin account, operator token or DataRail admin -> session
 *   GET    /v1/admin/me, POST /v1/admin/logout, GET /v1/admin/mission        (admin)
 *   /v1/account/*, /v1/billing/*, /v1/admin/accounts|unclaimed|settings      see players.js
 *   GET    /v1/mission                      the mission the server runs, for the web player
 *   GET    /admin, /account, /play          admin page, account page, web player (+ /play/assets)
 *
 * Reads of the mission, routes, published tracks, free stems and broadcasts are public; the rest
 * of /v1 needs the event token, an admin, or a signed-in player (X-Account-Token).
 *
 * "Admin" means the operator token or an admin session from /v1/admin/login. Premium soundtracks
 * and their stems go only to admins and to players whose X-Account-Token is a premium account.
 */
export function createServer({
  mission,
  npc,
  tts,
  store,
  eventToken = "",
  operatorToken = "",
  accounts,
  admin,
  players,
  billing = {},
  assetsDir = DEFAULT_ASSETS_DIR,
  trustProxy = false,
  log = console,
}) {
  const serveStatic = createStatic({ pages: PAGES, assetsDir });
  accounts ??= createAccounts({ dir: store.dir, adminEmails: billing.adminEmails ?? [] });
  admin ??= createAdmin({ operatorToken, accounts });
  // Linked Meta glasses are a way to premium, once an admin approves the pair (see glasses.js).
  const glasses = createGlasses({ store, accounts });
  accounts.setExtraPremium((id) => glasses.premiumFor(id));
  players ??= createPlayers({
    accounts,
    glasses,
    store,
    defaults: billing.defaults,
    secrets: billing.secrets,
    limits: billing.limits,
    log,
    premiumTracks: () =>
      store.getCatalog(mission.id).soundtracks.filter((t) => t.published && t.premium).map((t) => ({ id: t.id, title: t.title, description: t.description })),
  });
  const npcLimit = limiter(30);
  const ttsLimit = limiter(120);
  // Anyone can sign up, so a player without the event token gets a tighter allowance of the paid
  // services (character replies and voices) on top of the per-address one.
  const accountNpcLimit = limiter(12);
  const accountTtsLimit = limiter(60);
  const playerOnly = (req) => (!eventToken || bearer(req) === eventToken || isAdmin(req) ? null : players.accountFor(req));
  const loginLimit = limiter(10);

  const isOperator = (req) => Boolean(operatorToken) && bearer(req) === operatorToken;
  const isAdmin = (req) => isOperator(req) || admin.session(bearer(req)) !== null;
  // A signed-in player counts as an event phone: the web player has no event token to send.
  const isEvent = (req) => !eventToken || bearer(req) === eventToken || isAdmin(req) || players.accountFor(req) !== null;

  async function handle(req, res) {
    const url = new URL(req.url, "http://localhost");
    const ip = clientIp(req, trustProxy);
    const route = `${req.method} ${url.pathname}`;

    if (route === "GET /health") {
      return send(res, 200, {
        ok: true,
        mission: mission.id,
        npc: npc.enabled,
        tts: tts.enabled,
        model: npc.model,
        admin: { operatorToken: Boolean(operatorToken), datarail: admin.datarail, accounts: true },
        payments: players.health(),
      });
    }

    if (serveStatic(req, res, url.pathname)) return;
    if (url.pathname === "/") {
      res.writeHead(302, { location: "/play/" });
      return res.end();
    }

    if (route === "POST /v1/admin/login") {
      if (!loginLimit(ip)) return send(res, 429, { error: "too many attempts; wait a minute" });
      return send(res, 200, await admin.login(await readJson(req)));
    }

    // Accounts and payment webhooks bring their own credentials, so they skip the event token.
    const player = await players.handle(req, url, ip, { readJson, readBody });
    if (player) return send(res, player[0], player[1]);

    const publicRead = req.method === "GET" && PUBLIC_READ.test(url.pathname);
    if (url.pathname.startsWith("/v1/") && !publicRead && !isEvent(req)) return send(res, 401, { error: "bad event token" });

    if (route === "GET /v1/mission") return send(res, 200, mission);

    if (url.pathname.startsWith("/v1/admin/")) {
      if (!isAdmin(req)) return send(res, 403, { error: "sign in as an admin" });
      const handled = await players.handleAdmin(req, url, admin.session(bearer(req))?.who ?? null, { readJson });
      if (handled) return send(res, handled[0], handled[1]);
      if (route === "GET /v1/admin/me") {
        return send(res, 200, { who: admin.session(bearer(req))?.who ?? { display: "Operator token", via: "operator-token" } });
      }
      if (route === "POST /v1/admin/logout") {
        admin.close(bearer(req));
        return send(res, 204, "");
      }
      if (route === "GET /v1/admin/mission") {
        return send(res, 200, {
          id: mission.id,
          title: mission.title,
          music: { title: mission.music.title, bpm: mission.music.bpm, beatsPerBar: mission.music.beatsPerBar ?? 4, loopBars: mission.music.loopBars ?? 8 },
          signals: mission.signals.map((s) => ({
            id: s.id, name: s.name, place: s.place ?? "", stem: s.stem, location: s.location, radiusM: s.radiusM ?? 45, lockRadiusM: s.lockRadiusM ?? 28,
          })),
          bundledSoundtracks: (mission.soundtracks ?? []).map((t) => ({ id: t.id, title: t.title, unlockAfterCompletions: t.unlockAfterCompletions ?? 1 })),
        });
      }
    }

    if (route === "POST /v1/npc") {
      const player = playerOnly(req);
      if (!npcLimit(ip) || (player && !accountNpcLimit(player.id))) return send(res, 429, { error: "slow down" });
      const body = await readJson(req);
      if (typeof body.characterId !== "string" || typeof body.utterance !== "string" || !body.utterance.trim()) {
        return send(res, 400, { error: "characterId and utterance are required" });
      }
      try {
        const reply = await npc.reply(mission, body);
        return send(res, 200, { reply, characterId: body.characterId });
      } catch (e) {
        log.warn?.(`npc failed: ${e.message}`);
        return send(res, npcErrorStatus(e), { error: e.message });
      }
    }

    if (route === "POST /v1/tts") {
      const player = playerOnly(req);
      if (!ttsLimit(ip) || (player && !accountTtsLimit(player.id))) return send(res, 429, { error: "slow down" });
      const body = await readJson(req);
      const text = typeof body.text === "string" ? body.text.trim().slice(0, 500) : "";
      if (typeof body.characterId !== "string" || !text) return send(res, 400, { error: "characterId and text are required" });
      try {
        return send(res, 200, await tts.render(mission, body.characterId, text), "audio/wav");
      } catch (e) {
        return send(res, e.status ?? 502, { error: e.message });
      }
    }

    const routeMatch = url.pathname.match(/^\/v1\/routes\/([\w-]+)$/);
    if (routeMatch) {
      const missionId = routeMatch[1];
      if (req.method === "GET") {
        const r = store.getRoute(missionId);
        return r ? send(res, 200, r) : send(res, 404, { error: "no route" });
      }
      if (req.method === "PUT") {
        if (!isAdmin(req)) return send(res, 403, { error: "operator token required" });
        if (missionId !== mission.id) return send(res, 404, { error: "unknown mission" });
        const merged = mergeRoute(mission, store.getRoute(missionId), await readJson(req));
        store.putRoute(missionId, { ...merged, updatedAtMs: Date.now() });
        return send(res, 204, "");
      }
    }

    const tracksMatch = url.pathname.match(/^\/v1\/tracks\/([\w-]+)(?:\/([\w-]+))?$/);
    if (tracksMatch) {
      const [, missionId, trackId] = tracksMatch;
      if (!trackId && req.method === "GET") {
        const c = store.getCatalog(missionId);
        if (url.searchParams.get("all") === "1" && isAdmin(req)) return send(res, 200, { ...c, locked: [] });
        const premium = isAdmin(req) || players.isPremium(req);
        const published = c.soundtracks.filter((t) => t.published);
        return send(res, 200, {
          ...c,
          soundtracks: published.filter((t) => premium || !t.premium),
          // What a free player could have, so the app can show it and offer premium.
          locked: premium ? [] : published.filter((t) => t.premium).map((t) => ({ id: t.id, title: t.title, description: t.description ?? "" })),
        });
      }
      if (trackId && (req.method === "PUT" || req.method === "DELETE")) {
        if (!isAdmin(req)) return send(res, 403, { error: "sign in as an admin" });
        if (missionId !== mission.id) return send(res, 404, { error: "unknown mission" });
        if (req.method === "DELETE") {
          return store.deleteTrack(missionId, trackId) ? send(res, 204, "") : send(res, 404, { error: "no such track" });
        }
        const { track, warnings } = buildTrack(mission, trackId, await readJson(req), (name) => store.blob(name));
        return send(res, 200, { track: store.putTrack(missionId, track), warnings });
      }
    }

    if (route === "PUT /v1/blobs") {
      if (!isAdmin(req)) return send(res, 403, { error: "sign in as an admin" });
      const bytes = await readBody(req, MAX_BLOB);
      const info = probeAudio(bytes);
      const original = (url.searchParams.get("name") ?? "").slice(0, 120);
      return send(res, 201, store.putBlob(bytes, info.format, { ...info, originalName: original }));
    }

    const blobMatch = url.pathname.match(/^\/v1\/blobs\/([0-9a-f]{64}\.(?:ogg|wav|flac))$/);
    if (blobMatch && req.method === "GET") {
      const blob = store.blob(blobMatch[1]);
      if (!blob) return send(res, 404, { error: "no such file" });
      const free = store
        .getCatalog(mission.id)
        .soundtracks.some((t) => t.published && !t.premium && t.music.stems.some((s) => s.file === `server:${blobMatch[1]}`));
      if (!free && !isAdmin(req) && !players.isPremium(req)) return send(res, 403, { error: "this stem belongs to a premium soundtrack" });
      const size = fs.statSync(blob.file).size;
      res.writeHead(200, {
        "content-type": AUDIO_TYPES[blobMatch[1].split(".").pop()],
        "content-length": size,
        // Named by its own checksum, so it can never change.
        "cache-control": "private, max-age=31536000, immutable",
      });
      return fs.createReadStream(blob.file).pipe(res);
    }

    if (route === "GET /v1/broadcasts") {
      const since = Number(url.searchParams.get("since") ?? 0) || 0;
      return send(res, 200, { broadcasts: store.broadcastsSince(since) });
    }

    if (route === "POST /v1/broadcasts") {
      if (!isAdmin(req)) return send(res, 403, { error: "operator token required" });
      const body = await readJson(req);
      const text = typeof body.text === "string" ? body.text.trim().slice(0, 400) : "";
      const speaker = typeof body.speaker === "string" ? body.speaker : "";
      if (!text || !mission.characters.some((c) => c.id === speaker)) return send(res, 400, { error: "speaker and text are required" });
      return send(res, 201, store.addBroadcast(speaker, text));
    }

    return send(res, 404, { error: "not found" });
  }

  return http.createServer((req, res) => {
    handle(req, res).catch((e) => {
      if (!e.status) log.error?.(e);
      if (!res.headersSent) send(res, e.status ?? 500, { error: e.status ? e.message : "internal error" });
    });
  });
}
