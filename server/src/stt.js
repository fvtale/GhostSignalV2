import { createMeta, DEFAULT_STT_MODEL } from "./meta.js";

// Runner speech via Muse Voice Transcribe, behind POST /v1/stt. Clients
// record a push-to-talk clip; the server validates it locally and only then
// spends an upstream call.

function reject(status, message) {
  return Object.assign(new Error(message), { status });
}

function readChunks(buf) {
  const chunks = [];
  for (let offset = 12; offset + 8 <= buf.length;) {
    const id = buf.toString("latin1", offset, offset + 4);
    const size = buf.readUInt32LE(offset + 4);
    chunks.push({ id, size, bodyAt: offset + 8 });
    if (id === "data") break;
    offset += 8 + size + (size % 2); // Chunk bodies are word-aligned.
  }
  return chunks;
}

/**
 * Validates that [buf] is a clip Muse Voice accepts — mono integer-PCM WAV
 * at 16 or 24 kHz — and returns its sample rate. Anything else fails with
 * 415 so the client can fix how it records instead of retrying pointlessly.
 */
export function wavRate(buf) {
  const isWav = Buffer.isBuffer(buf) && buf.length >= 44 &&
    buf.toString("latin1", 0, 4) === "RIFF" &&
    buf.toString("latin1", 8, 12) === "WAVE";
  if (!isWav) throw reject(415, "speech must be a WAV file");

  let format = null;
  let hasAudio = false;
  for (const chunk of readChunks(buf)) {
    if (chunk.id === "fmt " && chunk.bodyAt + 16 <= buf.length) {
      format = {
        formatTag: buf.readUInt16LE(chunk.bodyAt),
        channels: buf.readUInt16LE(chunk.bodyAt + 2),
        sampleRate: buf.readUInt32LE(chunk.bodyAt + 4),
        bitsPerSample: buf.readUInt16LE(chunk.bodyAt + 14),
      };
    } else if (chunk.id === "data") {
      hasAudio = chunk.size > 0;
    }
  }

  if (!format || !hasAudio) throw reject(415, "damaged WAV file");
  if (format.formatTag !== 1 || format.bitsPerSample !== 16 || format.channels !== 1) {
    throw reject(415, "speech must be mono 16-bit PCM WAV");
  }
  if (format.sampleRate !== 16_000 && format.sampleRate !== 24_000) {
    throw reject(415, `speech must be 16 or 24 kHz (got ${format.sampleRate})`);
  }
  return format.sampleRate;
}

export function createStt({
  meta = null,
  apiKey = process.env.MODEL_API_KEY || process.env.META_API_KEY || "",
  model = process.env.STT_MODEL || DEFAULT_STT_MODEL,
  baseUrl = process.env.META_API_BASE || undefined,
  fetchImpl = globalThis.fetch,
  log = console,
} = {}) {
  const client = meta ?? createMeta({
    apiKey,
    ...(baseUrl ? { baseUrl } : {}),
    fetchImpl,
    log,
  });

  return {
    enabled: client.enabled,
    model,

    async transcribe(wav, keywords = []) {
      if (!client.enabled) {
        throw reject(503, "speech transcription not configured (set MODEL_API_KEY)");
      }
      wavRate(wav); // Never pay to upload a clip the API would refuse.
      return client.transcribe(wav, { model, keywords });
    },
  };
}
