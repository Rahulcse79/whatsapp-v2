package com.whatsappv2.data.sip.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The WAV size repair, in bytes.
 *
 * The bug it fixes: PJSIP leaves the RIFF and data sizes at zero, MediaPlayer reads that
 * as no samples, and the recording will not play. These assert the header is rewritten to
 * the file's real length, and left alone when there is nothing to do.
 */
class WavHeaderTest {

    @Test
    fun `zero sizes are rewritten from the real file length`() {
        val header = pcmHeader(riffSize = 0, dataSize = 0)
        val fileLength = 44L + PCM_BYTES

        val fixed = WavHeader.corrected(header, fileLength)!!

        assertEquals((fileLength - 8).toInt(), leInt(fixed, 4), "RIFF size")
        assertEquals(PCM_BYTES.toInt(), leInt(fixed, 40), "data size")
    }

    @Test
    fun `a header already correct is left alone`() {
        val fileLength = 44L + PCM_BYTES
        val header = pcmHeader(riffSize = (fileLength - 8).toInt(), dataSize = PCM_BYTES.toInt())

        assertNull(WavHeader.corrected(header, fileLength))
    }

    @Test
    fun `a truncated or non-WAV buffer is not touched`() {
        assertNull(WavHeader.corrected(ByteArray(10), 10))
        val notRiff = pcmHeader(0, 0).also { "JUNK".toByteArray().copyInto(it, 0) }
        assertNull(WavHeader.corrected(notRiff, 44L + PCM_BYTES))
    }

    @Test
    fun `the data chunk is found past an extra chunk before it`() {
        // A LIST chunk between fmt and data must not stop the scan.
        val header = pcmHeaderWithListChunk()
        val fileLength = header.size.toLong() + PCM_BYTES

        val fixed = WavHeader.corrected(header, fileLength)!!
        val dataIdx = String(fixed, Charsets.US_ASCII).indexOf("data")
        assertEquals(PCM_BYTES.toInt(), leInt(fixed, dataIdx + 4))
    }

    private fun pcmHeader(riffSize: Int, dataSize: Int): ByteArray {
        val b = ByteArray(44)
        "RIFF".toByteArray().copyInto(b, 0)
        putLeInt(b, 4, riffSize)
        "WAVE".toByteArray().copyInto(b, 8)
        "fmt ".toByteArray().copyInto(b, 12)
        putLeInt(b, 16, 16)
        putLeShort(b, 20, 1)
        putLeShort(b, 22, 1)
        putLeInt(b, 24, 48_000)
        putLeInt(b, 28, 96_000)
        putLeShort(b, 32, 2)
        putLeShort(b, 34, 16)
        "data".toByteArray().copyInto(b, 36)
        putLeInt(b, 40, dataSize)
        return b
    }

    private fun pcmHeaderWithListChunk(): ByteArray {
        val list = ByteArray(12).also {
            "LIST".toByteArray().copyInto(it, 0)
            putLeInt(it, 4, 4)
            "INFO".toByteArray().copyInto(it, 8)
        }
        val base = pcmHeader(0, 0)
        return base.copyOfRange(0, 36) + list + base.copyOfRange(36, 44)
    }

    @Suppress("MagicNumber")
    private fun leInt(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    @Suppress("MagicNumber")
    private fun putLeInt(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v shr 8) and 0xFF).toByte()
        b[at + 2] = ((v shr 16) and 0xFF).toByte()
        b[at + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun putLeShort(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private companion object {
        const val PCM_BYTES = 480_000L
    }
}
