import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

// The server loads the same mission JSON the app ships with, so character
// personas, the world brief, and the route exist in exactly one place.

const MODULE_DIR = path.dirname(fileURLToPath(import.meta.url));

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

/** The character with [id], or null. */
export function findCharacter(mission, id) {
  return mission.characters.find((character) => character.id === id) ?? null;
}
