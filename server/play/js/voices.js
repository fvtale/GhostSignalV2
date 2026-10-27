import { api, store } from "./api.js";

/**
 * Character voices. Signed in, lines come from the server in each character's own voice (cached
 * there and here) and play through the radio filter, placed where the character's relay is.
 * Signed out, the browser's own speech voice reads them, with each character's pitch and rate.
 */
export class Voices {
  constructor(audio, missionId) {
    this.audio = audio;
    this.missionId = missionId;
    this.cache = new Map();
    this.current = null;
    this.cloud = Boolean(store.token());
  }

  /** Fetches a line ahead of time so it plays without a gap. */
  prefetch(u) {
    if (!this.cloud) return;
    this.buffer(u).catch(() => {});
  }

  buffer(u) {
    const key = `${u.speaker}\u0000${u.text}`;
    if (!this.cache.has(key)) {
      const p = api.tts(this.missionId, u.speaker, u.text).then((bytes) => this.audio.decode(bytes));
      p.catch(() => this.cache.delete(key));
      this.cache.set(key, p);
    }
    return this.cache.get(key);
  }

  /** Plays one utterance; resolves when it has been said (or skipped). */
  async say(u, bearingDeg) {
    if (this.cloud) {
      try {
        const buffer = await this.buffer(u);
        const handle = this.audio.playVoice(buffer, { bearingDeg, radio: u.radio });
        this.current = handle;
        await handle.ended;
        return;
      } catch {
        // No cloud voice for this line (offline, or not signed in any more): say it locally.
      }
    }
    await this.synth(u);
  }

  synth(u) {
    if (!("speechSynthesis" in window)) return new Promise((r) => setTimeout(r, Math.min(6000, 60 * u.text.length)));
    return new Promise((resolve) => {
      const line = new SpeechSynthesisUtterance(u.text);
      line.lang = "en-US";
      line.pitch = Math.max(0, Math.min(2, u.pitch));
      line.rate = Math.max(0.5, Math.min(2, u.rate));
      const english = speechSynthesis.getVoices().filter((v) => v.lang && v.lang.startsWith("en"));
      if (english.length) line.voice = english[Math.abs(hash(u.speaker)) % english.length];
      let settled = false;
      const done = () => {
        if (!settled) {
          settled = true;
          resolve();
        }
      };
      line.onend = done;
      line.onerror = done;
      this.current = { stop: () => { speechSynthesis.cancel(); done(); } };
      speechSynthesis.speak(line);
      // Some browsers never fire onend for a cancelled or blocked line.
      setTimeout(done, 1500 + 120 * u.text.length);
    });
  }

  stop() {
    if (this.current) this.current.stop();
    this.current = null;
    if ("speechSynthesis" in window) speechSynthesis.cancel();
  }
}

function hash(s) {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
  return h;
}
