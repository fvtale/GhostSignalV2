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

function pickVoice(speakerId) {
  const english = speechSynthesis.getVoices().filter(
    (voice) => voice.lang && voice.lang.startsWith("en"),
  );
  if (!english.length) return null;
  return english[Math.abs(stableHash(speakerId)) % english.length];
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
