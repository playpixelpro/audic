package com.audic.music.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class Mp4TagWriterTest {

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        val size = out.size
        out[0] = (size ushr 24).toByte()
        out[1] = (size ushr 16).toByte()
        out[2] = (size ushr 8).toByte()
        out[3] = size.toByte()
        type.toByteArray(Charsets.US_ASCII).copyInto(out, 4)
        payload.copyInto(out, 8)
        return out
    }

    /** ftyp + mdat + moov, with moov last — the shape MediaMuxer/Transformer emits. */
    private fun sampleMp4(): ByteArray = ByteArrayOutputStream().apply {
        write(preamble)
        write(box("moov", box("mvhd", ByteArray(100))))
    }.toByteArray()

    /** Everything before moov: the bytes stco offsets point into, which must never move. */
    private val preamble: ByteArray = ByteArrayOutputStream().apply {
        write(box("ftyp", "M4A isom".toByteArray(Charsets.US_ASCII)))
        write(box("mdat", ByteArray(64) { it.toByte() }))
    }.toByteArray()

    private fun topLevelTypes(bytes: ByteArray): List<String> {
        val types = mutableListOf<String>()
        var offset = 0
        while (offset + 8 <= bytes.size) {
            val size = ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
            types.add(String(bytes, offset + 4, 4, Charsets.US_ASCII))
            offset += size
        }
        assertEquals("boxes must span the file exactly", bytes.size, offset)
        return types
    }

    private fun tempFile(bytes: ByteArray): File =
        File.createTempFile("tagtest", ".m4a").apply { writeBytes(bytes); deleteOnExit() }

    @Test
    fun `writes tags without disturbing audio payload`() {
        val original = sampleMp4()
        val file = tempFile(original)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x11, 0x22)

        assertTrue(Mp4TagWriter.writeTags(file, "Title", "Artist", "Album", 1999, jpeg))

        val result = file.readBytes()
        // Everything ahead of moov — crucially the mdat samples stco points at — is byte-identical.
        assertArrayEquals(preamble, result.copyOfRange(0, preamble.size))
        assertTrue("moov should have grown", result.size > original.size)
        assertEquals(listOf("ftyp", "mdat", "moov"), topLevelTypes(result))

        val text = String(result, Charsets.ISO_8859_1)
        assertTrue("udta/meta/ilst missing", text.contains("udta") && text.contains("ilst"))
        assertTrue("hdlr must mark iTunes metadata", text.contains("mdir"))
        assertTrue(text.contains("Title") && text.contains("Artist") && text.contains("Album"))
        assertTrue("cover art missing", text.contains("covr"))
        // `©` must be the raw 0xA9 byte, not UTF-8 encoded to 0xC2 0xA9.
        assertTrue(text.contains("©nam"))
        assertFalse(text.contains("Â©nam"))
    }

    @Test
    fun `leaves file untouched when moov is not last`() {
        val moovFirst = ByteArrayOutputStream().apply {
            write(box("ftyp", "M4A isom".toByteArray(Charsets.US_ASCII)))
            write(box("moov", box("mvhd", ByteArray(100))))
            write(box("mdat", ByteArray(64)))
        }.toByteArray()
        val file = tempFile(moovFirst)

        // Growing moov here would shift mdat and invalidate stco offsets, so it must bail.
        assertFalse(Mp4TagWriter.writeTags(file, "Title", "Artist", "Album", 1999, null))
        assertArrayEquals(moovFirst, file.readBytes())
    }

    @Test
    fun `skips unsupported cover art format`() {
        val file = tempFile(sampleMp4())
        val webp = "RIFF____WEBPVP8 ".toByteArray(Charsets.US_ASCII)

        assertTrue(Mp4TagWriter.writeTags(file, "Title", "", "", null, webp))

        val text = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse("WebP cannot live in covr", text.contains("covr"))
        assertTrue(text.contains("Title"))
    }

    @Test
    fun `does nothing when there is no metadata to write`() {
        val original = sampleMp4()
        val file = tempFile(original)

        assertFalse(Mp4TagWriter.writeTags(file, "", "", "", null, null))
        assertArrayEquals(original, file.readBytes())
    }
}
