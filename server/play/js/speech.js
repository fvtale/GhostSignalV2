import { api } from "./api.js";

/**
 * Listening windows. The runner's clip is recorded in the browser, sent to Muse Voice on
 * the server (POST /v1/stt) and the transcript comes back as one final result. The window
 * ends on a short silence after speech, on the timeout, or when the game stops listening.
 * Either way the screen offers the expected answers as buttons and a box to type in, so a
 * challenge never depends on transcription hearing you in a noisy park.
 */
export class Speech {
  constructor() {
    this.active = null;
  }

  get available() {
    return Boolean(navigator.mediaDevices?.getUserMedia);
  }

  /**
   * Opens a window. [onText](text, isFinal, confidence) for what's heard (or tapped), then
   * [onEnd]() exactly once.
   */
  listen({ timeoutMs, hints = [] }, { onText, onEnd }) {
    this.stop();
    const session = { onText, onEnd, ended: false, stream: null, ctx: null, node: null, chunks: [], rate: 16000, finish: null, end: null };
    this.active = session;
    const end = () => {
      if (session.ended) return;
      session.ended = true;
      if (this.active === session) this.active = null;
      onEnd();
    };
    session.end = end;

    let finishing = false;
    const finish = async () => {
      if (finishing || session.ended) return;
      finishing = true;
      cleanup();
      try {
        const wav = encodeWav(session.chunks, session.rate);
        if (wav) {
          const text = await api.stt(wav, hints);
          if (text && !session.ended) onText(text, true, 0.9);
        }
      } catch {
        // Transcription unavailable (signed out, offline): tapped and typed answers remain.
      }
      end();
    };
    session.finish = finish;

    const cleanup = () => {
      clearTimeout(session.timer);
      if (session.node) session.node.onaudioprocess = null;
      session.node?.disconnect();
      session.stream?.getTracks().forEach((t) => t.stop());
      session.ctx?.close().catch(() => {});
    };
    session.timer = setTimeout(finish, timeoutMs + 8000);

    (async () => {
      let stream;
      try {
        stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      } catch {
        return; // No mic: the window stays open for taps until the timer ends it.
      }
      if (session.ended || finishing) {
        stream.getTracks().forEach((t) => t.stop());
        return;
      }
      session.stream = stream;
      const ctx = new AudioContext();
      session.ctx = ctx;
      session.rate = ctx.sampleRate;
      const node = ctx.createScriptProcessor(4096, 1, 1);
      session.node = node;
      const started = performance.now();
      let heard = false;
      let lastLoud = 0;
      node.onaudioprocess = (e) => {
        const data = e.inputBuffer.getChannelData(0);
        session.chunks.push(new Float32Array(data));
        let peak = 0;
        for (let i = 0; i < data.length; i += 8) peak = Math.max(peak, Math.abs(data[i]));
        const now = performance.now();
        if (peak > 0.02) {
          heard = true;
          lastLoud = now;
        }
        if ((heard && now - lastLoud > 1200) || now - started > timeoutMs) finish();
      };
      ctx.createMediaStreamSource(stream).connect(node);
      node.connect(ctx.destination);
    })();
  }

  /** An answer tapped or typed on screen: counts as heard, and closes the window. */
  answer(text) {
    const s = this.active;
    if (!s || !text.trim()) return;
    s.onText(text.trim(), true, 1);
    s.end();
  }

  stop() {
    const s = this.active;
    if (!s) return;
    if (s.finish) s.finish();
    else s.end();
  }
}

/** The recorded clip as a mono 16 kHz PCM WAV, which is what Muse Voice accepts. */
function encodeWav(chunks, inputRate) {
  let length = 0;
  for (const c of chunks) length += c.length;
  if (!length) return null;
  const flat = new Float32Array(length);
  let at = 0;
  for (const c of chunks) {
    flat.set(c, at);
    at += c.length;
  }
  const ratio = inputRate / 16000;
  const frames = Math.max(1, Math.floor(length / ratio));
  const pcm = new Int16Array(frames);
  for (let i = 0; i < frames; i++) {
    const from = Math.floor(i * ratio);
    const to = Math.min(flat.length, Math.floor((i + 1) * ratio));
    let sum = 0;
    for (let j = from; j < to; j++) sum += flat[j];
    const v = Math.max(-1, Math.min(1, sum / Math.max(1, to - from)));
    pcm[i] = v < 0 ? v * 0x8000 : v * 0x7fff;
  }
  const bytes = new ArrayBuffer(44 + pcm.length * 2);
  const view = new DataView(bytes);
  const text = (offset, s) => {
    for (let i = 0; i < s.length; i++) view.setUint8(offset + i, s.charCodeAt(i));
  };
  text(0, "RIFF");
  view.setUint32(4, 36 + pcm.length * 2, true);
  text(8, "WAVEfmt ");
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, 16000, true);
  view.setUint32(28, 32000, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  text(36, "data");
  view.setUint32(40, pcm.length * 2, true);
  for (let i = 0; i < pcm.length; i++) view.setInt16(44 + i * 2, pcm[i], true);
  return new Blob([bytes], { type: "audio/wav" });
}
