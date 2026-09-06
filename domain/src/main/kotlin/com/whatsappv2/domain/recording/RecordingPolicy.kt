package com.whatsappv2.domain.recording

import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.model.CallId

/** Why a recording may not start. Each case is something the UI can say out loud. */
sealed interface RecordingRefusal {

    /** Nobody has consented on this call (§2.6). */
    data object NoConsent : RecordingRefusal

    /** Consent was given for a different call, so it does not carry to this one. */
    data class ConsentForAnotherCall(val grantedFor: CallId) : RecordingRefusal

    /** The call has no media to record yet, or no longer has any. */
    data class CallNotEstablished(val state: CallState) : RecordingRefusal

    /** The platform will not let this app capture the other party (see `docs/security.md`). */
    data object NotSupportedOnThisPlatform : RecordingRefusal
}

/**
 * The one gate a recording has to pass (Task 58, §2.6).
 *
 * Pure, and deliberately the only thing that can answer the question. A recorder that
 * checked consent itself would have the check and the capture in the same class, and the
 * two would be one refactor away from being one step apart. Here the decision is a
 * function of values a test can enumerate, and `CallRecorder` implementations are handed
 * an answer rather than trusted to ask.
 */
object RecordingPolicy {

    /**
     * Whether [callId] may start recording, or why not.
     *
     * The order of the checks is the order of the reasons a user would want to hear:
     * consent first, because it is the one that is about them rather than about the phone.
     */
    fun mayRecord(
        callId: CallId,
        state: CallState,
        consent: RecordingConsent,
        platformSupported: Boolean,
    ): RecordingRefusal? = when {
        consent is RecordingConsent.None -> RecordingRefusal.NoConsent

        consent is RecordingConsent.GrantedByLocalUser && consent.callId != callId ->
            RecordingRefusal.ConsentForAnotherCall(consent.callId)

        !platformSupported -> RecordingRefusal.NotSupportedOnThisPlatform

        // Established, not merely connected: a held call still has a dialog and a file
        // being written, and stopping the recording because somebody pressed hold would
        // silently produce a recording with a hole in it.
        !state.isEstablished -> RecordingRefusal.CallNotEstablished(state)

        else -> null
    }

    /**
     * Whether a recording already running must now stop.
     *
     * The mirror of [mayRecord], and separate from it because the answers differ: a call
     * that ends stops the recording, but so does a call that leaves the established
     * states for any other reason. Asking one question in both directions is how a
     * recording survives the end of the call it belongs to.
     */
    fun mustStop(state: CallState): Boolean = !state.isEstablished
}
