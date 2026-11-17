// Character voices in the browser come from the device's own speech
// synthesis: one system voice per character, cast by type (see CASTING
// below), at the character's pitch and rate. No voice
// keys exist server-side; spatial placement of voices lives in the app's
// mixer, while here the lines are read plainly so the story still lands.

const FALLBACK_MS_PER_CHAR = 60;
const FALLBACK_MAX_MS = 6000;
const WATCHDOG_BASE_MS = 1500;
const WATCHDOG_MS_PER_CHAR = 120;

// Casting, not hashing. The old code hashed each speaker id into
// whatever voice list the OS happened to offer, so characters landed on
// arbitrary voices: Zion could get the female default, Vesper the plain
// system voice. Each character below is cast by type — gender, named
// voices in preference order, home locale — against every English voice
// the browser offers (Edge's distinct natural voices included; dropping
// non-local voices, an earlier fix, collapsed Edge casts onto the same
// two local voices). Local voices only break ties, and each character
// is pinned to their voiceURI for the session: the voice list loads
// asynchronously, and re-picking from a growing/reordering list remaps
// characters mid-run — custom in paragraph one, generic after, until
// the browser restarts and the list loads fresh.
export const CASTING = {
  vesper: { gender: "female", locale: "en-US", prefer: ["aria", "jenny", "michelle", "ava", "emma", "samantha", "zira"], pitch: 0.9, rate: 1.0 },
  archivist: { gender: "male", locale: "en-US", prefer: ["christopher", "eric", "guy", "david", "mark", "roger", "brian"], pitch: 0.72, rate: 0.95 },
  mari: { gender: "male", locale: "en-US", prefer: ["roger", "brian", "tony", "christopher", "stefan", "eric", "david"], pitch: 0.78, rate: 1.02 },
  kilo: { gender: "female", locale: "en-US", prefer: ["ana", "ava", "emma", "jenny", "aria", "zira"] },
  hush: { gender: "male", locale: "en-US", prefer: ["david", "guy", "christopher", "mark"] },
  smartspecs: { gender: "any", locale: "en-US", prefer: ["google us english", "ava", "ana", "zira"] },
};

const FEMALE_NAMES = ["female", "aria", "jenny", "michelle", "ava", "emma", "ana", "samantha", "zira", "victoria", "karen", "moira", "fiona", "tessa", "catherine", "linda", "veena", "zira"];
const MALE_NAMES = ["male", "christopher", "eric", "guy", "david", "mark", "roger", "brian", "tony", "stefan", "daniel", "alex", "fred", "oliver", "george", "ryan", "andrew", "prabhat"];

function voiceGender(voice) {
  const name = (voice.name || "").toLowerCase();
  if (name.includes("female") || FEMALE_NAMES.some((n) => name.includes(n))) return "female";
  if (name.includes("male") || MALE_NAMES.some((n) => name.includes(n))) return "male";
  return "unknown";
}

/**
 * Picks the voice for a character from `voices`. `takenUris` holds the
 * voiceURIs already cast to other characters; sharing is penalized but
 * allowed when the pool is too small (two-voice browsers) — pitch and
 * rate carry the difference there. Deterministic: ties break by URI.
 */
export function chooseVoice(speakerId, voices, takenUris = new Set()) {
  const english = voices.filter(
    (voice) => voice.lang && voice.lang.replace("_", "-").startsWith("en"),
  );
  if (!english.length) return null;
  const cast = CASTING[speakerId] || { gender: "any", prefer: [] };
  const scored = english.map((voice) => {
    const name = (voice.name || "").toLowerCase();
    let score = 0;
    const rank = cast.prefer.findIndex((wanted) => name.includes(wanted));
    if (rank >= 0) score += 1000 - rank * 10;
    const gender = voiceGender(voice);
    if (cast.gender !== "any" && gender === cast.gender) score += 300;
    if (cast.gender !== "any" && gender !== "unknown" && gender !== cast.gender) score -= 600;
    if (cast.locale && voice.lang.replace("_", "-").startsWith(cast.locale)) score += 80;
    if (name.includes("natural") || name.includes("neural")) score += 20;
    if (voice.localService) score += 40;
    if (takenUris.has(voice.voiceURI)) score -= 500;
    return { voice, score };
  });
  scored.sort((a, b) => b.score - a.score || (a.voice.voiceURI || "").localeCompare(b.voice.voiceURI || ""));
  return scored[0].voice;
}

/** Final pitch/rate for a line: the casting sheet overrides the authored spec where it has an opinion. */
export function treatmentFor(speakerId, authored = {}) {
  const cast = CASTING[speakerId] || {};
  return {
    pitch: Math.max(0, Math.min(2, cast.pitch ?? authored.pitch ?? 1)),
    rate: Math.max(0.5, Math.min(2, cast.rate ?? authored.rate ?? 1)),
  };
}

let cachedVoices = [];
const pinnedVoiceUri = new Map();

function refreshVoices() {
  const all = speechSynthesis.getVoices();
  if (all.length) cachedVoices = all;
  return cachedVoices;
}

if (typeof window !== "undefined" && "speechSynthesis" in window) {
  refreshVoices();
  speechSynthesis.addEventListener?.("voiceschanged", refreshVoices);
}

function pickVoice(speakerId) {
  const pool = refreshVoices();
  if (!pool.length) return null;
  const pinned = pinnedVoiceUri.get(speakerId);
  if (pinned) {
    const stillThere = pool.find((voice) => voice.voiceURI === pinned);
    if (stillThere) return stillThere;
  }
  const taken = new Set([...pinnedVoiceUri.entries()].filter(([id]) => id !== speakerId).map(([, uri]) => uri));
  const voice = chooseVoice(speakerId, pool, taken);
  if (voice) pinnedVoiceUri.set(speakerId, voice.voiceURI);
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
      const treatment = treatmentFor(utterance.speaker, utterance);
      line.pitch = treatment.pitch;
      line.rate = treatment.rate;
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
