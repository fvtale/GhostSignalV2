import { createMeta, DEFAULT_STT_MODEL } from "./meta.js";

const fail = (status, message) => Object.assign(new Error(message), { status });

/**
 * Checks a clip is what Muse Voice accepts — a mono integer-PCM WAV at 16 or 24 kHz — and
 * returns its sample rate. Anything else is a 415 the client can act on (record differently),
 * rather than a wasted upstream call.
 */
export function wavRate(buf) {
  if (!Buffer.isBuffer(buf) || buf.length < 44 || buf.toString("latin1", 0, 4) !== "RIFF" || buf.toString("latin1", 8, 12) !== "WAVE") {
    throw fail(415, "speech must be a WAV file");
  }
  let fmt = null;
  let hasData = false;
  for (let at = 12; at + 8 <= buf.length; ) {
    const id = buf.toString("latin1", at, at + 4);
    const size = buf.readUInt32LE(at + 4);
    const body = at + 8;
    if (id === "fmt " && body + 16 <= buf.length) {
      fmt = { format: buf.readUInt16LE(body), channels: buf.readUInt16LE(body + 2), rate: buf.readUInt32LE(body + 4), bits: buf.readUInt16LE(body + 14) };
    } else if (id === "data") {
      hasData = size > 0;
      break;
    }
    at = body + size + (size % 2);
  }
  if (!fmt || !hasData) throw fail(415, "damaged WAV file");
  if (fmt.format !== 1 || fmt.bits !== 16 || fmt.channels !== 1) throw fail(415, "speech must be mono 16-bit PCM WAV");
  if (fmt.rate !== 16_000 && fmt.rate !== 24_000) throw fail(415, `speech must be 16 or 24 kHz (got ${fmt.rate})`);
  return fmt.rate;
}

/**
 * Runner speech through Muse Voice Transcribe, behind POST /v1/stt. The phone or browser
 * records a push-to-talk clip; the server checks it and hands it to the Model API.
 */
export function createStt({
  meta = null,
  apiKey = process.env.MODEL_API_KEY || process.env.META_API_KEY || "",
  model = process.env.STT_MODEL || DEFAULT_STT_MODEL,
  baseUrl = process.env.META_API_BASE || undefined,
  fetchImpl = globalThis.fetch,
  log = console,
} = {}) {
  const client = meta ?? createMeta({ apiKey, ...(baseUrl ? { baseUrl } : {}), fetchImpl, log });
  return {
    enabled: client.enabled,
    model,
    async transcribe(wav, keywords = []) {
      if (!client.enabled) throw fail(503, "speech transcription not configured (set MODEL_API_KEY)");
      wavRate(wav); // reject anything Muse Voice would refuse, before paying for the call
      return client.transcribe(wav, { model, keywords });
    },
  };
}
