/**
 * Character voices, read by the browser's own speech synthesis: one system voice per
 * character (picked by name), at the character's pitch and rate. The server keeps no voice
 * keys; the spatial radio placement lives in the app, where the mixer can place a voice at
 * its relay — here the lines are read plainly so the story still lands.
 */
export class Voices {
  constructor(audio, missionId) {
    this.audio = audio;
    this.missionId = missionId;
    this.current = null;
  }

  /** Kept for the caller's prefetch cadence; local synthesis needs no warming up. */
  prefetch() {}

  /** Plays one utterance; resolves when it has been said (or skipped). */
  async say(u, bearingDeg) {
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
