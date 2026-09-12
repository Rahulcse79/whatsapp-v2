package com.whatsappv2.data.sip.recording.stack

/**
 * The one rule about a recording's plaintext file name that the stack imposes.
 *
 * `pjsua_recorder_create` chooses its writer by the file name's **last four characters**:
 * `.wav` and `.mp3` are accepted and anything else is `PJ_ENOTSUP` — no recorder, no
 * file. The store used to name the plaintext `<id>.tmp`, so every recording on the TC15
 * showed "Recording this call" over a recorder that was never created and sealed nothing
 * (2026-09-11). The "unsealed" marker therefore sits *before* the extension, and both the
 * store's sweep and this object read it from there.
 *
 * Pure and tested on the JVM, because the store itself needs the Android Keystore.
 */
internal object RecordingFileNames {

    /** What the stack must see at the end of the name, and what [isUnsealed] looks for. */
    const val PLAINTEXT_SUFFIX = ".unsealed.wav"

    fun plaintext(id: String): String = "$id$PLAINTEXT_SUFFIX"

    fun isUnsealed(fileName: String): Boolean = fileName.endsWith(PLAINTEXT_SUFFIX)

    /** True when PJSIP will open a writer for this name. */
    fun stackCanWrite(fileName: String): Boolean =
        fileName.endsWith(".wav", ignoreCase = true) || fileName.endsWith(".mp3", ignoreCase = true)
}
