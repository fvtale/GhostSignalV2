/**
 * Reads just enough of an uploaded stem to know what it is and exactly how long it plays, without
 * decoding it: Ogg (Vorbis or Opus), WAV (16/24-bit PCM or 32-bit float) and FLAC. The phone
 * decodes it; the server only has to tell the admin whether it will loop on the beat.
 */
export const AUDIO_TYPES = { ogg: "audio/ogg", wav: "audio/wav", flac: "audio/flac" };

export function probeAudio(buf) {
  if (buf.length >= 4 && buf.toString("latin1", 0, 4) === "OggS") return probeOgg(buf);
  if (buf.length >= 12 && buf.toString("latin1", 0, 4) === "RIFF" && buf.toString("latin1", 8, 12) === "WAVE") return probeWav(buf);
  const flacAt = skipId3(buf);
  if (buf.length >= flacAt + 4 && buf.toString("latin1", flacAt, flacAt + 4) === "fLaC") return probeFlac(buf, flacAt);
  throw unsupported("not an Ogg, WAV or FLAC file");
}

function unsupported(message) {
  return Object.assign(new Error(message), { status: 415 });
}

function result(format, sampleRate, channels, frames) {
  if (!(sampleRate >= 8000 && sampleRate <= 192000)) throw unsupported(`sample rate ${sampleRate} Hz is not supported`);
  if (!(channels >= 1 && channels <= 2)) throw unsupported(`${channels} channels: export the stem as mono or stereo`);
  if (!(frames > 0)) throw unsupported("the file has no audio in it");
  return { format, sampleRate, channels, durationS: Math.round((frames / sampleRate) * 1e4) / 1e4 };
}

function probeOgg(buf) {
  const first = oggPage(buf, 0);
  if (!first) throw unsupported("damaged Ogg file");
  const head = first.payload;
  let codec;
  let rate;
  let channels;
  let preSkip = 0;
  if (head.length >= 16 && head[0] === 1 && head.toString("latin1", 1, 7) === "vorbis") {
    codec = "vorbis";
    channels = head[11];
    rate = head.readUInt32LE(12);
  } else if (head.length >= 19 && head.toString("latin1", 0, 8) === "OpusHead") {
    codec = "opus";
    channels = head[9];
    preSkip = head.readUInt16LE(10);
    rate = 48000; // Opus granule positions always count 48 kHz samples
  } else {
    throw unsupported("Ogg file is not Vorbis or Opus");
  }
  // The last page of the stream carries the total sample count.
  let granule = -1n;
  for (let at = buf.lastIndexOf("OggS", buf.length - 27, "latin1"); at >= 0; at = buf.lastIndexOf("OggS", at - 1, "latin1")) {
    const page = oggPage(buf, at);
    if (page && page.serial === first.serial && page.granule >= 0n) {
      granule = page.granule;
      break;
    }
    if (at === 0) break;
  }
  if (granule < 0n) throw unsupported("damaged Ogg file: no end position");
  return { ...result("ogg", rate, channels, Number(granule) - preSkip), codec };
}

function oggPage(buf, at) {
  if (at + 27 > buf.length || buf.toString("latin1", at, at + 4) !== "OggS") return null;
  const segments = buf[at + 26];
  if (at + 27 + segments > buf.length) return null;
  let size = 0;
  for (let i = 0; i < segments; i++) size += buf[at + 27 + i];
  const start = at + 27 + segments;
  return {
    granule: buf.readBigInt64LE(at + 6),
    serial: buf.readUInt32LE(at + 14),
    payload: buf.subarray(start, Math.min(buf.length, start + size)),
  };
}

function probeWav(buf) {
  let fmt = null;
  let dataBytes = -1;
  for (let at = 12; at + 8 <= buf.length; ) {
    const id = buf.toString("latin1", at, at + 4);
    const size = buf.readUInt32LE(at + 4);
    const body = at + 8;
    if (id === "fmt " && body + 16 <= buf.length) {
      let format = buf.readUInt16LE(body);
      if (format === 0xfffe && body + 26 <= buf.length) format = buf.readUInt16LE(body + 24); // WAVE_FORMAT_EXTENSIBLE
      fmt = { format, channels: buf.readUInt16LE(body + 2), rate: buf.readUInt32LE(body + 4), blockAlign: buf.readUInt16LE(body + 12), bits: buf.readUInt16LE(body + 14) };
    } else if (id === "data") {
      dataBytes = Math.min(size, buf.length - body);
      break;
    }
    at = body + size + (size % 2);
  }
  if (!fmt || dataBytes < 0) throw unsupported("damaged WAV file");
  const pcm = fmt.format === 1 && (fmt.bits === 16 || fmt.bits === 24);
  const float = fmt.format === 3 && fmt.bits === 32;
  if (!pcm && !float) throw unsupported(`WAV is ${fmt.bits}-bit format ${fmt.format}: export 16- or 24-bit PCM, or Ogg`);
  return { ...result("wav", fmt.rate, fmt.channels, Math.floor(dataBytes / fmt.blockAlign)), codec: float ? "float32" : `pcm${fmt.bits}` };
}

function probeFlac(buf, at) {
  const info = at + 8; // "fLaC", then the STREAMINFO block header
  if ((buf[at + 4] & 0x7f) !== 0 || info + 18 > buf.length) throw unsupported("damaged FLAC file");
  const bits = buf.readBigUInt64BE(info + 10);
  const rate = Number(bits >> 44n);
  const channels = Number((bits >> 41n) & 7n) + 1;
  const frames = Number(bits & 0xfffffffffn);
  return { ...result("flac", rate, channels, frames), codec: "flac" };
}

/** FLAC files sometimes start with an ID3v2 tag; its size is a 28-bit "syncsafe" integer. */
function skipId3(buf) {
  if (buf.length < 10 || buf.toString("latin1", 0, 3) !== "ID3") return 0;
  return 10 + ((buf[6] & 0x7f) << 21) + ((buf[7] & 0x7f) << 14) + ((buf[8] & 0x7f) << 7) + (buf[9] & 0x7f);
}
