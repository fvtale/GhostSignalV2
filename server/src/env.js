import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

// Minimal .env support: KEY=value lines, '#' comments, an optional `export`
// prefix and optional matching quotes. It exists so a key dropped into
// server/.env works with a plain `node src/index.js`, not only under a
// service manager. Values already present in the real environment always
// win, and nothing loaded here is ever logged.

const DEFAULT_ENV_PATH = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../.env",
);

const SETTING_LINE = /^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/;

/**
 * Loads settings from [file] into [env]. Returns the names of the keys that
 * were set (never their values). A missing file is not an error.
 */
export function loadEnvFile(file = DEFAULT_ENV_PATH, env = process.env) {
  let text;
  try {
    text = fs.readFileSync(file, "utf8");
  } catch {
    return [];
  }

  const loadedKeys = [];
  for (const rawLine of text.replace(/^﻿/, "").split(/\r?\n/)) {
    const line = rawLine.trim();
    if (line === "" || line.startsWith("#")) continue;

    const match = SETTING_LINE.exec(line);
    if (!match) continue;
    const [, key, rawValue] = match;
    if (env[key] !== undefined) continue; // Real environment wins.

    let value = rawValue.trim();
    const first = value[0];
    if (value.length >= 2 && (first === '"' || first === "'") && value.at(-1) === first) {
      value = value.slice(1, -1);
    }
    env[key] = value;
    loadedKeys.push(key);
  }
  return loadedKeys;
}
