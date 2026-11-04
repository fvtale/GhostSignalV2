import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createAccounts } from "./accounts.js";
import { createAdmin } from "./admin.js";
import { AUDIO_TYPES, probeAudio } from "./audio.js";
import { buildTrack, mergeRoute } from "./content.js";
import { createGlasses } from "./glasses.js";
import { missionRegistry, missionSummaries } from "./mission.js";
import { npcErrorStatus } from "./npc.js";
import { createPlayers } from "./players.js";
import { createStatic } from "./static.js";
import { createStt } from "./stt.js";

// HTTP surface. Route summary (auth in parentheses):
//   GET    /health                          liveness + configured features
//   POST   /v1/npc                          character reply        (event)
//   POST   /v1/tts                          retired, answers 501   (event)
//   POST   /v1/stt                          speech clip -> text    (event)
//   GET    /v1/routes/:missionId            venue route + names    (public)
//   PUT    /v1/routes/:missionId            edit route             (admin)
//   GET    /v1/broadcasts?since=ms          operator messages      (public)
//   POST   /v1/broadcasts                   send message           (admin)
//   GET    /v1/tracks/:missionId            deployed soundtracks   (public)
//   PUT|DELETE /v1/tracks/:missionId/:id    manage soundtrack      (admin)
//   PUT    /v1/blobs?name=file.ogg          upload a stem          (admin)
//   GET    /v1/blobs/:sha256.:ext           download a stem        (gated)
//   POST   /v1/admin/login                  admin sign-in
//   GET    /v1/admin/me, POST /v1/admin/logout, GET /v1/admin/mission (admin)
//   /v1/account/*, /v1/billing/*, /v1/admin/accounts|settings|glasses (players.js)
//   GET    /v1/missions                     mission picker list      (public)
//   GET    /v1/mission[?missionId=]         mission JSON for the web player
//   GET    /admin, /account, /play          the three browser surfaces
//
// "Event" auth is the shared event token, an admin session, or any
// signed-in player (the web player has no event token to send). "Admin" is
// the operator token or an admin session. Premium soundtrack stems are
// served only to admins and premium players.

const MAX_JSON_BODY = 64 * 1024;
const MAX_BLOB_BYTES = 40 * 1024 * 1024;
const MAX_STT_BYTES = 20 * 1024 * 1024; // ~10 minutes of 16 kHz mono speech.
const MODULE_DIR = path.dirname(fileURLToPath(import.meta.url));

const PAGE_DIRS = {
  "/admin": path.resolve(MODULE_DIR, "../admin"),
  "/account": path.resolve(MODULE_DIR, "../account"),
  "/play": path.resolve(MODULE_DIR, "../play"),
};

/** Domain-root landing page (signature circuitry), served at `/`. */
const LANDING_DIR = path.resolve(MODULE_DIR, "../landing");

/** Sideloadable Android builds, served at `/download/ghostsignal.apk` (binaries stay out of git). */
const DOWNLOADS_DIR = path.resolve(MODULE_DIR, "../downloads");

/** The app's bundled mission/music folder, shared with the web player. */
export const DEFAULT_ASSETS_DIR = path.resolve(MODULE_DIR, "../../app/src/main/assets");

// Reads any visitor may make before (or without) signing in.
const PUBLIC_READ_PATTERN =
  /^\/v1\/(missions?|broadcasts|routes\/[\w-]+|tracks\/[\w-]+|blobs\/[0-9a-f]{64}\.(ogg|wav|flac))$/;

const LOOPBACK_ADDRESSES = new Set(["127.0.0.1", "::1", "::ffff:127.0.0.1"]);

function httpError(status, message) {
  return Object.assign(new Error(message), { status });
}

/**
 * Per-key token-bucket rate limiter ([perMinute] sustained). Buckets idle
 * for a full minute have fully refilled and become indistinguishable from
 * fresh ones, so once the map grows they are swept; otherwise a long-lived
 * server remembers every address it has ever limited.
 */
