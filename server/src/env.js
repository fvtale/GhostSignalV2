import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

/**
 * Loads `server/.env` (see .env.example) into process.env, so a key dropped in that file —
 * MODEL_API_KEY, say — works with a plain `node src/index.js`, not only under systemd's
 * EnvironmentFile or a host dashboard. Real environment variables always win: a value already
 * set is never overwritten. Deliberately tiny (KEY=value lines, # comments, optional quotes);
 * no dependency, and values are never logged.
 */
const DEFAULT_FILE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../.env");

export function loadEnvFile(file = DEFAULT_FILE, env = process.env) {
  let text;
  try {
    text = fs.readFileSync(file, "utf8");
  } catch {
    return []; // No .env file: configuration comes from the environment alone.
  }
  const loaded = [];
  for (const rawLine of text.replace(/^﻿/, "").split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const match = /^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/.exec(line);
    if (!match) continue;
    const [, key, rawValue] = match;
    if (env[key] !== undefined) continue; // The real environment wins.
    let value = rawValue.trim();
    if (value.length >= 2 && (value[0] === '"' || value[0] === "'") && value.at(-1) === value[0]) {
      value = value.slice(1, -1);
    }
    env[key] = value;
    loaded.push(key);
  }
  return loaded;
}
