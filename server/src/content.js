/**
 * Validation for what the admin page changes: signal placements and names (the venue route) and
 * deployed soundtracks. Anything that fails throws a 400 with a message the page can show.
 */

const ORIGINAL = "original";

function bad(message) {
  return Object.assign(new Error(message), { status: 400 });
}

const isObject = (v) => v !== null && typeof v === "object" && !Array.isArray(v);
const num = (v, lo, hi) => typeof v === "number" && Number.isFinite(v) && v >= lo && v <= hi;
const text = (v, max) => (v === undefined || v === null ? "" : typeof v === "string" && v.length <= max ? v.trim() : null);

/** The loop length every stem of a soundtrack should have, in seconds. */
export function loopSeconds(music) {
  return (music.loopBars * music.beatsPerBar * 60) / music.bpm;
}

/**
 * Applies a route change on top of what is stored. Placements and labels merge signal by signal,
 * so the phone's route editor and the admin page can each change some signals without undoing the
 * other's; `null` for a signal puts it back to the authored value.
 */
export function mergeRoute(mission, stored, body) {
  if (!isObject(body) || body.missionId !== mission.id) throw bad("invalid route");
  const signalIds = new Set(mission.signals.map((s) => s.id));
  const placements = { ...(stored?.placements ?? {}) };
  const labels = { ...(stored?.labels ?? {}) };

  if (body.placements !== undefined) {
    if (!isObject(body.placements)) throw bad("invalid route");
    for (const [id, p] of Object.entries(body.placements)) {
      if (!signalIds.has(id)) throw bad(`there is no signal '${id}'`);
      if (p === null) {
        delete placements[id];
        continue;
      }
      const ok =
        isObject(p) && isObject(p.location) && num(p.location.lat, -90, 90) && num(p.location.lon, -180, 180) &&
        num(p.radiusM, 5, 500) && num(p.lockRadiusM, 5, 500) && p.lockRadiusM <= p.radiusM;
      if (!ok) throw bad(`signal '${id}' needs a position and a capture radius no larger than its near radius`);
      placements[id] = { location: { lat: p.location.lat, lon: p.location.lon }, radiusM: p.radiusM, lockRadiusM: p.lockRadiusM };
    }
  }

  if (body.labels !== undefined) {
    if (!isObject(body.labels)) throw bad("invalid route");
    for (const [id, l] of Object.entries(body.labels)) {
      if (!signalIds.has(id)) throw bad(`there is no signal '${id}'`);
      if (l === null) {
        delete labels[id];
        continue;
      }
      const name = isObject(l) ? text(l.name, 60) : null;
      const place = isObject(l) ? text(l.place, 160) : null;
      if (name === null || place === null) throw bad(`signal '${id}' name is up to 60 characters, its place up to 160`);
      if (name || place) labels[id] = { name, place };
      else delete labels[id];
    }
  }

  const name = text(body.name, 80);
  return {
    missionId: mission.id,
    name: name || stored?.name || "Venue route",
    placements,
    labels,
  };
}

/**
 * Builds a deployed soundtrack in the mission's own Soundtrack shape from what the admin page
 * sends. Stems refer to uploaded files by name; [blobOf] looks them up. Returns the track and any
 * warnings worth showing (a stem that won't loop on the beat).
 */
export function buildTrack(mission, trackId, body, blobOf) {
  if (!/^[a-z0-9][a-z0-9-]{1,39}$/.test(trackId)) throw bad("track id is 2-40 lowercase letters, digits and dashes");
  const taken = new Set([ORIGINAL, ...(mission.soundtracks ?? []).map((t) => t.id)]);
  if (taken.has(trackId)) throw bad(`'${trackId}' is a soundtrack that ships with the app`);
  if (!isObject(body) || !isObject(body.music)) throw bad("invalid track");

  const title = text(body.title, 60);
  const description = text(body.description, 240);
  if (!title) throw bad("give the track a title (up to 60 characters)");
  if (description === null) throw bad("the description is up to 240 characters");
  const unlock = body.unlockAfterCompletions ?? 0;
  if (!Number.isInteger(unlock) || unlock < 0 || unlock > 20) throw bad("unlock after 0 to 20 finished runs");
  const published = body.published === true;
  const premium = body.premium === true;
  if (premium && unlock !== 0) throw bad("a premium track is open to premium players from their first run; set its unlock to 0");

  const m = body.music;
  const music = { title, bpm: m.bpm, beatsPerBar: m.beatsPerBar ?? 4, loopBars: m.loopBars ?? 8 };
  if (!num(music.bpm, 40, 220)) throw bad("BPM is 40 to 220");
  if (!Number.isInteger(music.beatsPerBar) || music.beatsPerBar < 1 || music.beatsPerBar > 12) throw bad("beats per bar is 1 to 12");
  if (!Number.isInteger(music.loopBars) || music.loopBars < 1 || music.loopBars > 64) throw bad("loop length is 1 to 64 bars");

  const slots = new Map(mission.signals.map((s) => [s.stem, s]));
  if (!Array.isArray(m.stems)) throw bad("invalid track");
  const seen = new Set();
  const loop = loopSeconds(music);
  const warnings = [];
  const stems = m.stems.map((st) => {
    if (!isObject(st) || !slots.has(st.id)) throw bad(`there is no stem slot '${st?.id}'`);
    if (seen.has(st.id)) throw bad(`stem '${st.id}' is in the track twice`);
    seen.add(st.id);
    const blob = typeof st.blob === "string" ? blobOf(st.blob) : null;
    if (!blob) throw bad(`upload a file for stem '${st.id}'`);
    const name = text(st.name, 60);
    if (name === null) throw bad(`stem '${st.id}' name is up to 60 characters`);
    const gainDb = st.gainDb ?? 0;
    if (!num(gainDb, -30, 12)) throw bad(`stem '${st.id}' gain is -30 to +12 dB`);
    const off = blob.durationS - loop;
    if (Math.abs(off) > 0.02) {
      warnings.push(
        `${slots.get(st.id).name}: the file is ${blob.durationS.toFixed(2)} s but ${music.loopBars} bars at ${music.bpm} BPM is ${loop.toFixed(2)} s, ` +
          (off > 0 ? "so its end is cut off." : "so it's padded with silence."),
      );
    }
    return {
      id: st.id,
      name: name || slots.get(st.id).name,
      gainDb,
      file: `server:${blob.name}`,
      sha256: blob.sha256,
      bytes: blob.bytes,
      format: blob.format,
      durationS: blob.durationS,
    };
  });
  const missing = [...slots.keys()].filter((id) => !seen.has(id));
  if (published && missing.length) throw bad(`a published track needs every stem: missing ${missing.join(", ")}`);

  // Keep the stems in signal order, so the catalog reads like the route.
  const order = [...slots.keys()];
  stems.sort((a, b) => order.indexOf(a.id) - order.indexOf(b.id));
  return {
    track: { id: trackId, title, description, unlockAfterCompletions: unlock, premium, published, music: { ...music, stems } },
    warnings,
  };
}
