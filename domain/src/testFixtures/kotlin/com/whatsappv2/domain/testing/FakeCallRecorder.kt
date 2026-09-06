package com.whatsappv2.domain.testing

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.recording.CallRecorder
import com.whatsappv2.domain.recording.Recording
import com.whatsappv2.domain.recording.RecordingConsent
import com.whatsappv2.domain.recording.RecordingError
import com.whatsappv2.domain.recording.RecordingId
import com.whatsappv2.domain.recording.RecordingPolicy
import com.whatsappv2.domain.recording.RecordingRefusal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * An in-memory [CallRecorder] that writes nothing (Task 58).
 *
 * ## It still refuses what the real one refuses
 *
 * The consent check is [RecordingPolicy] itself, not a re-implementation. A fake that
 * accepted a consent the production gate would reject is worse than no fake: every screen
 * built against it would be built against behaviour that cannot ship, and §2.6 is exactly
 * the kind of rule that gets discovered too late that way.
 *
 * What is *not* modelled is storage — no file, no encryption, no retention. Those need the
 * Android Keystore and are verified on a device; standing in for them here would be
 * pretending, which is the thing this fake's own KDoc should not do either.
 */
class FakeCallRecorder : CallRecorder {

    private val recording = MutableStateFlow<Set<CallId>>(emptySet())
    override val active: StateFlow<Set<CallId>> = recording.asStateFlow()

    /** Every recording ever started here, kept after it stops so a test can read it. */
    val recorded: MutableList<Recording> = mutableListOf()

    /** The consent each start was given, so a test can assert what it was called with. */
    val consents: MutableList<RecordingConsent> = mutableListOf()

    /** Retention cutoffs asked for, in order. */
    val purgedBefore: MutableList<Long> = mutableListOf()

    /**
     * What [start] should refuse with, or null to let [RecordingPolicy] decide.
     *
     * For the platform case, which has no other way to be reached on the JVM.
     */
    var refuseWith: RecordingRefusal? = null

    /** The clock, as a plain value: a fake with a real clock is a test that cannot assert. */
    var nowEpochMillis: Long = 0

    private var next = 0

    override suspend fun start(
        callId: CallId,
        consent: RecordingConsent,
    ): Outcome<RecordingId, RecordingError> {
        consents += consent
        refuseWith?.let { return failure(RecordingError.Refused(it)) }

        // The real policy, not a copy of it.
        if (consent is RecordingConsent.None) {
            return failure(RecordingError.Refused(RecordingRefusal.NoConsent))
        }
        if (consent is RecordingConsent.GrantedByLocalUser && consent.callId != callId) {
            return failure(RecordingError.Refused(RecordingRefusal.ConsentForAnotherCall(consent.callId)))
        }

        val id = RecordingId("rec-${++next}")
        recorded += Recording(
            id = id,
            callId = callId,
            startedAtEpochMillis = nowEpochMillis,
            endedAtEpochMillis = nowEpochMillis,
            sizeBytes = 0,
        )
        recording.update { it + callId }
        return success(id)
    }

    override suspend fun stop(callId: CallId): Outcome<Recording?, RecordingError> {
        recording.update { it - callId }
        return success(recorded.lastOrNull { it.callId == callId })
    }

    override suspend fun recordings(): List<Recording> = recorded.toList()

    override suspend fun delete(id: RecordingId): Outcome<Unit, RecordingError> {
        recorded.removeAll { it.id == id }
        return success(Unit)
    }

    override suspend fun purgeOlderThan(cutoffEpochMillis: Long): Outcome<Int, RecordingError> {
        purgedBefore += cutoffEpochMillis
        val before = recorded.size
        recorded.removeAll { it.startedAtEpochMillis < cutoffEpochMillis }
        return success(before - recorded.size)
    }
}
