package com.whatsappv2.data.sip

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.errorOrNull
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.call.AudioRoute
import com.whatsappv2.domain.engine.PushToken
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.DtmfDigit
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.TransferType
import com.whatsappv2.domain.model.Transport
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins the placeholder's behaviour until the real stack lands in Task 27.
 *
 * The point is that it must fail, visibly and consistently. A placeholder that quietly
 * returned success - or reported accounts as registered - would let the screens above it
 * be built against behaviour that does not exist, and the gap would surface only when the
 * real engine arrived and behaved differently.
 */
class UnavailableSipEngineTest {

    private val engine = UnavailableSipEngine()
    private val target = requireNotNull(SipUri.parse("sip:bob@example.com").getOrNull())
    private val callId = CallId("call-1")

    private val account = SipAccount(
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

    @Test
    fun `every registration operation reports the engine is unavailable`() = runTest {
        assertEquals(SipError.EngineUnavailable, engine.register(account).errorOrNull())
        assertEquals(SipError.EngineUnavailable, engine.unregister(account.id).errorOrNull())
        assertEquals(SipError.EngineUnavailable, engine.refreshRegistration(account.id).errorOrNull())
        assertEquals(
            SipError.EngineUnavailable,
            engine.setPushToken(PushToken("fcm", "sender", "token")).errorOrNull(),
        )
    }

    @Test
    fun `every call operation reports the engine is unavailable`() = runTest {
        assertEquals(
            SipError.EngineUnavailable,
            engine.placeCall(account.id, target, MediaProfile.AUDIO).errorOrNull(),
        )
        assertEquals(SipError.EngineUnavailable, engine.answer(callId, MediaProfile.AUDIO).errorOrNull())
        assertEquals(SipError.EngineUnavailable, engine.reject(callId, HangupReason.BUSY).errorOrNull())
        assertEquals(
            SipError.EngineUnavailable,
            engine.hangup(callId, HangupReason.LOCAL_HANGUP).errorOrNull(),
        )
        assertEquals(SipError.EngineUnavailable, engine.setHold(callId, held = true).errorOrNull())
        assertEquals(SipError.EngineUnavailable, engine.sendDtmf(callId, DtmfDigit.FIVE).errorOrNull())
        assertEquals(
            SipError.EngineUnavailable,
            engine.transfer(callId, target, TransferType.BLIND).errorOrNull(),
        )
    }

    @Test
    fun `every media operation reports the engine is unavailable`() = runTest {
        assertEquals(SipError.EngineUnavailable, engine.setMuted(callId, muted = true).errorOrNull())
        assertEquals(
            SipError.EngineUnavailable,
            engine.setAudioRoute(callId, AudioRoute.SPEAKER).errorOrNull(),
        )
        assertEquals(
            SipError.EngineUnavailable,
            engine.setVideoEnabled(callId, enabled = true).errorOrNull(),
        )
        assertEquals(SipError.EngineUnavailable, engine.switchCamera(callId).errorOrNull())
        assertEquals(
            SipError.EngineUnavailable,
            engine.joinConference(account.id, target, MediaProfile.AUDIO).errorOrNull(),
        )
    }

    @Test
    fun `no account is ever reported as registered`() = runTest {
        // The account list renders from this, so a placeholder claiming registration
        // would show a working state that does not exist.
        assertTrue(engine.registrationState.value.isEmpty())
        engine.register(account)
        assertTrue(engine.registrationState.value.isEmpty())
    }

    @Test
    fun `there are never any calls or conferences`() = runTest {
        assertTrue(engine.activeCalls.value.isEmpty())
        assertTrue(engine.conferences.value.isEmpty())
        assertTrue(engine.incomingCalls.toList().isEmpty())
    }

    @Test
    fun `shutdown is a no-op rather than an error`() = runTest {
        engine.shutdown()
        assertIs<Outcome.Failure<SipError>>(engine.register(account))
    }
}