export function limiter(perMinute, nowMs = Date.now) {
  const buckets = new Map();

  function sweepExpired(now) {
    if (buckets.size < 128) return;
    for (const [key, bucket] of buckets) {
      if (now - bucket.updatedAt >= 60_000) buckets.delete(key);
    }
  }

  function allow(key) {
    const now = nowMs();
    sweepExpired(now);
    const bucket = buckets.get(key) ?? { tokens: perMinute, updatedAt: now };
    const elapsedMinutes = (now - bucket.updatedAt) / 60_000;
    bucket.tokens = Math.min(perMinute, bucket.tokens + elapsedMinutes * perMinute);
    bucket.updatedAt = now;
    if (bucket.tokens < 1) {
      buckets.set(key, bucket);
      return false;
    }
    bucket.tokens -= 1;
    buckets.set(key, bucket);
    return true;
  }

  // Exposed for tests and diagnostics.
  allow.size = () => buckets.size;
  return allow;
}

/**
 * The client address for rate limiting. Behind a same-host reverse proxy
 * (TRUST_PROXY, e.g. Caddy) every request arrives from loopback, so the
 * client is the LAST X-Forwarded-For entry — the one the proxy itself
 * appended; anything earlier was supplied by the client and may be forged.
 * Direct requests keep their socket address.
 */
export function clientIp(req, trustProxy = false) {
  const socketAddress = req.socket.remoteAddress ?? "?";
  if (!trustProxy || !LOOPBACK_ADDRESSES.has(socketAddress)) return socketAddress;
  const hops = String(req.headers["x-forwarded-for"] ?? "")
    .split(",")
    .map((part) => part.trim())
    .filter(Boolean);
  return hops.at(-1) ?? socketAddress;
}

function respond(res, status, body, contentType = "application/json") {
  const payload = Buffer.isBuffer(body)
    ? body
    : Buffer.from(typeof body === "string" ? body : JSON.stringify(body));
  res.writeHead(status, {
    "content-type": contentType,
    "content-length": payload.length,
    "cache-control": "no-store",
  });
  res.end(payload);
}

