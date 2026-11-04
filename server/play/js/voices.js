// Character voices in the browser come from the device's own speech
// synthesis: one system voice per character (chosen by a stable hash of
// the character id), at the character's authored pitch and rate. No voice
// keys exist server-side; spatial placement of voices lives in the app's
// mixer, while here the lines are read plainly so the story still lands.

const FALLBACK_MS_PER_CHAR = 60;
const FALLBACK_MAX_MS = 6000;
const WATCHDOG_BASE_MS = 1500;
const WATCHDOG_MS_PER_CHAR = 120;

function stableHash(text) {
  let hash = 0;
  for (let i = 0; i < text.length; i++) {
    hash = (hash * 31 + text.charCodeAt(i)) | 0;
  }
  return hash;
}

// Why not just hash into getVoices() on every line (the old code)? The
// voice list loads asynchronously: its size and order change as voices
// arrive, so `hash % list.length` quietly remaps every character mid-run
// — custom voice in the first paragraph, different (often default-
// sounding) voices after, persisting until the browser restarts and the
// list loads fresh. Chrome's network voices (localService === false)
// make it worse: they can silently fall back to the default voice in a
// long session. So: prefer local voices, sort them deterministically,
// and pin each character to a voiceURI for the whole session.
let cachedVoices = [];
const pinnedVoiceUri = new Map();

function refreshVoices() {
  const all = speechSynthesis.getVoices();
  if (all.length) cachedVoices = all;
  return cachedVoices;
}

if ("speechSynthesis" in window) {
  refreshVoices();
  speechSynthesis.addEventListener?.("voiceschanged", refreshVoices);
}

function englishPool() {
  const english = refreshVoices().filter(
    (voice) => voice.lang && voice.lang.replace("_", "-").startsWith("en"),
  );
  const local = english.filter((voice) => voice.localService);
  const pool = local.length ? local : english;
  return pool.sort((a, b) => (a.voiceURI || a.name).localeCompare(b.voiceURI || b.name));
}

function pickVoice(speakerId) {
  const pool = englishPool();
  if (!pool.length) return null;
  const pinned = pinnedVoiceUri.get(speakerId);
  if (pinned) {
    const stillThere = pool.find((voice) => voice.voiceURI === pinned);
    if (stillThere) return stillThere;
  }
  const voice = pool[Math.abs(stableHash(speakerId)) % pool.length];
  pinnedVoiceUri.set(speakerId, voice.voiceURI);
  return voice;
}

export class Voices {
  constructor(audio, missionId) {
    this.audio = audio;
    this.missionId = missionId;
    this.current = null;
  }

  /** Local synthesis needs no warming; kept for the caller's cadence. */
  prefetch() {}

  /** Speaks one utterance; resolves when it ends (or is skipped). */
  async say(utterance, bearingDeg) {
    await this.speakWithSynth(utterance);
  }

  speakWithSynth(utterance) {
    if (!("speechSynthesis" in window)) {
      // No synthesis at all: pace the story as if the line were read.
      const wait = Math.min(FALLBACK_MAX_MS, FALLBACK_MS_PER_CHAR * utterance.text.length);
      return new Promise((resolve) => setTimeout(resolve, wait));
    }

    return new Promise((resolve) => {
      const line = new SpeechSynthesisUtterance(utterance.text);
      line.lang = "en-US";
      line.pitch = Math.max(0, Math.min(2, utterance.pitch));
      line.rate = Math.max(0.5, Math.min(2, utterance.rate));
      const voice = pickVoice(utterance.speaker);
      if (voice) line.voice = voice;

      let settled = false;
      const finish = () => {
        if (settled) return;
        settled = true;
        resolve();
      };
      line.onend = finish;
      line.onerror = finish;
      this.current = {
        stop: () => {
          speechSynthesis.cancel();
          finish();
        },
      };
      speechSynthesis.speak(line);
      // Some browsers never fire onend for cancelled/blocked utterances.
      setTimeout(finish, WATCHDOG_BASE_MS + WATCHDOG_MS_PER_CHAR * utterance.text.length);
    });
  }

  stop() {
    if (this.current) this.current.stop();
    this.current = null;
    if ("speechSynthesis" in window) speechSynthesis.cancel();
  }
}
