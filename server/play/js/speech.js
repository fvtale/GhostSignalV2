import { api } from "./api.js";

// Listening windows. The runner's clip is recorded in the browser, sent to
// Muse Voice on the server (POST /v1/stt), and the transcript returns as a
// single final result. A window closes on a short silence after speech, on
// the challenge timeout, or when the game stops listening. The screen
// always offers the expected answers as buttons plus a type-in box, so no
// challenge ever depends on transcription hearing you in a noisy park.

const TARGET_RATE = 16000;
const LOUD_THRESHOLD = 0.02;
const SILENCE_END_MS = 1200;
const PEAK_STRIDE = 8; // Peak-check every 8th sample; plenty for voice activity.

export class Speech {
  constructor() {
    this.session = null;
  }

  get available() {
    return Boolean(navigator.mediaDevices?.getUserMedia);
  }

  /**
   * Opens a listening window. onText(text, isFinal, confidence) delivers
   * what was heard (or tapped/typed); onEnd() then fires exactly once.
   */
  listen({ timeoutMs, hints = [] }, { onText, onEnd }) {
    this.stop();

    const session = {
      onText,
      onEnd,
      ended: false,
      finishing: false,
      stream: null,
      context: null,
      processor: null,
      chunks: [],
      sampleRate: TARGET_RATE,
      timer: null,
      finish: null,
      end: null,
    };
    this.session = session;

    const end = () => {
      if (session.ended) return;
      session.ended = true;
      if (this.session === session) this.session = null;
      onEnd();
    };
    session.end = end;

    const releaseAudio = () => {
      clearTimeout(session.timer);
      if (session.processor) session.processor.onaudioprocess = null;
      session.processor?.disconnect();
      session.stream?.getTracks().forEach((track) => track.stop());
      session.context?.close().catch(() => {});
    };

    const finish = async () => {
      if (session.finishing || session.ended) return;
      session.finishing = true;
      releaseAudio();
      try {
        const wav = encodeWav(session.chunks, session.sampleRate);
        if (wav) {
          const text = await api.stt(wav, hints);
          if (text && !session.ended) onText(text, true, 0.9);
        }
      } catch {
        // Transcription unavailable: tapped/typed answers still work.
      }
      end();
    };
    session.finish = finish;
    session.timer = setTimeout(finish, timeoutMs + 8000);

    this.captureMicrophone(session, timeoutMs, finish);
  }

  async captureMicrophone(session, timeoutMs, finish) {
    let stream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch {
      return; // No mic: the window stays open for taps until its timer.
    }
    if (session.ended || session.finishing) {
      stream.getTracks().forEach((track) => track.stop());
      return;
    }

    session.stream = stream;
    const context = new AudioContext();
    session.context = context;
    session.sampleRate = context.sampleRate;

    const processor = context.createScriptProcessor(4096, 1, 1);
    session.processor = processor;
    const startedAt = performance.now();
    let heardSpeech = false;
    let lastLoudAt = 0;

    processor.onaudioprocess = (event) => {
      const samples = event.inputBuffer.getChannelData(0);
      session.chunks.push(new Float32Array(samples));

      let peak = 0;
      for (let i = 0; i < samples.length; i += PEAK_STRIDE) {
        peak = Math.max(peak, Math.abs(samples[i]));
      }
      const now = performance.now();
      if (peak > LOUD_THRESHOLD) {
        heardSpeech = true;
        lastLoudAt = now;
      }
      const silenceEnded = heardSpeech && now - lastLoudAt > SILENCE_END_MS;
      if (silenceEnded || now - startedAt > timeoutMs) finish();
    };

    context.createMediaStreamSource(stream).connect(processor);
    processor.connect(context.destination);
  }

  /** A tapped or typed answer: delivered as heard, closing the window. */
  answer(text) {
    const session = this.session;
    if (!session || !text.trim()) return;
    session.onText(text.trim(), true, 1);
    session.end();
  }

  stop() {
    const session = this.session;
    if (!session) return;
    if (session.finish) session.finish();
    else session.end();
  }
}

/** Encodes captured float samples as a mono 16 kHz PCM WAV (Muse Voice input). */
function encodeWav(chunks, inputRate) {
  let totalSamples = 0;
  for (const chunk of chunks) totalSamples += chunk.length;
  if (!totalSamples) return null;

  const flat = new Float32Array(totalSamples);
  let offset = 0;
  for (const chunk of chunks) {
    flat.set(chunk, offset);
    offset += chunk.length;
  }

  // Downsample by averaging each input window into one output frame.
  const ratio = inputRate / TARGET_RATE;
  const frameCount = Math.max(1, Math.floor(totalSamples / ratio));
  const pcm = new Int16Array(frameCount);
  for (let frame = 0; frame < frameCount; frame++) {
    const from = Math.floor(frame * ratio);
    const to = Math.min(flat.length, Math.floor((frame + 1) * ratio));
    let sum = 0;
    for (let i = from; i < to; i++) sum += flat[i];
    const value = Math.max(-1, Math.min(1, sum / Math.max(1, to - from)));
    pcm[frame] = value < 0 ? value * 0x8000 : value * 0x7fff;
  }

  const bytes = new ArrayBuffer(44 + pcm.length * 2);
  const view = new DataView(bytes);
  const writeText = (at, text) => {
    for (let i = 0; i < text.length; i++) view.setUint8(at + i, text.charCodeAt(i));
  };
  writeText(0, "RIFF");
  view.setUint32(4, 36 + pcm.length * 2, true);
  writeText(8, "WAVEfmt ");
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true); // PCM
  view.setUint16(22, 1, true); // mono
  view.setUint32(24, TARGET_RATE, true);
  view.setUint32(28, TARGET_RATE * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  writeText(36, "data");
  view.setUint32(40, pcm.length * 2, true);
  for (let i = 0; i < pcm.length; i++) view.setInt16(44 + i * 2, pcm[i], true);

  return new Blob([bytes], { type: "audio/wav" });
}
