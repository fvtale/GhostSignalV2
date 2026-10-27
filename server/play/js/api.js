/**
 * Talking to the GhostSignal server, and what this browser remembers. The account session is the
 * same one the /account page keeps, so signing in there signs in here too.
 */

const ACCOUNT_KEY = "ghostsignal-account";
const KEYS = { progress: "ghostsignal-progress", rewards: "ghostsignal-rewards", settings: "ghostsignal-play" };

function read(key, fallback) {
  try {
    const v = localStorage.getItem(key);
    return v === null ? fallback : JSON.parse(v);
  } catch {
    return fallback;
  }
}

function write(key, value) {
  try {
    if (value === null || value === undefined) localStorage.removeItem(key);
    else localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Private window or storage full: the run still plays, it just won't be remembered.
  }
}

export const store = {
  progress: () => read(KEYS.progress, null),
  saveProgress: (p) => write(KEYS.progress, p),
  clearProgress: () => write(KEYS.progress, null),
  /** Finished runs and the soundtrack picked for the next one, as in the app. */
  rewards: () => ({ completions: 0, soundtrack: "original", ...read(KEYS.rewards, {}) }),
  saveRewards: (r) => write(KEYS.rewards, r),
  settings: () => ({ mode: "gps", volume: 0.9, ...read(KEYS.settings, {}) }),
  saveSettings: (s) => write(KEYS.settings, s),
  token() {
    try {
      return localStorage.getItem(ACCOUNT_KEY) || "";
    } catch {
      return "";
    }
  },
  forgetToken() {
    try {
      localStorage.removeItem(ACCOUNT_KEY);
    } catch {
      // nothing to forget
    }
  },
};

async function call(path, { method = "GET", body, raw = false } = {}) {
  const headers = { accept: raw ? "*/*" : "application/json" };
  const token = store.token();
  if (token) headers["x-account-token"] = token;
  if (body !== undefined) headers["content-type"] = "application/json";
  const res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  if (!res.ok) {
    const data = await res.json().catch(() => ({}));
    const err = new Error(data.error || `HTTP ${res.status}`);
    err.status = res.status;
    throw err;
  }
  if (raw) return res.arrayBuffer();
  return res.status === 204 ? null : res.json();
}

export const api = {
  health: () => call("/health"),
  mission: () => call("/v1/mission"),
  async route(missionId) {
    try {
      return await call(`/v1/routes/${missionId}`);
    } catch (e) {
      if (e.status === 404) return null;
      throw e;
    }
  },
  tracks: (missionId) => call(`/v1/tracks/${missionId}`),
  async me() {
    if (!store.token()) return null;
    try {
      return await call("/v1/account/me");
    } catch (e) {
      if (e.status === 401) store.forgetToken();
      return null;
    }
  },
  progress: (missionId, completions) => call("/v1/account/progress", { method: "POST", body: { missionId, completions } }),
  npc: (body) => call("/v1/npc", { method: "POST", body }),
  tts: (missionId, characterId, text) => call("/v1/tts", { method: "POST", body: { missionId, characterId, text }, raw: true }),
  broadcasts: (since) => call(`/v1/broadcasts?since=${since}`),
  /** A stem: bundled with the game, or deployed from the admin page ("server:<sha256>.<ext>"). */
  audio(file) {
    const path = file.startsWith("server:") ? `/v1/blobs/${file.slice("server:".length)}` : `/play/assets/${file}`;
    return call(path, { raw: true });
  },
};
