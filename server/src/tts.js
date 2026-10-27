import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { findCharacter } from "./mission.js";

const SAMPLE_RATE = 24_000;

/** Wraps raw 16-bit mono PCM in a WAV header so the app can decode it directly. */
export function pcmToWav(pcm, sampleRate = SAMPLE_RATE) {
  const header = Buffer.alloc(44);
  header.write("RIFF", 0);
  header.writeUInt32LE(36 + pcm.length, 4);
  header.write("WAVE", 8);
  header.write("fmt ", 12);
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20); // PCM
  header.writeUInt16LE(1, 22); // mono
  header.writeUInt32LE(sampleRate, 24);
  header.writeUInt32LE(sampleRate * 2, 28);
  header.writeUInt16LE(2, 32);
  header.writeUInt16LE(16, 34);
  header.write("data", 36);
  header.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([header, pcm]);
}

/**
 * Character voices through ElevenLabs. Voice ids come from the mission (`voice.ttsVoice`), then
 * ELEVENLABS_VOICE_<CHARACTER> env vars, then ELEVENLABS_DEFAULT_VOICE. Rendered audio is cached on
 * disk, so each authored line is paid for once.
 *
 * To use another provider, implement `synthesize(text, voiceId) -> Buffer (PCM16 mono 24 kHz)`.
 */
export function createTts({
  apiKey = process.env.ELEVENLABS_API_KEY,
  modelId = process.env.ELEVENLABS_MODEL || "eleven_flash_v2_5",
  defaultVoice = process.env.ELEVENLABS_DEFAULT_VOICE,
  cacheDir = path.join(process.env.DATA_DIR || "data", "tts"),
  fetchImpl = globalThis.fetch,
} = {}) {
  fs.mkdirSync(cacheDir, { recursive: true });

  function voiceFor(mission, characterId) {
    const character = findCharacter(mission, characterId);
    if (!character) return null;
    return character.voice?.ttsVoice || process.env[`ELEVENLABS_VOICE_${characterId.toUpperCase()}`] || defaultVoice || null;
  }

  async function synthesize(text, voiceId) {
    const url = `https://api.elevenlabs.io/v1/text-to-speech/${encodeURIComponent(voiceId)}?output_format=pcm_${SAMPLE_RATE}`;
    const res = await fetchImpl(url, {
      method: "POST",
      headers: { "xi-api-key": apiKey, "content-type": "application/json", accept: "audio/pcm" },
      body: JSON.stringify({ text, model_id: modelId }),
      signal: AbortSignal.timeout(20_000),
    });
    if (!res.ok) {
      const err = new Error(`voice provider returned ${res.status}`);
      err.status = 502;
      throw err;
    }
    return Buffer.from(await res.arrayBuffer());
  }

  return {
    enabled: Boolean(apiKey),
    async render(mission, characterId, text) {
      if (!apiKey) {
        const err = new Error("voices not configured (set ELEVENLABS_API_KEY)");
        err.status = 501;
        throw err;
      }
      const voiceId = voiceFor(mission, characterId);
      if (!voiceId) {
        const err = new Error(`no voice for ${characterId}`);
        err.status = findCharacter(mission, characterId) ? 501 : 404;
        throw err;
      }
      const key = crypto.createHash("sha1").update(`${voiceId}|${modelId}|${text}`).digest("hex");
      const file = path.join(cacheDir, `${key}.wav`);
      if (fs.existsSync(file)) return fs.readFileSync(file);
      const wav = pcmToWav(await synthesize(text, voiceId));
      fs.writeFileSync(file, wav);
      return wav;
    },
  };
}
