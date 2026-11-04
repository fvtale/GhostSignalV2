// Validation for everything the admin page can change: venue route
// placements/names and deployed soundtracks. Failures throw 400 errors whose
// messages the page shows verbatim, so they are written for admins, not logs.

const RESERVED_TRACK_ID = "original";

function invalid(message) {
  return Object.assign(new Error(message), { status: 400 });
}

const isRecord = (value) => value !== null && typeof value === "object" && !Array.isArray(value);
const isNumberIn = (value, low, high) =>
  typeof value === "number" && Number.isFinite(value) && value >= low && value <= high;

// Optional text: absent becomes "", present must be a string within [max].
const optionalText = (value, max) => {
  if (value === undefined || value === null) return "";
  return typeof value === "string" && value.length <= max ? value.trim() : null;
};

/** Seconds in one full loop of a soundtrack with this music shape. */
export function loopSeconds(music) {
  return (music.loopBars * music.beatsPerBar * 60) / music.bpm;
}

/**
 * Merges a route change over the stored route. Placements and labels merge
 * per signal (the phone's route editor and the admin page can each touch
 * some signals without undoing each other); a null entry restores the
 * authored value for that signal.
 */
export function mergeRoute(mission, stored, body) {
  if (!isRecord(body) || body.missionId !== mission.id) throw invalid("invalid route");
  const knownSignals = new Set(mission.signals.map((signal) => signal.id));
  const placements = { ...(stored?.placements ?? {}) };
  const labels = { ...(stored?.labels ?? {}) };

  if (body.placements !== undefined) {
    if (!isRecord(body.placements)) throw invalid("invalid route");
    for (const [signalId, placement] of Object.entries(body.placements)) {
      if (!knownSignals.has(signalId)) throw invalid(`there is no signal '${signalId}'`);
      if (placement === null) {
        delete placements[signalId];
        continue;
      }
      const valid = isRecord(placement) && isRecord(placement.location) &&
        isNumberIn(placement.location.lat, -90, 90) &&
        isNumberIn(placement.location.lon, -180, 180) &&
        isNumberIn(placement.radiusM, 5, 500) &&
        isNumberIn(placement.lockRadiusM, 5, 500) &&
        placement.lockRadiusM <= placement.radiusM;
      if (!valid) {
        throw invalid(`signal '${signalId}' needs a position and a capture radius no larger than its near radius`);
      }
      placements[signalId] = {
        location: { lat: placement.location.lat, lon: placement.location.lon },
        radiusM: placement.radiusM,
        lockRadiusM: placement.lockRadiusM,
      };
    }
  }

  if (body.labels !== undefined) {
    if (!isRecord(body.labels)) throw invalid("invalid route");
    for (const [signalId, label] of Object.entries(body.labels)) {
      if (!knownSignals.has(signalId)) throw invalid(`there is no signal '${signalId}'`);
      if (label === null) {
        delete labels[signalId];
        continue;
      }
      const name = isRecord(label) ? optionalText(label.name, 60) : null;
      const place = isRecord(label) ? optionalText(label.place, 160) : null;
      if (name === null || place === null) {
        throw invalid(`signal '${signalId}' name is up to 60 characters, its place up to 160`);
      }
      if (name || place) labels[signalId] = { name, place };
      else delete labels[signalId];
    }
  }

  const routeName = optionalText(body.name, 80);
  return {
    missionId: mission.id,
    name: routeName || stored?.name || "Venue route",
    placements,
    labels,
  };
}

/**
 * Builds a deployed soundtrack in the mission's Soundtrack shape from an
 * admin submission. Stems reference uploaded blobs by name; [blobOf]
 * resolves them. Returns the track plus warnings worth showing (chiefly a
 * stem whose length will not loop on the beat).
 */
