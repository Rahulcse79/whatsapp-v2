package com.whatsappv2.data.sip.call

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.data.sip.registration.TestTarget
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.engine.ConferenceSession
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Dialling into a real conference bridge (Task 60, §2.2, DoD 11).
 *
 * ## What this can and cannot cover
 *
 * ADR-003 chose a dial-in MCU, so joining is dialling — and that half is testable from one
 * device: the INVITE goes out, the bridge answers, and the leg is recorded as a
 * conference. That is what runs here.
 *
 * **Three clients hearing each other is not testable from one device and this suite does
 * not claim it.** Task 60's first done-when needs three endpoints and a person to listen;
 * this asserts the client half — that the app joins, that whatever roster the bridge
 * publishes reaches `conferences`, and that a bridge publishing none leaves the session
 * saying so rather than showing an empty room.
 *
 * ## The roster assertion is deliberately conditional
 *
 * FreeSWITCH's `mod_conference` does not publish a SIP conference event package to a
 * dial-in participant by default. So the roster may legitimately be absent, and a test
 * that *required* one would fail against a correctly configured server. What is asserted
 * instead is the thing that must hold either way: `rosterAvailable` tells the truth, and
 * the participant list is never fabricated. That is Task 60's third done-when, and it is
 * the one that matters — an invented roster looks authoritative and is worse than none.
 *
 * ## Skips rather than fails
 *
 * No configured target means the whole class is skipped with a message naming what to set,
 * exactly as the registration and call suites do. See `docs/testing.md`.
 */
@RunWith(AndroidJUnit4::class)
class ConferenceIntegrationTest {

    private lateinit var target: TestTarget
    private lateinit var harness: CallTestHarness

    @Before
    fun setUp() {
        target = TestTarget.requireConfigured()
        harness = CallTestHarness(
            InstrumentationRegistry.getInstrumentation().targetContext,
            target,
        )
        harness.start()
    }

    @After
    fun tearDown() {
        if (::harness.isInitialized) harness.stop()
    }

    @Test
    fun joiningTheBridgeProducesAConferenceLegAndAnHonestRoster() = runBlocking {
        registerCaller()

        val uri = requireNotNull(
            SipUri.parse("sip:${target.conferenceExtension}@${target.domain}").getOrNull(),
        )
        val joined = harness.engine.joinConference(harness.caller.id, uri, MediaProfile.AUDIO)
        val callId = assertNotNull(joined.getOrNull(), "the bridge refused the INVITE: $joined")

        // The bridge answers a dial-in immediately; a generous wait, because what is being
        // measured here is a network rather than a client.
        val connected = withTimeoutOrNull(ANSWER_TIMEOUT_MILLIS) {
            waitUntil {
                harness.engine.activeCalls.value
                    .any { it.callId == callId && it.state is CallState.Connected }
            }
        }
        assertNotNull(connected, "the conference leg never connected")

        val session = harness.engine.conferences.value.singleOrNull()
        assertNotNull(session, "joining produced no conference session")
        assertEquals(callId, session.callId)
        assertTrue(harness.engine.activeCalls.value.single { it.callId == callId }.isConference)

        assertRosterIsHonest(session)

        harness.engine.hangup(callId, HangupReason.LOCAL_HANGUP)
        assertTrue(harness.engine.conferences.value.isEmpty(), "the session outlived its leg")
    }

    /**
     * Whatever the bridge says, the session must not overstate it.
     *
     * The two states are both legal and the assertion is that they are not confused: a
     * published roster has entries, and an unpublished one reports no count at all rather
     * than zero (§13).
     */
    private fun assertRosterIsHonest(session: ConferenceSession) {
        if (session.rosterAvailable) {
            assertTrue(
                session.participants.isNotEmpty(),
                "the bridge published a roster, so it must name at least the local participant",
            )
        } else {
            assertEquals(null, session.participantCount, "an unpublished roster must not report a count")
            assertTrue(session.participants.isEmpty(), "a roster that does not exist must not have entries")
        }
    }

    private suspend fun registerCaller() {
        harness.engine.register(harness.caller)
        val registered = withTimeoutOrNull(REGISTER_TIMEOUT_MILLIS) {
            waitUntil { harness.engine.registrationState.value[harness.caller.id]?.isUsable == true }
        }
        assertNotNull(registered, "the caller never registered against ${target.host}")
    }

    /** Polls rather than collects: what is being waited on is a real network round trip. */
    private suspend fun waitUntil(condition: () -> Boolean) {
        while (!condition()) delay(POLL_INTERVAL_MILLIS)
    }

    private companion object {
        const val REGISTER_TIMEOUT_MILLIS = 15_000L
        const val ANSWER_TIMEOUT_MILLIS = 20_000L
        const val POLL_INTERVAL_MILLIS = 200L
    }
}
