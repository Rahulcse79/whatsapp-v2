package com.whatsappv2.domain.recording

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.model.CallId
import kotlinx.coroutines.flow.StateFlow

/** Identifies one recording. Opaque; do not parse it. */
@JvmInline
value class RecordingId(val value: String) {
    init {
        require(value.isNotBlank()) { "RecordingId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * One finished recording, as the app knows it.
 *
 * Carries no path. A caller that could ask this for a filename could hand that filename
 * to something outside the app, and a recording of a phone call is the last thing that
 * should travel by file path (§7). Where the bytes live and how they are encrypted is
 * `:data`'s business, and only `:data`'s.
 */
data class Recording(
    val id: RecordingId,
    val callId: CallId,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
    val sizeBytes: Long,
) {
    val durationMillis: Long get() = (endedAtEpochMillis - startedAtEpochMillis).coerceAtLeast(0)
}

/** Why a recording could not be started or stopped. */
sealed interface RecordingError {

    /** [RecordingPolicy] refused. Carries the refusal so the UI can name it. */
    data class Refused(val refusal: RecordingRefusal) : RecordingError

    /** Storage would not take it — no space, or the encrypted store could not be opened. */
    data class StorageUnavailable(val detail: String) : RecordingError

    /** The stack refused to start or stop the capture. */
    data class EngineRefused(val detail: String) : RecordingError
}

/**
 * Recording a call, behind a port (Task 58, §2.6, §7).
 *
 * ## An architecture, not a feature
 *
 * Task 58 asks for the shape and the safeguards, not for a shipped recorder — and the
 * safeguards are the point. Everything that makes recording dangerous is decided above
 * this interface: [RecordingPolicy] gates the start, [RecordingConsent] is per call and
 * begins at none, and [active] is what keeps an indicator on screen for the whole
 * duration. An implementation cannot record without passing through all three, because
 * there is no other way in.
 *
 * ## The platform limit, stated rather than worked around
 *
 * Android does not let a normal app capture the far end of a call from the system audio
 * path; `MediaRecorder.AudioSource.VOICE_CALL` is refused outside privileged builds. What
 * *is* possible is recording the SIP media this app itself handles, which the SIP stack
 * does — and that is what an implementation records. The distinction matters legally and
 * is written down in `docs/security.md` rather than buried here: a user told "call
 * recording" who receives one side of the conversation has been misled.
 *
 * ## Storage
 *
 * Implementations write **encrypted at rest** and **excluded from backup** (§7, DoD 12).
 * A recording is the most sensitive artefact this app can produce — it contains whatever
 * was said, including the DTMF that was never logged — and a backup copies it off the
 * device to somewhere none of these rules apply.
 */
interface CallRecorder {

    /**
     * Calls being recorded right now.
     *
     * A [StateFlow] and not an event, because Task 58's second done-when is about
     * *duration*: the indicator must be present for the whole of a recording, and a UI
     * built on start/stop events shows nothing at all after a process restart. A screen
     * that renders from this cannot get out of step with what is being written.
     */
    val active: StateFlow<Set<CallId>>

    /**
     * Begins recording [callId], having been given [consent].
     *
     * Consent is a parameter rather than something this looks up, so no implementation
     * can find a path that starts without one. It is still checked — against
     * [RecordingPolicy] — because a caller passing the wrong call's consent is exactly
     * the bug that would otherwise ship silently.
     */
    suspend fun start(callId: CallId, consent: RecordingConsent): Outcome<RecordingId, RecordingError>

    /** Stops recording [callId] and closes the file. Succeeds quietly if it was not recording. */
    suspend fun stop(callId: CallId): Outcome<Recording?, RecordingError>

    /** Every recording still held on the device, newest first. */
    suspend fun recordings(): List<Recording>

    /** Deletes one recording and its bytes. Succeeds quietly if it is already gone. */
    suspend fun delete(id: RecordingId): Outcome<Unit, RecordingError>

    /**
     * Deletes everything recorded before [cutoffEpochMillis] — the retention hook.
     *
     * A hook rather than a schedule: what the retention period *is* belongs to whoever
     * deploys this app, and in several jurisdictions to their regulator. Hard-coding
     * ninety days here would be this app inventing a legal position for its operator.
     *
     * @return how many recordings were removed.
     */
    suspend fun purgeOlderThan(cutoffEpochMillis: Long): Outcome<Int, RecordingError>
}
