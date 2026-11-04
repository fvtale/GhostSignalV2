import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

// The server loads the same mission JSON the app ships with, so character
// personas, the world brief, and the route exist in exactly one place.

const MODULE_DIR = path.dirname(fileURLToPath(import.meta.url));

export const DEFAULT_MISSION_ID = "ghostsignal-bryant-park";

export const DEFAULT_MISSIONS_DIR = path.resolve(
  MODULE_DIR,
  "../../app/src/main/assets/mission",
);

export const DEFAULT_MISSION_PATH = path.resolve(
  MODULE_DIR,
  "../../app/src/main/assets/mission/ghostsignal.json",
);

/** Reads and sanity-checks a mission file. Throws if it is not one. */
export function loadMission(file = process.env.MISSION_PATH || DEFAULT_MISSION_PATH) {
  const mission = JSON.parse(fs.readFileSync(file, "utf8"));
  if (!mission.id || !Array.isArray(mission.characters)) {
    throw new Error(`${file} is not a GhostSignal mission`);
  }
  return mission;
}

/**
 * Loads every *.json mission in [dir], keyed by mission id. Files that are
 * not readable missions are skipped, so one bad drop-in cannot stop the
 * server from serving the missions that are fine.
 */
export function loadMissions(dir = process.env.MISSIONS_DIR || DEFAULT_MISSIONS_DIR) {
  const missions = new Map();
  let files = [];
  try {
    files = fs.readdirSync(dir)
      .filter((name) => name.toLowerCase().endsWith(".json"))
      .sort();
  } catch {
    return missions;
  }
  for (const name of files) {
    try {
      const mission = loadMission(path.join(dir, name));
      if (!missions.has(mission.id)) missions.set(mission.id, mission);
    } catch {
      // Not a mission file (or unreadable): skip it.
    }
  }
  return missions;
}

/**
 * Normalises the ways callers describe the mission set — a single mission,
 * an array, a Map, or an id-keyed object — into a registry Map plus the
 * default mission. The single-mission form keeps older callers working.
 */
export function missionRegistry({ mission, missions, defaultMissionId } = {}) {
  const registry = new Map();
  const add = (candidate) => {
    if (candidate && typeof candidate.id === "string" && candidate.id) {
      registry.set(candidate.id, candidate);
    }
  };
  if (missions instanceof Map) {
    for (const candidate of missions.values()) add(candidate);
  } else if (Array.isArray(missions)) {
    for (const candidate of missions) add(candidate);
  } else if (missions && typeof missions === "object") {
    const nested = missions.missions ?? missions.byId ?? null;
    if (nested instanceof Map) {
      for (const candidate of nested.values()) add(candidate);
    } else if (Array.isArray(nested)) {
      for (const candidate of nested) add(candidate);
    } else {
      for (const candidate of Object.values(missions)) add(candidate);
    }
  }
  add(mission);

  let fallbackId = defaultMissionId || mission?.id || DEFAULT_MISSION_ID;
  if (!registry.has(fallbackId)) {
    fallbackId = mission?.id && registry.has(mission.id)
      ? mission.id
      : registry.keys().next().value;
  }
  return { missions: registry, defaultMissionId: fallbackId, defaultMission: registry.get(fallbackId) ?? null };
}

/** [{ id, title, landmark? }] for the mission picker, default first, then by id. */
export function missionSummaries(registry, defaultMissionId) {
  return [...registry.values()]
    .map((candidate) => ({
      id: candidate.id,
      title: candidate.title ?? candidate.id,
      ...(candidate.landmark
        ? {
            landmark: {
              name: candidate.landmark.name,
              label: candidate.landmark.label,
              location: candidate.landmark.location,
            },
          }
        : {}),
    }))
    .sort((a, b) => {
      if (a.id === defaultMissionId) return -1;
      if (b.id === defaultMissionId) return 1;
      return a.id.localeCompare(b.id);
    });
}

/** The character with [id], or null. */
export function findCharacter(mission, id) {
  return mission.characters.find((character) => character.id === id) ?? null;
}