export function buildTrack(mission, trackId, body, blobOf) {
  if (!/^[a-z0-9][a-z0-9-]{1,39}$/.test(trackId)) {
    throw invalid("track id is 2-40 lowercase letters, digits and dashes");
  }
  const bundledIds = new Set([RESERVED_TRACK_ID, ...(mission.soundtracks ?? []).map((t) => t.id)]);
  if (bundledIds.has(trackId)) throw invalid(`'${trackId}' is a soundtrack that ships with the app`);
  if (!isRecord(body) || !isRecord(body.music)) throw invalid("invalid track");

  const title = optionalText(body.title, 60);
  const description = optionalText(body.description, 240);
  if (!title) throw invalid("give the track a title (up to 60 characters)");
  if (description === null) throw invalid("the description is up to 240 characters");

  const unlockAfterCompletions = body.unlockAfterCompletions ?? 0;
  if (!Number.isInteger(unlockAfterCompletions) || unlockAfterCompletions < 0 || unlockAfterCompletions > 20) {
    throw invalid("unlock after 0 to 20 finished runs");
  }
  const published = body.published === true;
  const premium = body.premium === true;
  if (premium && unlockAfterCompletions !== 0) {
    throw invalid("a premium track is open to premium players from their first run; set its unlock to 0");
  }

  const submitted = body.music;
  const musicShape = {
    title,
    bpm: submitted.bpm,
    beatsPerBar: submitted.beatsPerBar ?? 4,
    loopBars: submitted.loopBars ?? 8,
  };
  if (!isNumberIn(musicShape.bpm, 40, 220)) throw invalid("BPM is 40 to 220");
  if (!Number.isInteger(musicShape.beatsPerBar) || musicShape.beatsPerBar < 1 || musicShape.beatsPerBar > 12) {
    throw invalid("beats per bar is 1 to 12");
  }
  if (!Number.isInteger(musicShape.loopBars) || musicShape.loopBars < 1 || musicShape.loopBars > 64) {
    throw invalid("loop length is 1 to 64 bars");
  }

  const slotById = new Map(mission.signals.map((signal) => [signal.stem, signal]));
  if (!Array.isArray(submitted.stems)) throw invalid("invalid track");

  const expectedLoopS = loopSeconds(musicShape);
  const seenSlots = new Set();
  const warnings = [];
  const stems = submitted.stems.map((submittedStem) => {
    if (!isRecord(submittedStem) || !slotById.has(submittedStem.id)) {
      throw invalid(`there is no stem slot '${submittedStem?.id}'`);
    }
    if (seenSlots.has(submittedStem.id)) throw invalid(`stem '${submittedStem.id}' is in the track twice`);
    seenSlots.add(submittedStem.id);

    const blob = typeof submittedStem.blob === "string" ? blobOf(submittedStem.blob) : null;
    if (!blob) throw invalid(`upload a file for stem '${submittedStem.id}'`);

    const stemName = optionalText(submittedStem.name, 60);
    if (stemName === null) throw invalid(`stem '${submittedStem.id}' name is up to 60 characters`);

    const gainDb = submittedStem.gainDb ?? 0;
    if (!isNumberIn(gainDb, -30, 12)) throw invalid(`stem '${submittedStem.id}' gain is -30 to +12 dB`);

    const driftS = blob.durationS - expectedLoopS;
    if (Math.abs(driftS) > 0.02) {
      warnings.push(
        `${slotById.get(submittedStem.id).name}: the file is ${blob.durationS.toFixed(2)} s but ${musicShape.loopBars} bars at ${musicShape.bpm} BPM is ${expectedLoopS.toFixed(2)} s, ` +
        (driftS > 0 ? "so its end is cut off." : "so it's padded with silence."),
      );
    }

    return {
      id: submittedStem.id,
      name: stemName || slotById.get(submittedStem.id).name,
      gainDb,
      file: `server:${blob.name}`,
      sha256: blob.sha256,
      bytes: blob.bytes,
      format: blob.format,
      durationS: blob.durationS,
    };
  });

  const missingSlots = [...slotById.keys()].filter((id) => !seenSlots.has(id));
  if (published && missingSlots.length) {
    throw invalid(`a published track needs every stem: missing ${missingSlots.join(", ")}`);
  }

  // Keep stems in route order so the catalog reads like the mission.
  const routeOrder = [...slotById.keys()];
  stems.sort((a, b) => routeOrder.indexOf(a.id) - routeOrder.indexOf(b.id));

  return {
    track: {
      id: trackId,
      title,
      description,
      unlockAfterCompletions,
      premium,
      published,
      music: { ...musicShape, stems },
    },
    warnings,
  };
}
