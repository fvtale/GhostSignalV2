package com.ghostsignal.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Interleaved 16-bit PCM. Everything the mixer touches is converted to [SAMPLE_RATE] first. */
class Pcm(val data: ShortArray, val channels: Int, val sampleRate: Int) {
  val frames: Int
    get() = data.size / channels

  val durationMs: Long
    get() = frames * 1000L / sampleRate

  fun toMono(): Pcm {
    if (channels == 1) return this
    val out = ShortArray(frames)
    for (i in 0 until frames) {
      var sum = 0
      for (c in 0 until channels) sum += data[i * channels + c]
      out[i] = (sum / channels).toShort()
    }
    return Pcm(out, 1, sampleRate)
  }

  fun toStereo(): Pcm {
    if (channels == 2) return this
    val mono = toMono()
    val out = ShortArray(mono.frames * 2)
    for (i in 0 until mono.frames) {
      out[2 * i] = mono.data[i]
      out[2 * i + 1] = mono.data[i]
    }
    return Pcm(out, 2, sampleRate)
  }

  /** Exactly [frames] long: truncated, or padded with silence. */
  fun withFrames(frames: Int): Pcm {
    if (frames == this.frames) return this
    val out = ShortArray(frames * channels)
    System.arraycopy(data, 0, out, 0, minOf(data.size, out.size))
    return Pcm(out, channels, sampleRate)
  }

  /** Linear-interpolation resampler; fine for speech and for 44.1 -> 48 kHz music. */
  fun resampled(target: Int = SAMPLE_RATE): Pcm {
    if (sampleRate == target || frames == 0) return this
    val ratio = sampleRate.toDouble() / target
    val outFrames = (frames / ratio).toInt()
    val out = ShortArray(outFrames * channels)
    for (i in 0 until outFrames) {
      val src = i * ratio
      val i0 = src.toInt().coerceAtMost(frames - 1)
      val i1 = (i0 + 1).coerceAtMost(frames - 1)
      val t = src - i0
      for (c in 0 until channels) {
        val a = data[i0 * channels + c]
        val b = data[i1 * channels + c]
        out[i * channels + c] = (a + (b - a) * t).toInt().toShort()
      }
    }
    return Pcm(out, channels, target)
  }

  companion object {
    const val SAMPLE_RATE = 48_000

    fun silence(ms: Long, channels: Int = 2) =
        Pcm(ShortArray((SAMPLE_RATE * ms / 1000).toInt() * channels), channels, SAMPLE_RATE)
  }
}

/** Decodes WAV directly and anything else (OGG, MP3, AAC, FLAC) through MediaCodec. */
object PcmDecoder {
  fun decodeAsset(context: Context, path: String): Pcm =
      context.assets.openFd(path).use { afd ->
        decode { it.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length) }
      }

  fun decodeFile(file: File): Pcm {
    if (file.extension.equals("wav", ignoreCase = true)) {
      WavCodec.read(file.readBytes())?.let { return it }
    }
    return decode { it.setDataSource(file.absolutePath) }
  }

  fun decodeFd(fd: FileDescriptor, offset: Long, length: Long) =
      decode { it.setDataSource(fd, offset, length) }

  private fun decode(source: (MediaExtractor) -> Unit): Pcm {
    val extractor = MediaExtractor()
    try {
      source(extractor)
      val track =
          (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
          } ?: error("no audio track")
      extractor.selectTrack(track)
      val format = extractor.getTrackFormat(track)
      val mime = format.getString(MediaFormat.KEY_MIME)!!
      var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
      var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
      var encoding = AudioFormat.ENCODING_PCM_16BIT

      val codec = MediaCodec.createDecoderByType(mime)
      codec.configure(format, null, null, 0)
      codec.start()
      val out = ShortBuilder()
      val info = MediaCodec.BufferInfo()
      var inputDone = false
      var outputDone = false
      try {
        while (!outputDone) {
          if (!inputDone) {
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
              val buf = codec.getInputBuffer(inIndex)!!
              val size = extractor.readSampleData(buf, 0)
              if (size < 0) {
                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputDone = true
              } else {
                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                extractor.advance()
              }
            }
          }
          val outIndex = codec.dequeueOutputBuffer(info, 10_000)
          when {
            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
              val f = codec.outputFormat
              channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
              rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
              if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
            }
            outIndex >= 0 -> {
              val buf = codec.getOutputBuffer(outIndex)!!
              buf.position(info.offset)
              buf.limit(info.offset + info.size)
              appendPcm(buf.order(ByteOrder.LITTLE_ENDIAN), encoding, out)
              codec.releaseOutputBuffer(outIndex, false)
              if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
          }
        }
      } finally {
        codec.stop()
        codec.release()
      }
      return Pcm(out.toArray(), channels, rate).resampled()
    } finally {
      extractor.release()
    }
  }

  private fun appendPcm(buf: ByteBuffer, encoding: Int, out: ShortBuilder) {
    if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
      val fb = buf.asFloatBuffer()
      while (fb.hasRemaining()) out.add((fb.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort())
    } else {
      val sb = buf.asShortBuffer()
      while (sb.hasRemaining()) out.add(sb.get())
    }
  }
}

/** Minimal RIFF/WAVE reader and writer for 16-bit PCM. */
object WavCodec {
  fun read(bytes: ByteArray): Pcm? {
    if (bytes.size < 44) return null
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
    var pos = 12
    var channels = 1
    var rate = 16_000
    var bits = 16
    var format = 1
    while (pos + 8 <= bytes.size) {
      val id = String(bytes, pos, 4)
      val size = b.getInt(pos + 4)
      val body = pos + 8
      when (id) {
        "fmt " -> {
          format = b.getShort(body).toInt() and 0xffff
          channels = b.getShort(body + 2).toInt()
          rate = b.getInt(body + 4)
          bits = b.getShort(body + 14).toInt()
        }
        "data" -> {
          val end = minOf(bytes.size, body + if (size < 0) Int.MAX_VALUE else size)
          return when {
            format == 1 && bits == 16 -> {
              val shorts = ShortArray((end - body) / 2)
              ByteBuffer.wrap(bytes, body, end - body).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
              Pcm(shorts, channels, rate).resampled()
            }
            format == 3 && bits == 32 -> {
              val n = (end - body) / 4
              val floats = FloatArray(n)
              ByteBuffer.wrap(bytes, body, end - body).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
              Pcm(ShortArray(n) { (floats[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }, channels, rate).resampled()
            }
            else -> null
          }
        }
      }
      pos = body + size + (size and 1)
    }
    return null
  }

  fun write(file: File, pcm: ShortArray, channels: Int, sampleRate: Int) {
    val dataBytes = pcm.size * 2
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
    header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
    header.putInt(sampleRate).putInt(sampleRate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
    header.put("data".toByteArray()).putInt(dataBytes)
    val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
    body.asShortBuffer().put(pcm)
    file.outputStream().use {
      it.write(header.array())
      it.write(body.array())
    }
  }
}

internal class ShortBuilder(initial: Int = 1 shl 16) {
  private var buf = ShortArray(initial)
  private var size = 0

  fun add(v: Short) {
    if (size == buf.size) buf = buf.copyOf(buf.size * 2)
    buf[size++] = v
  }

  fun toArray(): ShortArray = buf.copyOf(size)
}
