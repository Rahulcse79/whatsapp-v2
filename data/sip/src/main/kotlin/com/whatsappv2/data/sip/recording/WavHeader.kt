package com.whatsappv2.data.sip.recording

/**
 * Repairs the size fields in a WAV header (Task 58 playback).
 *
 * ## Why this is needed at all
 *
 * PJSIP's `AudioMediaRecorder` writes the 44-byte header up front with the RIFF size and
 * the `data` chunk size left at **zero**, and only backpatches them from the real length
 * when the recorder is destroyed cleanly. Sealing reads the file right after the call
 * ends, and at that moment the backpatch has often not happened — so the sealed recording,
 * and the copy decrypted from it, carry a header that says "zero samples". `MediaPlayer`
 * believes it: duration `0:00`, playback ends in a few milliseconds, and the recording
 * looks unplayable. This recomputes both sizes from the bytes actually present, which is
 * the truth the header should have carried.
 *
 * Pure and byte-only, so it is tested on the JVM without a device or a real file.
 */
internal object WavHeader {

    /** The minimum canonical PCM header: RIFF(12) + fmt (24) + data chunk header (8). */
    const val MIN_HEADER_BYTES = 44

    private const val RIFF_SIZE_OFFSET = 4
    private const val WAVE_TAG_OFFSET = 8
    private const val FIRST_CHUNK_OFFSET = 12
    private const val WORD = 4

    /** Bytes before the RIFF size counts from: the "RIFF" tag and the size field itself. */
    private const val RIFF_PREFIX = 8

    /** Chunk ids are 4-aligned, but a malformed one can sit on a 2-byte boundary. */
    private const val SCAN_STEP = 2

    /**
     * Returns a corrected copy of [head], or null when nothing needs doing.
     *
     * Null means either "not a canonical PCM WAV this should touch" or "the sizes are
     * already right" — in both cases the caller leaves the file alone. [head] must contain
     * at least through the `data` chunk's size field; [fileLength] is the whole file.
     */
    fun corrected(head: ByteArray, fileLength: Long): ByteArray? {
        val dataSizeOffset = dataSizeOffsetOrNull(head, fileLength) ?: return null

        // The RIFF size counts everything after the 8-byte RIFF header; the data size
        // counts only the PCM bytes, which begin right after this size field.
        val realRiff = fileLength - RIFF_PREFIX
        val realData = fileLength - (dataSizeOffset + WORD)
        val alreadyRight = realRiff <= Int.MAX_VALUE && realData >= 0 &&
            leInt(head, RIFF_SIZE_OFFSET).toLong() == realRiff &&
            leInt(head, dataSizeOffset).toLong() == realData
        if (realRiff > Int.MAX_VALUE || realData < 0 || alreadyRight) return null

        return head.copyOf().also {
            putLeInt(it, RIFF_SIZE_OFFSET, realRiff.toInt())
            putLeInt(it, dataSizeOffset, realData.toInt())
        }
    }

    /** The offset of the `data` chunk's size field, or null when this is not a WAV to touch. */
    private fun dataSizeOffsetOrNull(head: ByteArray, fileLength: Long): Int? {
        if (fileLength < MIN_HEADER_BYTES || head.size < MIN_HEADER_BYTES) return null
        if (tag(head, 0) != "RIFF" || tag(head, WAVE_TAG_OFFSET) != "WAVE") return null
        val dataIdOffset = indexOfData(head) ?: return null
        val dataSizeOffset = dataIdOffset + WORD
        return if (dataSizeOffset + WORD <= head.size) dataSizeOffset else null
    }

    /** The offset of the `data` chunk id, scanning past fmt and any extension chunks. */
    private fun indexOfData(head: ByteArray): Int? {
        var i = FIRST_CHUNK_OFFSET
        while (i + WORD <= head.size) {
            if (tag(head, i) == "data") return i
            i += SCAN_STEP
        }
        return null
    }

    private fun tag(b: ByteArray, at: Int): String = String(b, at, WORD, Charsets.US_ASCII)

    @Suppress("MagicNumber") // Little-endian byte packing; the shifts are the definition.
    private fun leInt(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)

    @Suppress("MagicNumber") // Little-endian byte packing; the shifts are the definition.
    private fun putLeInt(b: ByteArray, at: Int, value: Int) {
        b[at] = (value and 0xFF).toByte()
        b[at + 1] = ((value shr 8) and 0xFF).toByte()
        b[at + 2] = ((value shr 16) and 0xFF).toByte()
        b[at + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
