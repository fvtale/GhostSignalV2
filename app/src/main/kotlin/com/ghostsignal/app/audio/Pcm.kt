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

/**
 * Interleaved 16-bit PCM. Everything the mixer touches is converted to
 * [SAMPLE_RATE] first, so renderers never reason about source rates.
 */
class Pcm(val data: ShortArray, val channels: Int, val sampleRate: Int) {

  val frames: Int
    get() = data.size / channels

  val durationMs: Long
    get() = frames * 1000L / sampleRate

  /** Downmixes to mono by averaging channels (already mono: returns this). */
  fun toMono(): Pcm {
    if (channels == 1) return this
    val mono = ShortArray(frames)
    for (frame in 0 until frames) {
      var channelSum = 0
      for (channel in 0 until channels) {
        channelSum += data[frame * channels + channel]
      }
      mono[frame] = (channelSum / channels).toShort()
    }
    return Pcm(mono, 1, sampleRate)
  }

  /** Duplicates mono into stereo (already stereo: returns this). */
  fun toStereo(): Pcm {
    if (channels == 2) return this
    val mono = toMono()
    val stereo = ShortArray(mono.frames * 2)
    for (frame in 0 until mono.frames) {
      stereo[2 * frame] = mono.data[frame]
      stereo[2 * frame + 1] = mono.data[frame]
    }
    return Pcm(stereo, 2, sampleRate)
  }

  /** Exactly [frames] long: truncated, or padded with silence. */
  fun withFrames(frames: Int): Pcm {
    if (frames == this.frames) return this
    val sized = ShortArray(frames * channels)
    System.arraycopy(data, 0, sized, 0, minOf(data.size, sized.size))
    return Pcm(sized, channels, sampleRate)
  }

  /** Linear-interpolation resampler; fine for speech and 44.1→48 kHz music. */
  fun resampled(targetRate: Int = SAMPLE_RATE): Pcm {
    if (sampleRate == targetRate || frames == 0) return this
    val ratio = sampleRate.toDouble() / targetRate
    val outputFrames = (frames / ratio).toInt()
    val resampledData = ShortArray(outputFrames * channels)
    for (frame in 0 until outputFrames) {
      val sourcePosition = frame * ratio
      val firstIndex = sourcePosition.toInt().coerceAtMost(frames - 1)
      val secondIndex = (firstIndex + 1).coerceAtMost(frames - 1)
      val fraction = sourcePosition - firstIndex
      for (channel in 0 until channels) {
        val firstSample = data[firstIndex * channels + channel]
        val secondSample = data[secondIndex * channels + channel]
        val interpolated = firstSample + (secondSample - firstSample) * fraction
        resampledData[frame * channels + channel] = interpolated.toInt().toShort()
      }
    }
    return Pcm(resampledData, channels, targetRate)
  }

  companion object {
    const val SAMPLE_RATE = 48_000

    fun silence(milliseconds: Long, channels: Int = 2): Pcm {
      val frameCount = (SAMPLE_RATE * milliseconds / 1000).toInt()
      return Pcm(ShortArray(frameCount * channels), channels, SAMPLE_RATE)
    }
  }
}

/** Decodes WAV directly; anything else (Ogg, MP3, AAC, FLAC) via MediaCodec. */
object PcmDecoder {

  fun decodeAsset(context: Context, assetPath: String): Pcm =
      context.assets.openFd(assetPath).use { descriptor ->
        decode { extractor ->
          extractor.setDataSource(
              descriptor.fileDescriptor, descriptor.startOffset, descriptor.length,
          )
        }
      }

  fun decodeFile(file: File): Pcm {
    if (file.extension.equals("wav", ignoreCase = true)) {
      WavCodec.read(file.readBytes())?.let { return it }
    }
    return decode { extractor -> extractor.setDataSource(file.absolutePath) }
  }

  fun decodeFd(fd: FileDescriptor, offset: Long, length: Long): Pcm =
      decode { extractor -> extractor.setDataSource(fd, offset, length) }

