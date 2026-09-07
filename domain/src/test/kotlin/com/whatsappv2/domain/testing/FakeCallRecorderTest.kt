package com.whatsappv2.domain.testing

import com.whatsappv2.core.common.result.errorOrNull
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.result.isSuccess
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.recording.RecordingConsent
import com.whatsappv2.domain.recording.RecordingError
import com.whatsappv2.domain.recording.RecordingRefusal
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The recorder fake, held to the promises the real one makes (Task 58, §2.6).
 *
 * This fake is the only recorder the call screen's tests ever see, so a fake that started
 * a recording the production gate would have refused would let those tests pass against
 * behaviour that cannot ship — and consent is precisely the rule that must not be
 * discovered late. Its refusals, its indicator and its retention hook are asserted here
 * rather than assumed, the same way the other fakes are.
 */
class FakeCallRecorderTest {

    private val recorder = FakeCallRecorder()
    private val callId = CallId("call-1")
    private val other = CallId("call-2")

    private fun consent(forCall: CallId = callId) =
        RecordingConsent.GrantedByLocalUser(forCall, grantedAtEpochMillis = GRANTED_AT)

    @Test
    fun `it records nothing until told, which is where every call starts`() = runTest {
        assertTrue(recorder.active.value.isEmpty())
        assertTrue(recorder.recordings().isEmpty())
    }

    @Test
    fun `without consent it refuses, with the reason the policy gives`() = runTest {
        val result = recorder.start(callId, RecordingConsent.None)

        assertEquals(RecordingError.Refused(RecordingRefusal.NoConsent), result.errorOrNull())
        assertTrue(recorder.active.value.isEmpty())
        // Recorded even when refused: a caller that passed no consent is what a test
        // asserting the gate needs to see.
        assertEquals(listOf(RecordingConsent.None), recorder.consents)
    }

    @Test
    fun `consent given on another call does not carry to this one`() = runTest {
        val result = recorder.start(callId, consent(forCall = other))

        assertEquals(
            RecordingError.Refused(RecordingRefusal.ConsentForAnotherCall(other)),
            result.errorOrNull(),
        )
        assertTrue(recorder.active.value.isEmpty())
    }

    @Test
    fun `refuseWith stands in for the platform refusal the JVM cannot reach`() = runTest {
        recorder.refuseWith = RecordingRefusal.NotSupportedOnThisPlatform

        val result = recorder.start(callId, consent())

        assertEquals(
            RecordingError.Refused(RecordingRefusal.NotSupportedOnThisPlatform),
            result.errorOrNull(),
        )
        assertTrue(recorder.active.value.isEmpty())
    }

    @Test
    fun `a granted consent starts one, and the indicator stays on until it stops`() = runTest {
        recorder.nowEpochMillis = STARTED_AT

        val id = recorder.start(callId, consent()).getOrNull()

        assertEquals(setOf(callId), recorder.active.value)

        val stopped = recorder.stop(callId).getOrNull()

        assertEquals(id, stopped?.id)
        assertEquals(callId, stopped?.callId)
        assertEquals(STARTED_AT, stopped?.startedAtEpochMillis)
        assertTrue(recorder.active.value.isEmpty())
    }

    @Test
    fun `stopping a call that was never recording succeeds quietly`() = runTest {
        val result = recorder.stop(callId)

        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
    }

    @Test
    fun `each recording is its own, so two cannot be confused for one`() = runTest {
        val first = recorder.start(callId, consent()).getOrNull()
        recorder.stop(callId)
        val second = recorder.start(callId, consent()).getOrNull()

        assertNotEquals(first, second)
    }

    @Test
    fun `a deleted recording is gone from the list, and deleting it again is quiet`() = runTest {
        val id = requireNotNull(recorder.start(callId, consent()).getOrNull())
        recorder.stop(callId)

        assertTrue(recorder.delete(id).isSuccess)
        assertTrue(recorder.recordings().isEmpty())
        assertTrue(recorder.delete(id).isSuccess)
    }

    @Test
    fun `purge removes only what started before the cutoff, and says how many`() = runTest {
        recorder.nowEpochMillis = STARTED_AT
        recorder.start(callId, consent())
        recorder.stop(callId)
        recorder.nowEpochMillis = LATER
        recorder.start(other, consent(forCall = other))
        recorder.stop(other)

        val removed = recorder.purgeOlderThan(CUTOFF).getOrNull()

        assertEquals(1, removed)
        assertEquals(listOf(CUTOFF), recorder.purgedBefore)
        assertEquals(listOf(other), recorder.recordings().map { it.callId })
    }

    private companion object {
        const val GRANTED_AT = 1_000L
        const val STARTED_AT = 1_000L
        const val CUTOFF = 3_000L
        const val LATER = 5_000L
    }
}
