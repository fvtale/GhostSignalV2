import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));

/** The same mission file the app ships with, so personas and world live in one place. */
export const DEFAULT_MISSION_PATH = path.resolve(here, "../../app/src/main/assets/mission/ghostsignal.json");

export function loadMission(file = process.env.MISSION_PATH || DEFAULT_MISSION_PATH) {
  const mission = JSON.parse(fs.readFileSync(file, "utf8"));
  if (!mission.id || !Array.isArray(mission.characters)) {
    throw new Error(`${file} is not a GhostSignal mission`);
  }
  return mission;
}

export function findCharacter(mission, id) {
  return mission.characters.find((c) => c.id === id) ?? null;
}
