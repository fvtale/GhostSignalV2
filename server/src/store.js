import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

/**
 * Small file store: venue routes per mission, the recent live broadcasts, and soundtracks deployed
 * from the admin page. Point DATA_DIR at a persistent volume in production, or all of it is lost on
 * redeploy (phones keep a local copy of the route and of every stem they downloaded).
 *
 * Uploaded stems are stored by content as `blobs/<sha256>.<ext>`, so a stem never changes under a
 * phone that already has it, and re-uploading the same file costs nothing.
 */
export function createStore(dir = process.env.DATA_DIR || "data") {
  const routesDir = path.join(dir, "routes");
  const tracksDir = path.join(dir, "tracks");
  const blobsDir = path.join(dir, "blobs");
  const broadcastsFile = path.join(dir, "broadcasts.json");
  for (const d of [routesDir, tracksDir, blobsDir]) fs.mkdirSync(d, { recursive: true });
  let broadcasts = [];
  try {
    broadcasts = JSON.parse(fs.readFileSync(broadcastsFile, "utf8"));
  } catch {
    broadcasts = [];
  }

  const safe = (id) => String(id).replace(/[^a-zA-Z0-9_-]/g, "_");
  const readJson = (file, fallback) => {
    try {
      return JSON.parse(fs.readFileSync(file, "utf8"));
    } catch {
      return fallback;
    }
  };
  /** Write-then-rename, so a crash mid-write never leaves half a catalog behind. */
  const writeJson = (file, value) => {
    const tmp = `${file}.${process.pid}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(value, null, 2));
    fs.renameSync(tmp, file);
  };
  const catalogFile = (missionId) => path.join(tracksDir, `${safe(missionId)}.json`);

  return {
    dir,
    /** Any small JSON document in the data folder, by file name. */
    read: (name, fallback) => readJson(path.join(dir, name), fallback),
    write: (name, value) => writeJson(path.join(dir, name), value),

    getRoute(missionId) {
      return readJson(path.join(routesDir, `${safe(missionId)}.json`), null);
    },
    putRoute(missionId, route) {
      writeJson(path.join(routesDir, `${safe(missionId)}.json`), route);
    },

    addBroadcast(speaker, text) {
      const b = { id: crypto.randomUUID(), speaker, text, createdAtMs: Date.now() };
      broadcasts = [...broadcasts, b].slice(-100);
      fs.writeFileSync(broadcastsFile, JSON.stringify(broadcasts));
      return b;
    },
    broadcastsSince(sinceMs) {
      return broadcasts.filter((b) => b.createdAtMs > sinceMs);
    },

    /** Stores an uploaded stem; returns its name and what it is. */
    putBlob(bytes, ext, info) {
      const sha256 = crypto.createHash("sha256").update(bytes).digest("hex");
      const name = `${sha256}.${ext}`;
      const file = path.join(blobsDir, name);
      if (!fs.existsSync(file)) {
        const tmp = `${file}.${process.pid}.tmp`;
        fs.writeFileSync(tmp, bytes);
        fs.renameSync(tmp, file);
      }
      const meta = { name, sha256, bytes: bytes.length, ...info, uploadedAtMs: Date.now() };
      writeJson(`${file}.json`, meta);
      return meta;
    },
    /** What [putBlob] recorded about a stored stem, or null. */
    blob(name) {
      if (!/^[0-9a-f]{64}\.(ogg|wav|flac)$/.test(name)) return null;
      const file = path.join(blobsDir, name);
      if (!fs.existsSync(file)) return null;
      return { ...readJson(`${file}.json`, {}), file };
    },

    getCatalog(missionId) {
      return readJson(catalogFile(missionId), { missionId, soundtracks: [], updatedAtMs: 0 });
    },
    putTrack(missionId, track) {
      const c = this.getCatalog(missionId);
      const now = Date.now();
      const stored = { ...track, updatedAtMs: now };
      const i = c.soundtracks.findIndex((t) => t.id === track.id);
      if (i >= 0) c.soundtracks[i] = stored;
      else c.soundtracks.push(stored);
      writeJson(catalogFile(missionId), { ...c, missionId, updatedAtMs: now });
      return stored;
    },
    deleteTrack(missionId, trackId) {
      const c = this.getCatalog(missionId);
      const left = c.soundtracks.filter((t) => t.id !== trackId);
      if (left.length === c.soundtracks.length) return false;
      writeJson(catalogFile(missionId), { ...c, soundtracks: left, updatedAtMs: Date.now() });
      return true;
    },
  };
}
