/**
 * Listening windows. The browser's speech recognizer when there is one (Chrome on Android, Safari);
 * either way the screen offers the expected answers as buttons and a box to type in, so a
 * challenge never depends on the recognizer hearing you in a noisy park.
 */
export class Speech {
  constructor() {
    this.Recognizer = window.SpeechRecognition || window.webkitSpeechRecognition || null;
    this.active = null;
  }

  get available() {
    return Boolean(this.Recognizer);
  }

  /**
   * Opens a window. [onText](text, isFinal, confidence) for what's heard (or tapped), then
   * [onEnd]() exactly once.
   */
  listen({ mode, timeoutMs }, { onText, onEnd }) {
    this.stop();
    const session = { onText, onEnd, ended: false, rec: null, timer: null };
    this.active = session;
    const end = () => {
      if (session.ended) return;
      session.ended = true;
      clearTimeout(session.timer);
      if (this.active === session) this.active = null;
      onEnd();
    };
    session.end = end;
    session.timer = setTimeout(() => (session.rec ? session.rec.stop() : end()), timeoutMs + (this.Recognizer ? 0 : 8000));
    if (!this.Recognizer) return;
    try {
      const rec = new this.Recognizer();
      rec.lang = "en-US";
      rec.interimResults = true;
      rec.continuous = mode === "FREEFORM";
      rec.maxAlternatives = 1;
      rec.onresult = (e) => {
        for (let i = e.resultIndex; i < e.results.length; i++) {
          const r = e.results[i];
          onText(r[0].transcript.trim(), r.isFinal, r[0].confidence || 0.8);
        }
      };
      rec.onerror = () => {};
      rec.onend = end;
      session.rec = rec;
      rec.start();
    } catch {
      session.rec = null;
    }
  }

  /** An answer tapped or typed on screen: counts as heard, and closes the window. */
  answer(text) {
    const s = this.active;
    if (!s || !text.trim()) return;
    s.onText(text.trim(), true, 1);
    if (s.rec) {
      s.rec.onresult = null;
      s.rec.abort();
    }
    s.end();
  }

  stop() {
    const s = this.active;
    if (!s) return;
    if (s.rec) s.rec.stop();
    else s.end();
  }
}
