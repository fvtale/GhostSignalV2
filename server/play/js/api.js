// Server calls and browser-side memory for the web player. The account
// session is shared with the /account page (same localStorage key), so
// signing in on either surface signs in on both.

const ACCOUNT_TOKEN_KEY = "ghostsignal-account";
const STORAGE_KEYS = {
  progress: "ghostsignal-progress",
  rewards: "ghostsignal-rewards",
  settings: "ghostsignal-play",
};

function readJson(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    return raw === null ? fallback : JSON.parse(raw);
  } catch {
    return fallback;
  }
}

function writeJson(key, value) {
  try {
    if (value === null || value === undefined) localStorage.removeItem(key);
    else localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Private window or full storage: the run still plays, unremembered.
  }
}

export const store = {
  progress: () => readJson(STORAGE_KEYS.progress, null),
  saveProgress: (progress) => writeJson(STORAGE_KEYS.progress, progress),
  clearProgress: () => writeJson(STORAGE_KEYS.progress, null),

  /** Finished-run count and the soundtrack chosen for the next run. */
  rewards: () => ({ completions: 0, soundtrack: "original", ...readJson(STORAGE_KEYS.rewards, {}) }),
  saveRewards: (rewards) => writeJson(STORAGE_KEYS.rewards, rewards),

  settings: () => ({ mode: "gps", volume: 0.9, ...readJson(STORAGE_KEYS.settings, {}) }),
  saveSettings: (settings) => writeJson(STORAGE_KEYS.settings, settings),

  token() {
    try {
      return localStorage.getItem(ACCOUNT_TOKEN_KEY) || "";
    } catch {
      return "";
    }
  },
  forgetToken() {
    try {
      localStorage.removeItem(ACCOUNT_TOKEN_KEY);
    } catch {
      // Nothing stored to forget.
    }
  },
};

function httpFailure(response, payload) {
  const error = new Error(payload?.error || `HTTP ${response.status}`);
  error.status = response.status;
  return error;
}

async function requestJson(path, { method = "GET", body } = {}) {
  const headers = { accept: "application/json" };
  const token = store.token();
  if (token) headers["x-account-token"] = token;
  if (body !== undefined) headers["content-type"] = "application/json";

  const response = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    throw httpFailure(response, await response.json().catch(() => ({})));
  }
  return response.status === 204 ? null : response.json();
}

async function requestBytes(path) {
  const headers = { accept: "*/*" };
  const token = store.token();
  if (token) headers["x-account-token"] = token;
  const response = await fetch(path, { headers });
  if (!response.ok) {
    throw httpFailure(response, await response.json().catch(() => ({})));
  }
  return response.arrayBuffer();
}

export const api = {
  health: () => requestJson("/health"),
  mission: () => requestJson("/v1/mission"),

  /** The shared venue route, or null when no route has been published. */
  async route(missionId) {
    try {
      return await requestJson(`/v1/routes/${missionId}`);
    } catch (error) {
      if (error.status === 404) return null;
      throw error;
    }
  },

  tracks: (missionId) => requestJson(`/v1/tracks/${missionId}`),

  /** The signed-in player's plan, or null when signed out. */
  async me() {
    if (!store.token()) return null;
    try {
      return await requestJson("/v1/account/me");
    } catch (error) {
      if (error.status === 401) store.forgetToken();
      return null;
    }
  },

  progress: (missionId, completions) =>
    requestJson("/v1/account/progress", { method: "POST", body: { missionId, completions } }),

  npc: (body) => requestJson("/v1/npc", { method: "POST", body }),

  /** Sends one recorded utterance (mono 16 kHz WAV) to Muse Voice. */
  async stt(wav, keywords = []) {
    const headers = { "content-type": "audio/wav" };
    const token = store.token();
    if (token) headers["x-account-token"] = token;
    const query = keywords.length ? `?keywords=${encodeURIComponent(keywords.join(","))}` : "";
    const response = await fetch(`/v1/stt${query}`, { method: "POST", headers, body: wav });
    if (!response.ok) {
      throw httpFailure(response, await response.json().catch(() => ({})));
    }
    const payload = await response.json();
    return payload.text ?? "";
  },

  broadcasts: (sinceMs) => requestJson(`/v1/broadcasts?since=${sinceMs}`),

  /** A stem: bundled assets, or `server:<sha256>.<ext>` from the admin page. */
  audio(file) {
    const path = file.startsWith("server:")
      ? `/v1/blobs/${file.slice("server:".length)}`
      : `/play/assets/${file}`;
    return requestBytes(path);
  },
};
