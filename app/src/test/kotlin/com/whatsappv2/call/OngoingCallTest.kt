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
