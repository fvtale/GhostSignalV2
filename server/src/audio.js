// Header-only inspection of uploaded stems. The server never decodes
// audio; it only needs to identify the container (Ogg Vorbis/Opus, WAV, or
// FLAC), the sample rate and channels, and the exact play length so the
// admin page can warn when a stem will not loop on the beat.

export const AUDIO_TYPES = { ogg: "audio/ogg", wav: "audio/wav", flac: "audio/flac" };

function unsupported(message) {
  return Object.assign(new Error(message), { status: 415 });
}

function describe(format, sampleRate, channels, frameCount) {
  if (!(sampleRate >= 8000 && sampleRate <= 192000)) {
    throw unsupported(`sample rate ${sampleRate} Hz is not supported`);
  }
  if (!(channels >= 1 && channels <= 2)) {
    throw unsupported(`${channels} channels: export the stem as mono or stereo`);
  }
  if (!(frameCount > 0)) throw unsupported("the file has no audio in it");
  const durationS = Math.round((frameCount / sampleRate) * 1e4) / 1e4;
  return { format, sampleRate, channels, durationS };
}

/** Identifies and measures an uploaded stem from its headers alone. */
export function probeAudio(buf) {
  if (buf.length >= 4 && buf.toString("latin1", 0, 4) === "OggS") return probeOgg(buf);
  const isWav = buf.length >= 12 &&
    buf.toString("latin1", 0, 4) === "RIFF" &&
    buf.toString("latin1", 8, 12) === "WAVE";
  if (isWav) return probeWav(buf);
  const flacOffset = id3Length(buf);
  if (buf.length >= flacOffset + 4 && buf.toString("latin1", flacOffset, flacOffset + 4) === "fLaC") {
    return probeFlac(buf, flacOffset);
  }
  throw unsupported("not an Ogg, WAV or FLAC file");
}

// -- Ogg ---------------------------------------------------------------------

function readOggPage(buf, offset) {
  if (offset + 27 > buf.length) return null;
  if (buf.toString("latin1", offset, offset + 4) !== "OggS") return null;
  const segmentCount = buf[offset + 26];
  if (offset + 27 + segmentCount > buf.length) return null;
  let payloadSize = 0;
  for (let i = 0; i < segmentCount; i++) payloadSize += buf[offset + 27 + i];
  const payloadStart = offset + 27 + segmentCount;
  return {
    granule: buf.readBigInt64LE(offset + 6),
    serial: buf.readUInt32LE(offset + 14),
    payload: buf.subarray(payloadStart, Math.min(buf.length, payloadStart + payloadSize)),
  };
}

function probeOgg(buf) {
  const firstPage = readOggPage(buf, 0);
  if (!firstPage) throw unsupported("damaged Ogg file");
  const head = firstPage.payload;

  let codec;
  let sampleRate;
  let channels;
  let preSkip = 0;
  const isVorbis = head.length >= 16 && head[0] === 1 && head.toString("latin1", 1, 7) === "vorbis";
  const isOpus = head.length >= 19 && head.toString("latin1", 0, 8) === "OpusHead";
  if (isVorbis) {
    codec = "vorbis";
    channels = head[11];
    sampleRate = head.readUInt32LE(12);
  } else if (isOpus) {
    codec = "opus";
    channels = head[9];
    preSkip = head.readUInt16LE(10);
    sampleRate = 48000; // Opus granule positions always count 48 kHz samples.
  } else {
    throw unsupported("Ogg file is not Vorbis or Opus");
  }

  // The final page of the stream carries the total sample count.
  let endGranule = -1n;
  for (
    let at = buf.lastIndexOf("OggS", buf.length - 27, "latin1");
    at >= 0;
    at = buf.lastIndexOf("OggS", at - 1, "latin1")
  ) {
    const page = readOggPage(buf, at);
    if (page && page.serial === firstPage.serial && page.granule >= 0n) {
      endGranule = page.granule;
      break;
    }
    if (at === 0) break;
  }
  if (endGranule < 0n) throw unsupported("damaged Ogg file: no end position");

  return { ...describe("ogg", sampleRate, channels, Number(endGranule) - preSkip), codec };
}

// -- WAV ---------------------------------------------------------------------

function probeWav(buf) {
  let format = null;
  let dataSize = -1;
  for (let offset = 12; offset + 8 <= buf.length;) {
    const chunkId = buf.toString("latin1", offset, offset + 4);
    const chunkSize = buf.readUInt32LE(offset + 4);
    const bodyAt = offset + 8;
    if (chunkId === "fmt " && bodyAt + 16 <= buf.length) {
      let formatTag = buf.readUInt16LE(bodyAt);
      if (formatTag === 0xfffe && bodyAt + 26 <= buf.length) {
        formatTag = buf.readUInt16LE(bodyAt + 24); // WAVE_FORMAT_EXTENSIBLE sub-format
      }
      format = {
        formatTag,
        channels: buf.readUInt16LE(bodyAt + 2),
        sampleRate: buf.readUInt32LE(bodyAt + 4),
        blockAlign: buf.readUInt16LE(bodyAt + 12),
        bitsPerSample: buf.readUInt16LE(bodyAt + 14),
      };
    } else if (chunkId === "data") {
      dataSize = Math.min(chunkSize, buf.length - bodyAt);
      break;
    }
    offset = bodyAt + chunkSize + (chunkSize % 2);
  }

  if (!format || dataSize < 0) throw unsupported("damaged WAV file");
  const isPcm = format.formatTag === 1 && (format.bitsPerSample === 16 || format.bitsPerSample === 24);
  const isFloat = format.formatTag === 3 && format.bitsPerSample === 32;
  if (!isPcm && !isFloat) {
    throw unsupported(`WAV is ${format.bitsPerSample}-bit format ${format.formatTag}: export 16- or 24-bit PCM, or Ogg`);
  }
  const frames = Math.floor(dataSize / format.blockAlign);
  return { ...describe("wav", format.sampleRate, format.channels, frames), codec: isFloat ? "float32" : `pcm${format.bitsPerSample}` };
}

// -- FLAC ----------------------------------------------------------------------

function probeFlac(buf, offset) {
  const streamInfoAt = offset + 8; // Past "fLaC" and the block header.
  const firstBlockIsStreamInfo = (buf[offset + 4] & 0x7f) === 0;
  if (!firstBlockIsStreamInfo || streamInfoAt + 18 > buf.length) {
    throw unsupported("damaged FLAC file");
  }
  // STREAMINFO packs rate (20 bits), channels-1 (3 bits), and the total
  // frame count (36 bits) into one big-endian field.
  const packed = buf.readBigUInt64BE(streamInfoAt + 10);
  const sampleRate = Number(packed >> 44n);
  const channels = Number((packed >> 41n) & 7n) + 1;
  const frames = Number(packed & 0xfffffffffn);
  return { ...describe("flac", sampleRate, channels, frames), codec: "flac" };
}

/** Length of a leading ID3v2 tag (28-bit syncsafe size), or 0. */
function id3Length(buf) {
  if (buf.length < 10 || buf.toString("latin1", 0, 3) !== "ID3") return 0;
  return 10 +
    ((buf[6] & 0x7f) << 21) +
    ((buf[7] & 0x7f) << 14) +
    ((buf[8] & 0x7f) << 7) +
    (buf[9] & 0x7f);
}
