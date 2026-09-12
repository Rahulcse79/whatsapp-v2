package com.whatsappv2.data.sip.recording

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.recording.Recording
import com.whatsappv2.domain.recording.RecordingError
import com.whatsappv2.domain.recording.RecordingId

/**
 * Where recordings live, and the only thing that knows (Task 58, §7).
 *
 * ## Why the path is allocated here and never chosen by a caller
 *
 * A recording of a phone call is the most sensitive artefact this app can produce — it
 * contains whatever was said, including the DTMF that §7 forbids logging. Every rule about
 * it is a rule about *where the bytes are*: encrypted at rest, inside the app sandbox,
 * excluded from backup, deleted on a retention cutoff. A caller that could name the file
 * could put it somewhere none of those rules apply.
 *
 * So [allocate] hands out a path the store chose, and [seal] is what turns the stack's
 * plaintext output into the encrypted artefact that is kept. Nothing above this interface
 * ever sees a filename — `CallRecorder` deliberately returns [Recording] without one.
 *
 * ## Seam, not indirection
 *
 * The Android Keystore does not run on the JVM. Behind this interface the real store
 * encrypts; in tests a fake one does not, so [PjsipCallRecorder]'s consent gate,
 * lifecycle and bookkeeping are exercised without a device — which is where the rules that
 * matter actually live.
 */
internal interface RecordingStore {

    /**
     * Removes plaintext left behind by a crash mid-recording.
     *
     * File I/O, so it must not run on the main thread -- which is where it ran when the
     * store did it from its constructor, because Hilt builds a singleton on whichever
     * thread first asks for it. The recorder calls this when the stack starts, on the I/O
     * dispatcher. Implementations run the sweep at most once per process, and run it
     * themselves before the first [allocate] if nothing has called this yet: a sweep that
     * came *after* a fresh allocation would delete the recording that had just started.
     */
    fun sweepAbandoned()

    /**
     * A path for a new recording of [callId], plus the id it will be known by.
     *
     * The file does not exist yet; the stack creates it. Failing here means storage is
     * unusable, which is a refusal the user can act on ("free some space") rather than a
     * silent non-recording.
     */
    fun allocate(callId: CallId): Outcome<AllocatedRecording, RecordingError>

    /**
     * Gives back a path [allocate] handed out, for a recording that never started.
     *
     * Not [seal] with a shrug: the stack may have created the file and written a WAV
     * header before it refused, and a header sealed is an artefact of nothing that then
     * has to be listed, shown and deleted like a real recording. This destroys it
     * instead. Quiet when the file was never created, which is the usual case.
     */
    fun discard(allocated: AllocatedRecording)

    /**
     * Encrypts the finished file in place and returns what was kept.
     *
     * Called once the stack has closed the file. The plaintext is destroyed as part of
     * this: an unencrypted copy left beside the encrypted one is the whole protection
     * undone, and it is exactly what a crash between the two steps would leave behind —
     * so implementations delete before they report success.
     *
     * Returns null when the stack wrote nothing, which happens for a recording stopped
     * within a few milliseconds of starting. Not an error: there is simply no artefact.
     */
    fun seal(allocated: AllocatedRecording, startedAtEpochMillis: Long, endedAtEpochMillis: Long):
        Outcome<Recording?, RecordingError>

    /** Every recording still held, newest first. */
    fun list(): List<Recording>

    /** Deletes one recording's bytes. Succeeds quietly if it is already gone. */
    fun delete(id: RecordingId): Outcome<Unit, RecordingError>

    /** Deletes everything started before [cutoffEpochMillis]; returns how many went. */
    fun purgeOlderThan(cutoffEpochMillis: Long): Outcome<Int, RecordingError>
}

/**
 * A path the stack may write to, and the id the result will carry.
 *
 * `internal` and confined to `:data:sip`: this is the one place a recording's location is
 * a value, and it must not become one anywhere else.
 */
internal data class AllocatedRecording(
    val id: RecordingId,
    val callId: CallId,
    val plaintextPath: String,
)