async function readBody(req, maxBytes) {
  const declared = Number(req.headers["content-length"] ?? 0);
  if (declared > maxBytes) throw httpError(413, "body too large");
  let received = 0;
  const chunks = [];
  for await (const chunk of req) {
    received += chunk.length;
    if (received > maxBytes) throw httpError(413, "body too large");
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

async function readJsonBody(req) {
  const raw = await readBody(req, MAX_JSON_BODY);
  try {
    return JSON.parse(raw.toString("utf8") || "{}");
  } catch {
    throw httpError(400, "invalid JSON");
  }
}

function bearerToken(req) {
  const header = req.headers.authorization ?? "";
  return header.startsWith("Bearer ") ? header.slice(7) : "";
}

export function createServer({
  mission,
  missions,
  defaultMissionId,
  npc,
  tts,
  store,
  eventToken = "",
  operatorToken = "",
  accounts,
  admin,
  stt,
  players,
  billing = {},
  assetsDir = DEFAULT_ASSETS_DIR,
  trustProxy = false,
  log = console,
}) {
  const registry = missionRegistry({ mission, missions, defaultMissionId });
  const missionById = registry.missions;
  mission = registry.defaultMission ?? mission;
  const fallbackMissionId = registry.defaultMissionId ?? mission?.id;

  // Mission selection: an explicit id may arrive as ?missionId= / ?mission=
  // or, on POSTs, as a missionId / mission field in the JSON body. No id
  // means the default mission; an unknown id resolves to null (-> 404).
  const requestedMissionId = (url, body) => {
    const fromBody = body && typeof body === "object"
      ? (body.missionId ?? body.mission)
      : undefined;
    const candidate = (typeof fromBody === "string" && fromBody)
      || url.searchParams.get("missionId")
      || url.searchParams.get("mission")
      || undefined;
    return candidate || undefined;
  };
  const resolveMission = (url, body) => {
    const id = requestedMissionId(url, body);
    if (!id) return mission;
    return missionById.get(id) ?? null;
  };

  const serveStaticFiles = createStatic({ pages: PAGE_DIRS, assetsDir, landingDir: LANDING_DIR, downloadsDir: DOWNLOADS_DIR });
  accounts ??= createAccounts({ dir: store.dir, adminEmails: billing.adminEmails ?? [] });
  stt ??= createStt({ log });
  admin ??= createAdmin({ operatorToken, accounts });

  // Approved glasses are an alternate route to premium for their account.
  const glasses = createGlasses({ store, accounts });
  accounts.setExtraPremium((accountId) => glasses.premiumFor(accountId));

  players ??= createPlayers({
    accounts,
    glasses,
    store,
    defaults: billing.defaults,
    secrets: billing.secrets,
    limits: billing.limits,
    log,
    premiumTracks: () => [...missionById.keys()].flatMap((id) => store.getCatalog(id).soundtracks)
      .filter((track) => track.published && track.premium)
      .map((track) => ({ id: track.id, title: track.title, description: track.description })),
  });

  // Paid-model endpoints are limited per address, and harder for callers
  // who arrive only as a signed-in player (anyone can create an account).
  const npcPerAddress = limiter(30);
  const ttsPerAddress = limiter(120);
  const sttPerAddress = limiter(60);
  const npcPerAccount = limiter(12);
  const ttsPerAccount = limiter(60);
  const loginPerAddress = limiter(10);

  const hasOperatorToken = (req) => Boolean(operatorToken) && bearerToken(req) === operatorToken;
  const isAdmin = (req) => hasOperatorToken(req) || admin.session(bearerToken(req)) !== null;
  const isEventCaller = (req) =>
    !eventToken || bearerToken(req) === eventToken || isAdmin(req) || players.accountFor(req) !== null;
  // Returns the account when the caller is ONLY a signed-in player (no
  // event token, not an admin); null when they carry stronger credentials.
  const playerOnlyAccount = (req) =>
    (!eventToken || bearerToken(req) === eventToken || isAdmin(req)) ? null : players.accountFor(req);

  async function handleRequest(req, res) {
    const url = new URL(req.url, "http://localhost");
    const ip = clientIp(req, trustProxy);
    const routeKey = `${req.method} ${url.pathname}`;

    if (routeKey === "GET /health") {
      return respond(res, 200, {
        ok: true,
        mission: mission.id,
        npc: npc.enabled,
        tts: tts.enabled,
        stt: stt.enabled,
        model: npc.model,
        admin: { operatorToken: Boolean(operatorToken), datarail: admin.datarail, accounts: true },
        payments: players.health(),
      });
    }

    if (serveStaticFiles(req, res, url.pathname)) return;

    if (routeKey === "POST /v1/admin/login") {
      if (!loginPerAddress(ip)) {
        return respond(res, 429, { error: "too many attempts; wait a minute" });
      }
      return respond(res, 200, await admin.login(await readJsonBody(req)));
    }

    // Account and webhook routes carry their own credentials.
    const playerResult = await players.handle(req, url, ip, { readJson: readJsonBody, readBody });
    if (playerResult) return respond(res, playerResult[0], playerResult[1]);

    const isPublicRead = req.method === "GET" && PUBLIC_READ_PATTERN.test(url.pathname);
    if (url.pathname.startsWith("/v1/") && !isPublicRead && !isEventCaller(req)) {
      return respond(res, 401, { error: "bad event token" });
    }

    if (routeKey === "GET /v1/missions") {
      return respond(res, 200, {
        missions: missionSummaries(missionById, fallbackMissionId),
        defaultMissionId: fallbackMissionId,
      });
    }

    if (routeKey === "GET /v1/mission") {
      const selected = resolveMission(url);
      if (!selected) return respond(res, 404, { error: "unknown mission" });
      return respond(res, 200, selected);
    }

    if (url.pathname.startsWith("/v1/admin/")) {
      if (!isAdmin(req)) return respond(res, 403, { error: "sign in as an admin" });
      const adminResult = await players.handleAdmin(
        req, url, admin.session(bearerToken(req))?.who ?? null, { readJson: readJsonBody },
      );
      if (adminResult) return respond(res, adminResult[0], adminResult[1]);

      if (routeKey === "GET /v1/admin/me") {
        const who = admin.session(bearerToken(req))?.who ?? { display: "Operator token", via: "operator-token" };
        return respond(res, 200, { who });
      }
      if (routeKey === "POST /v1/admin/logout") {
        admin.close(bearerToken(req));
        return respond(res, 204, "");
      }
      if (routeKey === "GET /v1/admin/mission") {
        const selected = resolveMission(url);
        if (!selected) return respond(res, 404, { error: "unknown mission" });
        return respond(res, 200, {
          id: selected.id,
          title: selected.title,
          music: {
            title: selected.music.title,
            bpm: selected.music.bpm,
            beatsPerBar: selected.music.beatsPerBar ?? 4,
            loopBars: selected.music.loopBars ?? 8,
          },
          signals: selected.signals.map((signal) => ({
            id: signal.id,
            name: signal.name,
            place: signal.place ?? "",
            stem: signal.stem,
            location: signal.location,
            radiusM: signal.radiusM ?? 45,
            lockRadiusM: signal.lockRadiusM ?? 28,
          })),
          bundledSoundtracks: (selected.soundtracks ?? []).map((track) => ({
            id: track.id,
            title: track.title,
            unlockAfterCompletions: track.unlockAfterCompletions ?? 1,
          })),
        });
      }
    }

    if (routeKey === "POST /v1/npc") {
      const player = playerOnlyAccount(req);
      if (!npcPerAddress(ip) || (player && !npcPerAccount(player.id))) {
        return respond(res, 429, { error: "slow down" });
      }
      const body = await readJsonBody(req);
      const valid = typeof body.characterId === "string" &&
        typeof body.utterance === "string" && body.utterance.trim() !== "";
      if (!valid) return respond(res, 400, { error: "characterId and utterance are required" });
      const selected = resolveMission(url, body);
      if (!selected) return respond(res, 404, { error: "unknown mission" });
      try {
        const reply = await npc.reply(selected, body);
        return respond(res, 200, { reply, characterId: body.characterId });
      } catch (error) {
        log.warn?.(`npc failed: ${error.message}`);
        return respond(res, npcErrorStatus(error), { error: error.message });
      }
    }

    if (routeKey === "POST /v1/tts") {
      const player = playerOnlyAccount(req);
      if (!ttsPerAddress(ip) || (player && !ttsPerAccount(player.id))) {
        return respond(res, 429, { error: "slow down" });
      }
      const body = await readJsonBody(req);
      const text = typeof body.text === "string" ? body.text.trim().slice(0, 500) : "";
      if (typeof body.characterId !== "string" || !text) {
        return respond(res, 400, { error: "characterId and text are required" });
      }
      const selected = resolveMission(url, body);
      if (!selected) return respond(res, 404, { error: "unknown mission" });
      try {
        return respond(res, 200, await tts.render(selected, body.characterId, text), "audio/wav");
      } catch (error) {
        return respond(res, error.status ?? 502, { error: error.message });
      }
    }

    if (routeKey === "POST /v1/stt") {
      const player = playerOnlyAccount(req);
      if (!sttPerAddress(ip) || (player && !ttsPerAccount(player.id))) {
        return respond(res, 429, { error: "slow down" });
      }
      const wav = await readBody(req, MAX_STT_BYTES);
      // Expected answers bias recognition toward challenge-solving words.
      const keywords = (url.searchParams.get("keywords") ?? "")
        .split(",").map((word) => word.trim()).filter(Boolean).slice(0, 20);
      try {
        return respond(res, 200, { text: await stt.transcribe(wav, keywords) });
      } catch (error) {
        return respond(res, error.status ?? 502, { error: error.message });
      }
    }

    const routeMatch = url.pathname.match(/^\/v1\/routes\/([\w-]+)$/);
    if (routeMatch) {
      const missionId = routeMatch[1];
      if (req.method === "GET") {
        const storedRoute = store.getRoute(missionId);
        return storedRoute
          ? respond(res, 200, storedRoute)
          : respond(res, 404, { error: "no route" });
      }
      if (req.method === "PUT") {
        if (!isAdmin(req)) return respond(res, 403, { error: "operator token required" });
        const routeMission = missionById.get(missionId);
        if (!routeMission) return respond(res, 404, { error: "unknown mission" });
        const merged = mergeRoute(routeMission, store.getRoute(missionId), await readJsonBody(req));
        store.putRoute(missionId, { ...merged, updatedAtMs: Date.now() });
        return respond(res, 204, "");
      }
    }

    const tracksMatch = url.pathname.match(/^\/v1\/tracks\/([\w-]+)(?:\/([\w-]+))?$/);
    if (tracksMatch) {
      const [, missionId, trackId] = tracksMatch;

      if (!trackId && req.method === "GET") {
        if (!missionById.has(missionId)) return respond(res, 404, { error: "unknown mission" });
        const catalog = store.getCatalog(missionId);
        if (url.searchParams.get("all") === "1" && isAdmin(req)) {
          return respond(res, 200, { ...catalog, locked: [] });
        }
        const canSeePremium = isAdmin(req) || players.isPremium(req);
        const published = catalog.soundtracks.filter((track) => track.published);
        return respond(res, 200, {
          ...catalog,
          soundtracks: published.filter((track) => canSeePremium || !track.premium),
          // Free players see what premium would add, so the app can offer it.
          locked: canSeePremium ? [] : published
            .filter((track) => track.premium)
            .map((track) => ({ id: track.id, title: track.title, description: track.description ?? "" })),
        });
      }

      if (trackId && (req.method === "PUT" || req.method === "DELETE")) {
        if (!isAdmin(req)) return respond(res, 403, { error: "sign in as an admin" });
        const trackMission = missionById.get(missionId);
        if (!trackMission) return respond(res, 404, { error: "unknown mission" });
        if (req.method === "DELETE") {
          return store.deleteTrack(missionId, trackId)
            ? respond(res, 204, "")
            : respond(res, 404, { error: "no such track" });
        }
        const built = buildTrack(trackMission, trackId, await readJsonBody(req), (name) => store.blob(name));
        return respond(res, 200, { track: store.putTrack(missionId, built.track), warnings: built.warnings });
      }
    }

    if (routeKey === "PUT /v1/blobs") {
      if (!isAdmin(req)) return respond(res, 403, { error: "sign in as an admin" });
      const bytes = await readBody(req, MAX_BLOB_BYTES);
      const info = probeAudio(bytes);
      const originalName = (url.searchParams.get("name") ?? "").slice(0, 120);
      return respond(res, 201, store.putBlob(bytes, info.format, { ...info, originalName }));
    }

    const blobMatch = url.pathname.match(/^\/v1\/blobs\/([0-9a-f]{64}\.(?:ogg|wav|flac))$/);
    if (blobMatch && req.method === "GET") {
      const blob = store.blob(blobMatch[1]);
      if (!blob) return respond(res, 404, { error: "no such file" });
      const blobMission = resolveMission(url);
      if (!blobMission) return respond(res, 404, { error: "unknown mission" });
      const catalogIds = requestedMissionId(url) ? [blobMission.id] : [...missionById.keys()];
      const inFreeTrack = catalogIds.some((id) => store.getCatalog(id).soundtracks.some(
        (track) => track.published && !track.premium &&
          track.music.stems.some((stem) => stem.file === `server:${blobMatch[1]}`),
      ));
      if (!inFreeTrack && !isAdmin(req) && !players.isPremium(req)) {
        return respond(res, 403, { error: "this stem belongs to a premium soundtrack" });
      }
      const extension = blobMatch[1].split(".").pop();
      res.writeHead(200, {
        "content-type": AUDIO_TYPES[extension],
        "content-length": fs.statSync(blob.file).size,
        // Content-named, so it can be cached forever.
        "cache-control": "private, max-age=31536000, immutable",
      });
      return fs.createReadStream(blob.file).pipe(res);
    }

    if (routeKey === "GET /v1/broadcasts") {
      const since = Number(url.searchParams.get("since") ?? 0) || 0;
      return respond(res, 200, { broadcasts: store.broadcastsSince(since) });
    }

    if (routeKey === "POST /v1/broadcasts") {
      if (!isAdmin(req)) return respond(res, 403, { error: "operator token required" });
      const body = await readJsonBody(req);
      const selected = resolveMission(url, body);
      if (!selected) return respond(res, 404, { error: "unknown mission" });
      const text = typeof body.text === "string" ? body.text.trim().slice(0, 400) : "";
      const speaker = typeof body.speaker === "string" ? body.speaker : "";
      const speakerKnown = selected.characters.some((character) => character.id === speaker);
      if (!text || !speakerKnown) {
        return respond(res, 400, { error: "speaker and text are required" });
      }
      return respond(res, 201, store.addBroadcast(speaker, text));
    }

    return respond(res, 404, { error: "not found" });
  }

  return http.createServer((req, res) => {
    handleRequest(req, res).catch((error) => {
      if (!error.status) log.error?.(error);
      if (!res.headersSent) {
        respond(res, error.status ?? 500, {
          error: error.status ? error.message : "internal error",
        });
      }
    });
  });
}
