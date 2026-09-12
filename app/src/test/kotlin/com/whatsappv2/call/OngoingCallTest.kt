package com.whatsappv2.call

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
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
import com.whatsappv2.domain.testing.FakeSipEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which call the screen shows after the process was killed (Task 45).
 *
 * Telecom's half cannot be exercised off a device — the connections are the platform's —
 * so what is asserted here is the engine's half and the decision made from it. That is the
 * half with the branches: preferring the requested call, ignoring a stale request, and
 * finishing when nothing is up.
 */
class OngoingCallTest {

    private val engine = FakeSipEngine()

    private fun ongoing() = OngoingCall(engine)

    @Test
    fun `with nothing in progress there is nothing to show`() {
        // The screen finishes on this. A call screen for a call that is over cannot be
        // dismissed and invites the user to press buttons that go nowhere.
        assertNull(ongoing().current(requested = CallId("gone")))
    }

    @Test
    fun `a live call is shown even when the intent named none`() = runTest {
        val callId = placeCall()

        assertEquals(callId, ongoing().current(requested = null))
    }

    @Test
    fun `the call the intent named is preferred when it is still up`() = runTest {
        val first = placeCall()
        val second = placeCall()

        assertEquals(second, ongoing().current(requested = second))
        assertEquals(first, ongoing().current(requested = first))
    }

    @Test
    fun `a request for a call that has ended falls back to what is actually up`() = runTest {
        // The system hands the original intent back after a kill, and it may name a call
        // that finished long ago. Treated as no request rather than as an error.
        val ended = placeCall()
        val live = placeCall()
        engine.hangup(ended, HangupReason.LOCAL_HANGUP)

        assertEquals(live, ongoing().current(requested = ended))
    }

    @Test
    fun `a fresh intent's call wins even before the engine has published it`() = runTest {
        // This is the whole difference between `next` and `current`, and asserting it with
        // both calls already live would prove nothing — `current` gets that case right too.
        //
        // A second leg is placed, its intent is built and delivered, and only then does the
        // engine publish the call. In that window `current` finds the named call absent,
        // treats the request as stale, and falls back to "the first live call" — which is
        // the *first* call, the one the screen is already wrongly showing. It would never
        // move, which is exactly what the handset did.
        val live = placeCall()
        val justPlaced = CallId("not-published-yet")

        assertEquals(justPlaced, ongoing().next(requested = justPlaced))
        assertEquals(live, ongoing().current(requested = justPlaced), "the premise no longer holds")
    }

    @Test
    fun `an intent naming no call falls back to whatever is up`() = runTest {
        // The notification's content intent is the case: it opens the screen without
        // naming a leg, and "the call that is up" is the right answer for it.
        val live = placeCall()

        assertEquals(live, ongoing().next(requested = null))
    }

    @Test
    fun `with no call named and none up there is nothing to re-point to`() {
        // The caller leaves the screen where it is rather than finishing it — unlike
        // `current`, which answers a screen that has not opened yet.
        assertNull(ongoing().next(requested = null))
    }

    @Test
    fun `the call screen still overrides onNewIntent`() {
        // The structural half of the same defect, and the half no state test can see.
        // CallActivity is launchMode="singleTask", so a second call is delivered to
        // onNewIntent rather than to a second activity. The override was simply absent:
        // the id read once in onCreate pinned the screen to the first call for the life of
        // the task, and the screen sat on a held call under a banner naming the live one
        // as held (TC15 24143524701316, 2026-09-12 12:01). Reflection because there is no
        // cheaper way to assert that a method exists on the JVM — driving a real activity
        // would need the SIP engine replaced, and its Hilt module is internal to :data:sip.
        val declared = CallActivity::class.java.declaredMethods.map { it.name }
        assertTrue("onNewIntent" in declared, "CallActivity no longer handles a second call's intent")
    }

    private suspend fun placeCall(): CallId {
        engine.givenRegistered(WORK)
        return engine.placeCall(WORK.id, REMOTE, MediaProfile.AUDIO).getOrNull()!!
    }

    private companion object {
        val REMOTE: SipUri = SipUri.parse("sip:bob@sip.example.com").getOrNull()!!

        val WORK = SipAccount(
            id = AccountId("acct-1"),
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
