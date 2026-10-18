/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.playback

import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioTagEmbedder {

    /**
     * Embeds metadata (artwork, lyrics, title, artist, album) directly into audio containers.
     * Supports MP4/M4A containers.
     * If the audio format is not supported or parsing fails, returns original bytes safely.
     */
    fun embedMetadata(
        audioBytes: ByteArray,
        isM4a: Boolean,
        artworkBytes: ByteArray? = null,
        lyrics: String? = null,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
    ): ByteArray {
        if (audioBytes.isEmpty()) return audioBytes

        return try {
            if (isM4a || isMp4Header(audioBytes)) {
                embedM4aMetadata(audioBytes, artworkBytes, lyrics, title, artist, album)
            } else if (isWebmHeader(audioBytes)) {
                embedWebmMetadata(audioBytes, artworkBytes, lyrics, title, artist, album)
            } else {
                audioBytes
            }
        } catch (e: Exception) {
            Timber.tag("AudioTagEmbedder").w(e, "Failed to embed metadata into audio container, returning original audio")
            audioBytes
        }
    }

    /**
     * Backward-compatible helper for embedding artwork into M4A/MP4.
     */
    fun embedArtwork(audioBytes: ByteArray, artworkBytes: ByteArray, isM4a: Boolean): ByteArray {
        return embedMetadata(audioBytes, isM4a, artworkBytes = artworkBytes)
    }

    fun isMp4Header(audio: ByteArray): Boolean {
        if (audio.size < 8) return false
        val type = String(audio.copyOfRange(4, 8), Charsets.US_ASCII)
        return type == "ftyp" || type == "moov"
    }

    fun isWebmHeader(audio: ByteArray): Boolean {
        return audio.size >= 4 &&
            audio[0] == 0x1A.toByte() && audio[1] == 0x45.toByte() &&
            audio[2] == 0xDF.toByte() && audio[3] == 0xA3.toByte()
    }

    // ─── WebM (Opus) Metadata Embedding ────────────────────────────────────────

    private fun ebmlSize(value: Long): ByteArray {
        var len = 1
        while (len < 8 && value >= (1L shl (7 * len)) - 1) len++
        val out = ByteArray(len)
        val marked = value or (1L shl (7 * len))
        for (i in 0 until len) out[i] = (marked shr (8 * (len - 1 - i))).toByte()
        return out
    }

    private fun ebmlElement(id: ByteArray, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id)
        out.write(ebmlSize(payload.size.toLong()))
        out.write(payload)
        return out.toByteArray()
    }

    private fun ebmlElement(id: ByteArray, text: String): ByteArray = ebmlElement(id, text.toByteArray(Charsets.UTF_8))

    private fun ids(vararg b: Int): ByteArray = ByteArray(b.size) { b[it].toByte() }

    private fun webmSimpleTag(name: String, value: String): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(ebmlElement(ids(0x45, 0xA3), name))
        body.write(ebmlElement(ids(0x44, 0x87), value))
        return ebmlElement(ids(0x67, 0xC8), body.toByteArray())
    }

    /**
     * WebM carries the Opus audio YouTube serves. Cover art is stored as a Matroska attachment and the text fields as
     * tags; both elements are appended to the end of the Segment, so no existing offsets need to move.
     */
    private fun embedWebmMetadata(
        audio: ByteArray,
        artwork: ByteArray?,
        lyrics: String?,
        title: String?,
        artist: String?,
        album: String?,
    ): ByteArray {
        fun readVint(at: Int): Pair<Long, Int>? {
            if (at >= audio.size) return null
            val first = audio[at].toInt() and 0xFF
            var len = 1
            var mask = 0x80
            while (len <= 8 && (first and mask) == 0) { len++; mask = mask shr 1 }
            if (len > 8 || at + len > audio.size) return null
            var v = (first and (mask - 1)).toLong()
            var allOnes = (first and (mask - 1)) == mask - 1
            for (i in 1 until len) {
                val b = audio[at + i].toInt() and 0xFF
                if (b != 0xFF) allOnes = false
                v = (v shl 8) or b.toLong()
            }
            return (if (allOnes) -1L else v) to len
        }

        // EBML header first, then the Segment.
        var pos = 4
        val (headerSize, headerLen) = readVint(pos) ?: return audio
        if (headerSize < 0) return audio
        pos += headerLen + headerSize.toInt()
        if (pos + 4 > audio.size ||
            audio[pos] != 0x18.toByte() || audio[pos + 1] != 0x53.toByte() ||
            audio[pos + 2] != 0x80.toByte() || audio[pos + 3] != 0x67.toByte()
        ) return audio
        val sizePos = pos + 4
        val (segSize, segLen) = readVint(sizePos) ?: return audio
        val dataStart = sizePos + segLen
        val segEnd = if (segSize < 0) audio.size else dataStart + segSize.toInt()
        if (segEnd > audio.size || segEnd < dataStart) return audio

        val extra = ByteArrayOutputStream()

        if (artwork != null && artwork.isNotEmpty()) {
            val isPng = artwork.size >= 8 && artwork[0] == 0x89.toByte() && artwork[1] == 0x50.toByte()
            val file = ByteArrayOutputStream()
            file.write(ebmlElement(ids(0x46, 0x6E), if (isPng) "cover.png" else "cover.jpg"))
            file.write(ebmlElement(ids(0x46, 0x60), if (isPng) "image/png" else "image/jpeg"))
            file.write(ebmlElement(ids(0x46, 0xAE), byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1)))
            file.write(ebmlElement(ids(0x46, 0x5C), artwork))
            extra.write(ebmlElement(ids(0x19, 0x41, 0xA4, 0x69), ebmlElement(ids(0x61, 0xA7), file.toByteArray())))
        }

        val simpleTags = ByteArrayOutputStream()
        if (!title.isNullOrBlank()) simpleTags.write(webmSimpleTag("TITLE", title))
        if (!artist.isNullOrBlank()) simpleTags.write(webmSimpleTag("ARTIST", artist))
        if (!album.isNullOrBlank()) simpleTags.write(webmSimpleTag("ALBUM", album))
        if (!lyrics.isNullOrBlank() && lyrics != "LYRICS_NOT_FOUND") simpleTags.write(webmSimpleTag("LYRICS", lyrics))
        if (simpleTags.size() > 0) {
            val tag = ByteArrayOutputStream()
            tag.write(ebmlElement(ids(0x63, 0xC0), ebmlElement(ids(0x68, 0xCA), byteArrayOf(50))))
            tag.write(simpleTags.toByteArray())
            extra.write(ebmlElement(ids(0x12, 0x54, 0xC3, 0x67), ebmlElement(ids(0x73, 0x73), tag.toByteArray())))
        }
        if (extra.size() == 0) return audio

        val extraBytes = extra.toByteArray()
        val result = ByteArrayOutputStream(audio.size + extraBytes.size)
        result.write(audio, 0, segEnd)
        result.write(extraBytes)
        result.write(audio, segEnd, audio.size - segEnd)
        val out = result.toByteArray()

        if (segSize >= 0) {
            // Rewrite the Segment size in place; it must still fit in the original number of size bytes.
            val newSize = segSize + extraBytes.size
            if (newSize >= (1L shl (7 * segLen)) - 1) return audio
            val marked = newSize or (1L shl (7 * segLen))
            for (i in 0 until segLen) out[sizePos + i] = (marked shr (8 * (segLen - 1 - i))).toByte()
        }
        return out
    }

    // ─── MP4 / M4A Metadata Embedding ──────────────────────────────────────────

    private fun embedM4aMetadata(
        audio: ByteArray,
        artwork: ByteArray?,
        lyrics: String?,
        title: String?,
        artist: String?,
        album: String?,
    ): ByteArray {
        val buffer = ByteBuffer.wrap(audio).order(ByteOrder.BIG_ENDIAN)
        var moovOffset = -1
        var moovSize = 0
        var mdatOffset = -1

        // Scan top-level atoms
        while (buffer.remaining() >= 8) {
            val offset = buffer.position()
            val size = buffer.int
            val typeBytes = ByteArray(4)
            buffer.get(typeBytes)
            val type = String(typeBytes, Charsets.US_ASCII)

            val atomSize = when (size) {
                0 -> audio.size - offset
                1 -> {
                    if (buffer.remaining() >= 8) buffer.long.toInt() else return audio
                }
                else -> size
            }

            if (atomSize < 8 || offset + atomSize > audio.size) break

            if (type == "moov") {
                moovOffset = offset
                moovSize = atomSize
            } else if (type == "mdat") {
                mdatOffset = offset
            }

            buffer.position(offset + atomSize)
        }

        if (moovOffset == -1 || moovSize < 8) return audio

        val tagBoxes = ByteArrayOutputStream()

        // 1. Artwork box: covr
        if (artwork != null && artwork.isNotEmpty()) {
            val isPng = artwork.size >= 8 && artwork[0] == 0x89.toByte() && artwork[1] == 0x50.toByte()
            val dataTypeFlag = if (isPng) 14 else 13
            val covrBox = createDataBox("covr", dataTypeFlag, artwork)
            tagBoxes.write(covrBox)
        }

        // 2. Lyrics box: ©lyr
        if (!lyrics.isNullOrBlank() && lyrics != "LYRICS_NOT_FOUND") {
            val lyrBox = createTextAtom(byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte()), lyrics)
            tagBoxes.write(lyrBox)
        }

        // 3. Title box: ©nam
        if (!title.isNullOrBlank()) {
            val namBox = createTextAtom(byteArrayOf(0xA9.toByte(), 'n'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte()), title)
            tagBoxes.write(namBox)
        }

        // 4. Artist box: ©ART
        if (!artist.isNullOrBlank()) {
            val artBox = createTextAtom(byteArrayOf(0xA9.toByte(), 'A'.code.toByte(), 'R'.code.toByte(), 'T'.code.toByte()), artist)
            tagBoxes.write(artBox)
        }

        // 5. Album box: ©alb
        if (!album.isNullOrBlank()) {
            val albBox = createTextAtom(byteArrayOf(0xA9.toByte(), 'a'.code.toByte(), 'l'.code.toByte(), 'b'.code.toByte()), album)
            tagBoxes.write(albBox)
        }

        val tagsBytes = tagBoxes.toByteArray()
        if (tagsBytes.isEmpty()) return audio

        // Locate or create udta -> meta -> ilst inside moov
        val moovBytes = audio.copyOfRange(moovOffset, moovOffset + moovSize)
        val modifiedMoov = insertTagsIntoMoov(moovBytes, tagsBytes) ?: return audio

        val deltaSize = modifiedMoov.size - moovSize

        // Adjust chunk offsets in stco / co64 if moov comes BEFORE mdat
        val finalMoov = if (moovOffset < mdatOffset && deltaSize != 0) {
            adjustChunkOffsets(modifiedMoov, deltaSize)
        } else {
            modifiedMoov
        }

        val out = ByteArrayOutputStream(audio.size + deltaSize)
        out.write(audio, 0, moovOffset)
        out.write(finalMoov)
        out.write(audio, moovOffset + moovSize, audio.size - (moovOffset + moovSize))
        return out.toByteArray()
    }

    private fun createTextAtom(atomType: ByteArray, text: String): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val dataPayload = ByteArrayOutputStream()
        val dataHeader = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        dataHeader.putInt(16 + textBytes.size)
        dataHeader.put("data".toByteArray(Charsets.US_ASCII))
        dataHeader.putInt(1) // type 1 = UTF-8 text
        dataHeader.putInt(0) // locale
        dataPayload.write(dataHeader.array())
        dataPayload.write(textBytes)

        val atomBox = ByteArrayOutputStream()
        val atomHeader = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        atomHeader.putInt(8 + dataPayload.size())
        atomHeader.put(atomType)
        atomBox.write(atomHeader.array())
        atomBox.write(dataPayload.toByteArray())
        return atomBox.toByteArray()
    }

    private fun createDataBox(atomTypeName: String, dataTypeFlag: Int, payload: ByteArray): ByteArray {
        val dataPayload = ByteArrayOutputStream()
        val dataHeader = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        val dataBoxSize = 16 + payload.size
        dataHeader.putInt(dataBoxSize)
        dataHeader.put("data".toByteArray(Charsets.US_ASCII))
        dataHeader.putInt(dataTypeFlag)
        dataHeader.putInt(0) // locale
        dataPayload.write(dataHeader.array())
        dataPayload.write(payload)

        val box = ByteArrayOutputStream()
        val boxHeader = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        val boxSize = 8 + dataPayload.size()
        boxHeader.putInt(boxSize)
        boxHeader.put(atomTypeName.toByteArray(Charsets.US_ASCII))
        box.write(boxHeader.array())
        box.write(dataPayload.toByteArray())
        return box.toByteArray()
    }

    private fun insertTagsIntoMoov(moov: ByteArray, tagsBytes: ByteArray): ByteArray? {
        val buf = ByteBuffer.wrap(moov).order(ByteOrder.BIG_ENDIAN)
        buf.position(8) // Skip moov header

        var udtaOffset = -1
        var udtaSize = 0

        while (buf.remaining() >= 8) {
            val pos = buf.position()
            val size = buf.int
            val typeBytes = ByteArray(4)
            buf.get(typeBytes)
            val type = String(typeBytes, Charsets.US_ASCII)

            if (size < 8 || pos + size > moov.size) break

            if (type == "udta") {
                udtaOffset = pos
                udtaSize = size
                break
            }
            buf.position(pos + size)
        }

        val newUdta: ByteArray = if (udtaOffset != -1) {
            insertTagsIntoUdta(moov.copyOfRange(udtaOffset, udtaOffset + udtaSize), tagsBytes)
        } else {
            createUdtaWithTags(tagsBytes)
        }

        val out = ByteArrayOutputStream()
        if (udtaOffset != -1) {
            out.write(moov, 0, udtaOffset)
            out.write(newUdta)
            out.write(moov, udtaOffset + udtaSize, moov.size - (udtaOffset + udtaSize))
        } else {
            out.write(moov, 0, moov.size)
            out.write(newUdta)
        }

        val result = out.toByteArray()
        ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN).putInt(0, result.size)
        return result
    }

    private fun insertTagsIntoUdta(udta: ByteArray, tagsBytes: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(udta).order(ByteOrder.BIG_ENDIAN)
        buf.position(8)

        var metaOffset = -1
        var metaSize = 0

        while (buf.remaining() >= 8) {
            val pos = buf.position()
            val size = buf.int
            val typeBytes = ByteArray(4)
            buf.get(typeBytes)
            val type = String(typeBytes, Charsets.US_ASCII)

            if (size < 8 || pos + size > udta.size) break

            if (type == "meta") {
                metaOffset = pos
                metaSize = size
                break
            }
            buf.position(pos + size)
        }

        val newMeta: ByteArray = if (metaOffset != -1) {
            insertTagsIntoMeta(udta.copyOfRange(metaOffset, metaOffset + metaSize), tagsBytes)
        } else {
            createMetaWithTags(tagsBytes)
        }

        val out = ByteArrayOutputStream()
        if (metaOffset != -1) {
            out.write(udta, 0, metaOffset)
            out.write(newMeta)
            out.write(udta, metaOffset + metaSize, udta.size - (metaOffset + metaSize))
        } else {
            out.write(udta, 0, udta.size)
            out.write(newMeta)
        }

        val result = out.toByteArray()
        ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN).putInt(0, result.size)
        return result
    }

    private fun insertTagsIntoMeta(meta: ByteArray, tagsBytes: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(meta).order(ByteOrder.BIG_ENDIAN)
        if (meta.size >= 12) buf.position(12) else buf.position(8)

        var ilstOffset = -1
        var ilstSize = 0

        while (buf.remaining() >= 8) {
            val pos = buf.position()
            val size = buf.int
            val typeBytes = ByteArray(4)
            buf.get(typeBytes)
            val type = String(typeBytes, Charsets.US_ASCII)

            if (size < 8 || pos + size > meta.size) break

            if (type == "ilst") {
                ilstOffset = pos
                ilstSize = size
                break
            }
            buf.position(pos + size)
        }

        val newIlst: ByteArray = if (ilstOffset != -1) {
            val ilst = meta.copyOfRange(ilstOffset, ilstOffset + ilstSize)
            val out = ByteArrayOutputStream()
            out.write(ilst, 0, ilst.size)
            out.write(tagsBytes)
            val res = out.toByteArray()
            ByteBuffer.wrap(res).order(ByteOrder.BIG_ENDIAN).putInt(0, res.size)
            res
        } else {
            val out = ByteArrayOutputStream()
            val h = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            h.putInt(8 + tagsBytes.size)
            h.put("ilst".toByteArray(Charsets.US_ASCII))
            out.write(h.array())
            out.write(tagsBytes)
            out.toByteArray()
        }

        val out = ByteArrayOutputStream()
        if (ilstOffset != -1) {
            out.write(meta, 0, ilstOffset)
            out.write(newIlst)
            out.write(meta, ilstOffset + ilstSize, meta.size - (ilstOffset + ilstSize))
        } else {
            out.write(meta, 0, meta.size)
            out.write(newIlst)
        }

        val result = out.toByteArray()
        ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN).putInt(0, result.size)
        return result
    }

    private fun createUdtaWithTags(tagsBytes: ByteArray): ByteArray {
        val meta = createMetaWithTags(tagsBytes)
        val out = ByteArrayOutputStream()
        val h = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        h.putInt(8 + meta.size)
        h.put("udta".toByteArray(Charsets.US_ASCII))
        out.write(h.array())
        out.write(meta)
        return out.toByteArray()
    }

    private fun createMetaWithTags(tagsBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val h = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        h.putInt(12 + 8 + tagsBytes.size)
        h.put("meta".toByteArray(Charsets.US_ASCII))
        h.putInt(0) // 4 bytes version & flags

        val ilstH = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        ilstH.putInt(8 + tagsBytes.size)
        ilstH.put("ilst".toByteArray(Charsets.US_ASCII))

        out.write(h.array())
        out.write(ilstH.array())
        out.write(tagsBytes)
        return out.toByteArray()
    }

    private fun adjustChunkOffsets(moov: ByteArray, delta: Int): ByteArray {
        val copy = moov.clone()
        val buf = ByteBuffer.wrap(copy).order(ByteOrder.BIG_ENDIAN)

        fun scan(offset: Int, length: Int) {
            var pos = offset
            while (pos + 8 <= offset + length) {
                buf.position(pos)
                val size = buf.int
                val typeBytes = ByteArray(4)
                buf.get(typeBytes)
                val type = String(typeBytes, Charsets.US_ASCII)
                if (size < 8 || pos + size > offset + length) break

                if (type == "stco" && size >= 16) {
                    buf.position(pos + 12)
                    val count = buf.int
                    for (i in 0 until count) {
                        if (buf.remaining() < 4) break
                        val curPos = buf.position()
                        val currentOffset = buf.int
                        buf.putInt(curPos, currentOffset + delta)
                    }
                } else if (type in listOf("trak", "mdia", "minf", "stbl")) {
                    scan(pos + 8, size - 8)
                }
                pos += size
            }
        }

        scan(8, copy.size - 8)
        return copy
    }
}
