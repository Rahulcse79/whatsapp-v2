package com.whatsappv2.data.sip.recording

import com.whatsappv2.data.sip.recording.stack.RecordingFileNames
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingFileNamesTest {

    @Test
    fun `the plaintext name is one the stack will open a writer for`() {
        // pjsua_recorder_create reads the last four characters: .wav or .mp3, else
        // PJ_ENOTSUP. The old ".tmp" recorded nothing on the TC15 while the UI said it was.
        val name = RecordingFileNames.plaintext("3f1c")
        assertTrue(RecordingFileNames.stackCanWrite(name), name)
        assertTrue(RecordingFileNames.isUnsealed(name), name)
    }

    @Test
    fun `a sealed recording is not swept as unsealed`() {
        assertFalse(RecordingFileNames.isUnsealed("3f1c__call__1__2.rec"))
        assertFalse(RecordingFileNames.stackCanWrite("3f1c.tmp"))
    }
}
