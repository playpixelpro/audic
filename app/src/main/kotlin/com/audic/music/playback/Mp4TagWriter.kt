package com.audic.music.playback

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Writes iTunes-style metadata (`moov/udta/meta/ilst`) into an MP4/M4A file.
 *
 * Used instead of ID3v2, which is only valid at the head of an MP3/ADTS stream —
 * prepending it to an MP4 would push `ftyp` off offset 0 and corrupt the file.
 *
 * Safe to run on MediaMuxer/Transformer output because that writer places `moov`
 * last: sample offsets in `stco` point backwards into `mdat`, so growing `moov`
 * cannot invalidate them. If the file is not shaped that way, [writeTags] changes
 * nothing and returns false, leaving a valid untagged file behind.
 */
object Mp4TagWriter {

    private const val TYPE_UTF8 = 1
    private const val TYPE_JPEG = 13
    private const val TYPE_PNG = 14

    /** `©` is the raw byte 0xA9 in atom names — never UTF-8 encode it (that yields 2 bytes). */
    private val NAME_TITLE = atom(0xA9, "nam")
    private val NAME_ARTIST = atom(0xA9, "ART")
    private val NAME_ALBUM = atom(0xA9, "alb")
    private val NAME_YEAR = atom(0xA9, "day")
    private val NAME_COVER = "covr".toByteArray(Charsets.US_ASCII)

    private val BOX_MOOV = "moov".toByteArray(Charsets.US_ASCII)
    private val BOX_UDTA = "udta".toByteArray(Charsets.US_ASCII)
    private val BOX_META = "meta".toByteArray(Charsets.US_ASCII)
    private val BOX_ILST = "ilst".toByteArray(Charsets.US_ASCII)
    private val BOX_DATA = "data".toByteArray(Charsets.US_ASCII)

    /** Fixed 33-byte `hdlr` identifying the metadata as iTunes ('mdir'/'appl'). */
    private val HDLR_BOX: ByteArray = run {
        val payload = ByteArray(25) // vflags(4) predefined(4) 'mdir'(4) 'appl'(4) reserved(8) name(1)
        "mdir".toByteArray(Charsets.US_ASCII).copyInto(payload, 8)
        "appl".toByteArray(Charsets.US_ASCII).copyInto(payload, 12)
        box("hdlr".toByteArray(Charsets.US_ASCII), payload)
    }

    /**
     * @return true if tags were written, false if the file was left untouched.
     */
    fun writeTags(
        file: File,
        title: String,
        artist: String,
        album: String,
        year: Int?,
        coverArt: ByteArray?,
    ): Boolean {
        val bytes = file.readBytes()

        val topLevel = walk(bytes, 0, bytes.size) ?: return false
        val moov = topLevel.lastOrNull { it.type == "moov" } ?: return false
        // Growing moov is only safe when nothing follows it.
        if (moov.end != bytes.size) return false

        val ilst = buildIlst(title, artist, album, year, coverArt)
        if (ilst.isEmpty()) return false

        val children = walk(bytes, moov.start + moov.headerSize, moov.end) ?: return false

        // meta is a FullBox: 4 zero bytes of version+flags before its children.
        val meta = box(BOX_META, byteArrayOf(0, 0, 0, 0) + HDLR_BOX + box(BOX_ILST, ilst))
        val udta = box(BOX_UDTA, meta)

        val payload = ByteArrayOutputStream()
        // Drop any udta the muxer already wrote rather than emitting a second one.
        children.filter { it.type != "udta" }
            .forEach { payload.write(bytes, it.start, it.end - it.start) }
        payload.write(udta)

        val newMoov = box(BOX_MOOV, payload.toByteArray())
        file.outputStream().use { out ->
            out.write(bytes, 0, moov.start)
            out.write(newMoov)
        }
        return true
    }

    private fun buildIlst(
        title: String,
        artist: String,
        album: String,
        year: Int?,
        coverArt: ByteArray?,
    ): ByteArray {
        val ilst = ByteArrayOutputStream()
        ilst.write(textItem(NAME_TITLE, title))
        ilst.write(textItem(NAME_ARTIST, artist))
        ilst.write(textItem(NAME_ALBUM, album))
        ilst.write(textItem(NAME_YEAR, year?.toString().orEmpty()))
        if (coverArt != null && coverArt.isNotEmpty()) {
            // Google artwork URLs can serve WebP, which `covr` cannot hold — skip rather
            // than write an image no player can decode.
            imageType(coverArt)?.let { type ->
                ilst.write(box(NAME_COVER, dataBox(type, coverArt)))
            }
        }
        return ilst.toByteArray()
    }

    private fun imageType(image: ByteArray): Int? = when {
        image.size >= 3 && image[0] == 0xFF.toByte() &&
            image[1] == 0xD8.toByte() && image[2] == 0xFF.toByte() -> TYPE_JPEG

        image.size >= 4 && image[0] == 0x89.toByte() && image[1] == 'P'.code.toByte() &&
            image[2] == 'N'.code.toByte() && image[3] == 'G'.code.toByte() -> TYPE_PNG

        else -> null
    }

    private fun textItem(name: ByteArray, value: String): ByteArray =
        if (value.isBlank()) ByteArray(0)
        else box(name, dataBox(TYPE_UTF8, value.toByteArray(Charsets.UTF_8)))

    private fun dataBox(typeIndicator: Int, value: ByteArray): ByteArray {
        val payload = ByteArray(8 + value.size) // typeIndicator(4) locale(4) value
        writeU32(payload, 0, typeIndicator)
        value.copyInto(payload, 8)
        return box(BOX_DATA, payload)
    }

    private fun box(type: ByteArray, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        writeU32(out, 0, out.size)
        type.copyInto(out, 4)
        payload.copyInto(out, 8)
        return out
    }

    private fun atom(prefix: Int, rest: String): ByteArray =
        byteArrayOf(prefix.toByte()) + rest.toByteArray(Charsets.US_ASCII)

    private fun writeU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun readU32(source: ByteArray, offset: Int): Long =
        ((source[offset].toLong() and 0xFF) shl 24) or
            ((source[offset + 1].toLong() and 0xFF) shl 16) or
            ((source[offset + 2].toLong() and 0xFF) shl 8) or
            (source[offset + 3].toLong() and 0xFF)

    private fun readU64(source: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (source[offset + i].toLong() and 0xFF)
        return value
    }

    private class Box(val start: Int, val end: Int, val headerSize: Int, val type: String)

    /** Returns the boxes spanning exactly [from], [to], or null if the range isn't well-formed. */
    private fun walk(bytes: ByteArray, from: Int, to: Int): List<Box>? {
        val boxes = ArrayList<Box>()
        var offset = from
        while (offset + 8 <= to) {
            var size = readU32(bytes, offset)
            var headerSize = 8
            if (size == 1L) { // 64-bit largesize follows the type
                if (offset + 16 > to) return null
                size = readU64(bytes, offset + 8)
                headerSize = 16
            }
            if (size < headerSize || offset + size > to) return null
            boxes.add(
                Box(
                    start = offset,
                    end = (offset + size).toInt(),
                    headerSize = headerSize,
                    type = String(bytes, offset + 4, 4, Charsets.US_ASCII),
                )
            )
            offset += size.toInt()
        }
        return if (offset == to) boxes else null
    }
}
