/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.playback

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer

/**
 * Converts an Opus (WebM/Ogg) file into an AAC-LC .m4a with the phone's own codecs, so songs exported
 * for other apps open everywhere. Nothing is downloaded again: the audio already in the download cache is
 * decoded and re-encoded. Returns null when the phone cannot do it, so the caller can fall back.
 */
object OpusToM4aTranscoder {
    private const val TAG = "OpusToM4aTranscoder"
    private const val TIMEOUT_US = 10_000L
    private const val BITRATE = 192_000
    private const val MAX_IDLE_LOOPS = 2_000

    private class Pcm(val data: ByteArray) {
        var offset = 0
    }

    fun transcode(context: Context, input: ByteArray): ByteArray? {
        val inFile = File.createTempFile("export_in", ".webm", context.cacheDir)
        val outFile = File.createTempFile("export_out", ".m4a", context.cacheDir)
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            inFile.writeBytes(input)
            extractor = MediaExtractor().apply { setDataSource(inFile.absolutePath) }
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)
            val inFormat = extractor.getTrackFormat(trackIndex)
            val inMime = inFormat.getString(MediaFormat.KEY_MIME) ?: return null

            decoder = MediaCodec.createDecoderByType(inMime).apply {
                configure(inFormat, null, null, 0)
                start()
            }
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val pcmQueue = ArrayDeque<Pcm>()
            val info = MediaCodec.BufferInfo()
            var sampleRate = 0
            var channels = 0
            var samplesFed = 0L
            var extractorDone = false
            var decoderDone = false
            var encoderInputEosSent = false
            var encoderDone = false
            var muxerTrack = -1
            var idleLoops = 0

            while (!encoderDone) {
                var progressed = false

                // Compressed samples into the decoder.
                if (!extractorDone) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        progressed = true
                    }
                }

                // Decoded PCM out of the decoder, into a queue.
                if (!decoderDone) {
                    val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFormat = decoder.outputFormat
                            sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            progressed = true
                        }
                        index >= 0 -> {
                            if (encoder == null) {
                                // Formats announced late or not at all: ask the decoder now.
                                val outFormat = decoder.outputFormat
                                sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                            if (info.size > 0) {
                                val buffer = decoder.getOutputBuffer(index)!!
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                buffer.get(bytes)
                                pcmQueue.addLast(Pcm(bytes))
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decoderDone = true
                            decoder.releaseOutputBuffer(index, false)
                            progressed = true
                        }
                    }
                }

                // The encoder exists once the PCM format is known.
                if (encoder == null && sampleRate > 0 && channels > 0) {
                    if (channels > 2) return null
                    val outFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                    }
                    encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                        configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        start()
                    }
                }

                val enc = encoder
                if (enc != null) {
                    // PCM into the encoder, in pieces that fit its buffers.
                    if (!encoderInputEosSent) {
                        val index = enc.dequeueInputBuffer(TIMEOUT_US)
                        if (index >= 0) {
                            val buffer = enc.getInputBuffer(index)!!
                            buffer.clear()
                            val head = pcmQueue.firstOrNull()
                            if (head == null) {
                                if (decoderDone) {
                                    val ptsUs = samplesFed * 1_000_000L / sampleRate
                                    enc.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    encoderInputEosSent = true
                                    progressed = true
                                } else {
                                    enc.queueInputBuffer(index, 0, 0, 0, 0)
                                }
                            } else {
                                val bytesPerFrame = 2 * channels
                                var count = minOf(buffer.capacity(), head.data.size - head.offset)
                                count -= count % bytesPerFrame
                                if (count <= 0) {
                                    pcmQueue.removeFirst()
                                    enc.queueInputBuffer(index, 0, 0, 0, 0)
                                } else {
                                    buffer.put(head.data, head.offset, count)
                                    val ptsUs = samplesFed * 1_000_000L / sampleRate
                                    enc.queueInputBuffer(index, 0, count, ptsUs, 0)
                                    head.offset += count
                                    samplesFed += count / bytesPerFrame
                                    if (head.offset >= head.data.size) pcmQueue.removeFirst()
                                    progressed = true
                                }
                            }
                        }
                    }

                    // AAC out of the encoder, into the file.
                    val outIndex = enc.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            muxerTrack = muxer.addTrack(enc.outputFormat)
                            muxer.start()
                            muxerStarted = true
                            progressed = true
                        }
                        outIndex >= 0 -> {
                            val buffer: ByteBuffer = enc.getOutputBuffer(outIndex)!!
                            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (!isConfig && info.size > 0 && muxerStarted) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(muxerTrack, buffer, info)
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                            enc.releaseOutputBuffer(outIndex, false)
                            progressed = true
                        }
                    }
                }

                if (progressed) idleLoops = 0 else if (++idleLoops > MAX_IDLE_LOOPS) {
                    Timber.tag(TAG).w("Transcoding stalled, giving up")
                    return null
                }
            }

            if (!muxerStarted) return null
            muxer.stop()
            muxerStarted = false
            return outFile.readBytes().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not convert the song to m4a")
            return null
        } finally {
            runCatching { extractor?.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            inFile.delete()
            outFile.delete()
        }
    }
}