  private fun decode(configureSource: (MediaExtractor) -> Unit): Pcm {
    val extractor = MediaExtractor()
    try {
      configureSource(extractor)
      val audioTrackIndex = (0 until extractor.trackCount).firstOrNull { index ->
        extractor.getTrackFormat(index)
            .getString(MediaFormat.KEY_MIME)
            ?.startsWith("audio/") == true
      } ?: error("no audio track")
      extractor.selectTrack(audioTrackIndex)

      val trackFormat = extractor.getTrackFormat(audioTrackIndex)
      val mimeType = trackFormat.getString(MediaFormat.KEY_MIME)!!
      var channelCount = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
      var sourceRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
      var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

      val codec = MediaCodec.createDecoderByType(mimeType)
      codec.configure(trackFormat, null, null, 0)
      codec.start()

      val collected = GrowingShortBuffer()
      val bufferInfo = MediaCodec.BufferInfo()
      var inputExhausted = false
      var outputFinished = false
      try {
        while (!outputFinished) {
          if (!inputExhausted) {
            val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inputIndex >= 0) {
              val inputBuffer = codec.getInputBuffer(inputIndex)!!
              val sampleSize = extractor.readSampleData(inputBuffer, 0)
              if (sampleSize < 0) {
                codec.queueInputBuffer(
                    inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
                inputExhausted = true
              } else {
                codec.queueInputBuffer(
                    inputIndex, 0, sampleSize, extractor.sampleTime, 0,
                )
                extractor.advance()
              }
            }
          }

          val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
          when {
            outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
              val outputFormat = codec.outputFormat
              channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
              sourceRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
              if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                pcmEncoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
              }
            }
            outputIndex >= 0 -> {
              val outputBuffer = codec.getOutputBuffer(outputIndex)!!
              outputBuffer.position(bufferInfo.offset)
              outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
              appendDecoded(outputBuffer.order(ByteOrder.LITTLE_ENDIAN), pcmEncoding, collected)
              codec.releaseOutputBuffer(outputIndex, false)
              val isEnd = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
              if (isEnd) outputFinished = true
            }
          }
        }
      } finally {
        codec.stop()
        codec.release()
      }
      return Pcm(collected.toArray(), channelCount, sourceRate).resampled()
    } finally {
      extractor.release()
    }
  }

  private fun appendDecoded(buffer: ByteBuffer, encoding: Int, out: GrowingShortBuffer) {
    if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
      val floatView = buffer.asFloatBuffer()
      while (floatView.hasRemaining()) {
        val scaled = floatView.get().coerceIn(-1f, 1f) * 32767f
        out.add(scaled.toInt().toShort())
      }
    } else {
      val shortView = buffer.asShortBuffer()
      while (shortView.hasRemaining()) out.add(shortView.get())
    }
  }

  private const val DEQUEUE_TIMEOUT_US = 10_000L
}

/** Minimal RIFF/WAVE reader and writer for PCM (16-bit int, or 32-bit float in). */
object WavCodec {

  /** Parses a WAV byte array, or null when it is not one we support. */
  fun read(bytes: ByteArray): Pcm? {
    if (bytes.size < 44) return null
    val littleEndian = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 12) != "WAVE") return null

    var chunkOffset = 12
    var channelCount = 1
    var sourceRate = 16_000
    var bitsPerSample = 16
    var formatTag = 1

    while (chunkOffset + 8 <= bytes.size) {
      val chunkId = String(bytes, chunkOffset, 4)
      val chunkSize = littleEndian.getInt(chunkOffset + 4)
      val chunkBody = chunkOffset + 8
      when (chunkId) {
        "fmt " -> {
          formatTag = littleEndian.getShort(chunkBody).toInt() and 0xffff
          channelCount = littleEndian.getShort(chunkBody + 2).toInt()
          sourceRate = littleEndian.getInt(chunkBody + 4)
          bitsPerSample = littleEndian.getShort(chunkBody + 14).toInt()
        }
        "data" -> {
          val dataEnd = minOf(bytes.size, chunkBody + if (chunkSize < 0) Int.MAX_VALUE else chunkSize)
          return decodeDataChunk(bytes, chunkBody, dataEnd, formatTag, bitsPerSample, channelCount, sourceRate)
        }
      }
      chunkOffset = chunkBody + chunkSize + (chunkSize and 1) // Bodies are word-aligned.
    }
    return null
  }

  private fun decodeDataChunk(
      bytes: ByteArray,
      bodyStart: Int,
      bodyEnd: Int,
      formatTag: Int,
      bitsPerSample: Int,
      channels: Int,
      sampleRate: Int,
  ): Pcm? {
    return when {
      formatTag == 1 && bitsPerSample == 16 -> {
        val sampleCount = (bodyEnd - bodyStart) / 2
        val samples = ShortArray(sampleCount)
        ByteBuffer.wrap(bytes, bodyStart, bodyEnd - bodyStart)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(samples)
        Pcm(samples, channels, sampleRate).resampled()
      }
      formatTag == 3 && bitsPerSample == 32 -> {
        val sampleCount = (bodyEnd - bodyStart) / 4
        val floatSamples = FloatArray(sampleCount)
        ByteBuffer.wrap(bytes, bodyStart, bodyEnd - bodyStart)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
            .get(floatSamples)
        val converted = ShortArray(sampleCount) { index ->
          (floatSamples[index].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        Pcm(converted, channels, sampleRate).resampled()
      }
      else -> null
    }
  }

  /** Writes 16-bit PCM as a canonical 44-byte-header WAV file. */
  fun write(file: File, pcm: ShortArray, channels: Int, sampleRate: Int) {
    val dataByteCount = pcm.size * 2
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray())
    header.putInt(36 + dataByteCount)
    header.put("WAVE".toByteArray())
    header.put("fmt ".toByteArray())
    header.putInt(16)
    header.putShort(1) // PCM
    header.putShort(channels.toShort())
    header.putInt(sampleRate)
    header.putInt(sampleRate * channels * 2)
    header.putShort((channels * 2).toShort())
    header.putShort(16)
    header.put("data".toByteArray())
    header.putInt(dataByteCount)

    val body = ByteBuffer.allocate(dataByteCount).order(ByteOrder.LITTLE_ENDIAN)
    body.asShortBuffer().put(pcm)

    file.outputStream().use { output ->
      output.write(header.array())
      output.write(body.array())
    }
  }
}

/** Growable ShortArray used while decoding streams of unknown length. */
internal class GrowingShortBuffer(initialCapacity: Int = 1 shl 16) {

  private var buffer = ShortArray(initialCapacity)
  private var size = 0

  fun add(value: Short) {
    if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
    buffer[size++] = value
  }

  fun toArray(): ShortArray = buffer.copyOf(size)
}
