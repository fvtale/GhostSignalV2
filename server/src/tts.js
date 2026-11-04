// Speech synthesis deliberately does not happen here: character voices are
// rendered on the device (Android TTS in the app, browser speech synthesis
// in the web player), so the server holds no voice keys. The POST /v1/tts
// route remains and answers 501, which older clients already treat as
// "fall back to the local voice".

const DEFAULT_SAMPLE_RATE = 24_000;

/** Wraps raw 16-bit mono PCM samples in a canonical 44-byte WAV header. */
export function pcmToWav(pcm, sampleRate = DEFAULT_SAMPLE_RATE) {
  const header = Buffer.alloc(44);
  header.write("RIFF", 0);
  header.writeUInt32LE(36 + pcm.length, 4);
  header.write("WAVE", 8);
  header.write("fmt ", 12);
  header.writeUInt32LE(16, 16); // fmt chunk size
  header.writeUInt16LE(1, 20); // PCM format
  header.writeUInt16LE(1, 22); // mono
  header.writeUInt32LE(sampleRate, 24);
  header.writeUInt32LE(sampleRate * 2, 28); // byte rate: 2 bytes per sample
  header.writeUInt16LE(2, 32); // block align
  header.writeUInt16LE(16, 34); // bits per sample
  header.write("data", 36);
  header.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([header, pcm]);
}

export function createTts() {
  return {
    enabled: false,
    async render() {
      throw Object.assign(new Error("voices are rendered on the device"), { status: 501 });
    },
  };
}
