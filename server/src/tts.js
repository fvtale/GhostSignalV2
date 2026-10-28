const SAMPLE_RATE = 24_000;

/** Wraps raw 16-bit mono PCM in a WAV header. Kept for tests and tools that build clips. */
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
 * Character voices are rendered on the device — Android TTS in the app, the browser's speech
 * synthesis in the web player — so the server no longer synthesizes speech and keeps no voice
 * keys. POST /v1/tts stays, answering 501 so older clients fall back to their local voice
 * instead of failing oddly.
 */
export function createTts() {
  return {
    enabled: false,
    async render() {
      throw Object.assign(new Error("voices are rendered on the device"), { status: 501 });
    },
  };
}
