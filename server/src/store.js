import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

// File-backed persistence for everything the server keeps between restarts:
// venue routes, deployed soundtrack catalogs, uploaded stems, and recent
// live broadcasts. In production DATA_DIR must point at persistent storage;
// phones keep local copies of routes and downloaded stems, but the catalogs
// and broadcast history live only here.
//
// Uploaded stems are content-addressed (`blobs/<sha256>.<ext>`): a stem can
// never change under a phone that already downloaded it, and re-uploading
// identical bytes costs one hash and no disk write.

const BROADCAST_HISTORY = 100;
const BLOB_NAME_PATTERN = /^[0-9a-f]{64}\.(ogg|wav|flac)$/;

export function createStore(dir = process.env.DATA_DIR || "data") {
  const routesDir = path.join(dir, "routes");
  const tracksDir = path.join(dir, "tracks");
  const blobsDir = path.join(dir, "blobs");
  const broadcastsPath = path.join(dir, "broadcasts.json");
  for (const sub of [routesDir, tracksDir, blobsDir]) {
    fs.mkdirSync(sub, { recursive: true });
  }

  // Mission ids come from URLs; keep them safe as file names.
  const fileKey = (id) => String(id).replace(/[^a-zA-Z0-9_-]/g, "_");

  function readJson(file, fallback) {
    try {
      return JSON.parse(fs.readFileSync(file, "utf8"));
    } catch {
      return fallback;
    }
  }

  // Write to a sibling temp file then rename over the target, so a crash
  // mid-write can never leave a truncated document behind.
  function writeJson(file, value) {
    const temp = `${file}.${process.pid}.tmp`;
    fs.writeFileSync(temp, JSON.stringify(value, null, 2));
    fs.renameSync(temp, file);
  }

  const catalogPath = (missionId) => path.join(tracksDir, `${fileKey(missionId)}.json`);
  const routePath = (missionId) => path.join(routesDir, `${fileKey(missionId)}.json`);

  let broadcasts = readJson(broadcastsPath, []);
  if (!Array.isArray(broadcasts)) broadcasts = [];

  return {
    dir,

    /** Reads any small JSON document in the data folder by file name. */
    read(name, fallback) {
      return readJson(path.join(dir, name), fallback);
    },
    write(name, value) {
      writeJson(path.join(dir, name), value);
    },

    getRoute(missionId) {
      return readJson(routePath(missionId), null);
    },
    putRoute(missionId, route) {
      writeJson(routePath(missionId), route);
    },

    addBroadcast(speaker, text) {
      const broadcast = {
        id: crypto.randomUUID(),
        speaker,
        text,
        createdAtMs: Date.now(),
      };
      broadcasts = [...broadcasts, broadcast].slice(-BROADCAST_HISTORY);
      fs.writeFileSync(broadcastsPath, JSON.stringify(broadcasts));
      return broadcast;
    },
    broadcastsSince(sinceMs) {
      return broadcasts.filter((entry) => entry.createdAtMs > sinceMs);
    },

    /** Stores an uploaded stem by content; returns its recorded metadata. */
    putBlob(bytes, ext, info) {
      const sha256 = crypto.createHash("sha256").update(bytes).digest("hex");
      const name = `${sha256}.${ext}`;
      const file = path.join(blobsDir, name);
      if (!fs.existsSync(file)) {
        const temp = `${file}.${process.pid}.tmp`;
        fs.writeFileSync(temp, bytes);
        fs.renameSync(temp, file);
      }
      const metadata = { name, sha256, bytes: bytes.length, ...info, uploadedAtMs: Date.now() };
      writeJson(`${file}.json`, metadata);
      return metadata;
    },

    /** Metadata plus path for a stored stem, or null if there is none. */
    blob(name) {
      if (!BLOB_NAME_PATTERN.test(name)) return null;
      const file = path.join(blobsDir, name);
      if (!fs.existsSync(file)) return null;
      return { ...readJson(`${file}.json`, {}), file };
    },

    getCatalog(missionId) {
      return readJson(catalogPath(missionId), { missionId, soundtracks: [], updatedAtMs: 0 });
    },
    putTrack(missionId, track) {
      const catalog = this.getCatalog(missionId);
      const now = Date.now();
      const storedTrack = { ...track, updatedAtMs: now };
      const existing = catalog.soundtracks.findIndex((entry) => entry.id === track.id);
      if (existing >= 0) catalog.soundtracks[existing] = storedTrack;
      else catalog.soundtracks.push(storedTrack);
      writeJson(catalogPath(missionId), { ...catalog, missionId, updatedAtMs: now });
      return storedTrack;
    },
    deleteTrack(missionId, trackId) {
      const catalog = this.getCatalog(missionId);
      const remaining = catalog.soundtracks.filter((entry) => entry.id !== trackId);
      if (remaining.length === catalog.soundtracks.length) return false;
      writeJson(catalogPath(missionId), { ...catalog, soundtracks: remaining, updatedAtMs: Date.now() });
      return true;
    },
  };
}
