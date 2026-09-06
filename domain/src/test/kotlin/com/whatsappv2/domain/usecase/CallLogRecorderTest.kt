package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.core.common.time.MutableClock
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.testing.FakeCallLogRepository
import com.whatsappv2.domain.testing.FakeSipEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * What reaches the call log when a call ends (Task 47).
 *
 * Driven through [FakeSipEngine] rather than by handing snapshots to the recorder
 * directly: "exactly one entry per call" is a claim about the engine's terminal
 * transitions, and a test that fed the recorder by hand would assert nothing about them.
 */
class CallLogRecorderTest {

    private val engine = FakeSipEngine()
    private val log = FakeCallLogRepository()
    private val clock = MutableClock().set(STARTED_AT)

    @Test
    fun `an answered call is recorded with the time it ended`() = runTest {
        val callId = outgoingCall()
        engine.simulateRemoteAnswer(callId)
        clock.set(ENDED_AT)
        engine.simulateRemoteHangup(callId)
        runCurrent()

        val entry = log.recorded.single()
        assertTrue(entry.wasAnswered)
        assertFalse(entry.wasMissed)
        assertEquals(HangupReason.REMOTE_HANGUP, entry.reason)
        assertEquals(ENDED_AT, entry.endedAtEpochMillis)
    }

    @Test
    fun `a call nobody answered is recorded as missed, which is the one worth looking up`() =
        runTest {
            recording()
            val incoming = engine.simulateIncomingCall(ACCOUNT, REMOTE)
            engine.reject(incoming.callId, HangupReason.NO_ANSWER)
            runCurrent()

            val entry = log.recorded.single()
            assertTrue(entry.wasMissed)
            assertNull(entry.answeredAtEpochMillis)
            assertEquals(0L, entry.durationSeconds)
            assertEquals(CallDirection.INCOMING, entry.direction)
        }

    @Test
    fun `a call that failed is recorded too, with the reason it failed`() = runTest {
        val callId = outgoingCall()
        engine.simulateRemoteRejection(callId, SipError.Busy(BUSY_HERE))
        runCurrent()

        assertEquals(HangupReason.BUSY, log.recorded.single().reason)
    }

    @Test
    fun `an outbound call the user gave up on is not a missed call`() = runTest {
        // Nobody misses their own call. Direction is half the question, which is the half
        // that goes wrong when "missed" is read as "never answered".
        val callId = outgoingCall()
        engine.hangup(callId, HangupReason.CANCELLED)
        runCurrent()

        val entry = log.recorded.single()
        assertFalse(entry.wasMissed)
        assertFalse(entry.wasAnswered)
    }

    @Test
    fun `each call is recorded once, however many end`() = runTest {
        recording()
        engine.givenRegistered(WORK)
        repeat(THREE_CALLS) {
            val id = engine.placeCall(ACCOUNT, REMOTE, MediaProfile.AUDIO).getOrNull()!!
            runCurrent()
            engine.hangup(id, HangupReason.LOCAL_HANGUP)
            runCurrent()
        }

        assertEquals(THREE_CALLS, log.recorded.size)
        assertEquals(THREE_CALLS, log.recorded.map { it.id }.toSet().size, "ids must be distinct")
    }

    @Test
    fun `duration counts from the answer, not from the dial`() = runTest {
        val callId = outgoingCall()
        clock.set(ANSWERED_AT)
        engine.simulateRemoteAnswer(callId)
        clock.set(ANSWERED_AT + TEN_SECONDS)
        engine.simulateRemoteHangup(callId)
        runCurrent()

        // The call existed for two minutes and was connected for ten seconds of them.
        assertEquals(TEN_SECONDS / MILLIS_PER_SECOND, log.recorded.single().durationSeconds)
    }

    // ---------------------------------------------------------------- fixture

    /** Starts the recorder collecting, before anything can end. */
    private fun TestScope.recording() {
        backgroundScope.launch { CallLogRecorder(engine, log, clock).record() }
        runCurrent()
    }

    /** A recorder already listening, and an outgoing call placed on a registered account. */
    private suspend fun TestScope.outgoingCall(): CallId {
        recording()
        engine.givenRegistered(WORK)
        val callId = engine.placeCall(ACCOUNT, REMOTE, MediaProfile.AUDIO).getOrNull()!!
        runCurrent()
        return callId
    }

    private companion object {
        val ACCOUNT = AccountId("acct-1")
        val REMOTE: SipUri = SipUri.parse("sip:bob@sip.example.com").getOrNull()!!

        const val STARTED_AT = 1_700_000_000_000L
        const val ANSWERED_AT = STARTED_AT + 60_000L
        const val ENDED_AT = STARTED_AT + 120_000L
        const val TEN_SECONDS = 10_000L
        const val MILLIS_PER_SECOND = 1_000L
        const val BUSY_HERE = 486
        const val THREE_CALLS = 3

        val WORK = SipAccount(
            id = ACCOUNT,
            label = "Work",
            username = "alice",
            extension = null,
            authUsername = null,
            password = Secret("hunter22"),
            displayName = null,
            domain = "sip.example.com",
            registrar = null,
            outboundProxy = null,
            port = null,
            transport = Transport.UDP,
            registrationExpirySeconds = 3_600,
            stunServer = null,
            turn = null,
            natPolicy = NatPolicy.DEFAULT,
            srtpPolicy = SrtpPolicy.OPTIONAL,
            codecs = CodecPreferences.DEFAULT,
            isDefault = true,
        )
    }
}
