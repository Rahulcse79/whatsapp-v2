package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.CodecPreferences
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * One button, one conference (ADR-009).
 *
 * `MergeTopologyTest` settles the rule; this settles that the rule is *acted on* — that
 * every merge, whatever media its legs carry, is mixed on this device and that **no**
 * merge dials a conference room. The room was the last server dependency in the call path
 * and these are the assertions that keep it gone.
 */
class MergeCallsUseCaseTest {

    private val account = SipAccount(
        id = AccountId("acct-1"),
        label = "Work",
        username = "1003",
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

    private fun useCase(engine: FakeSipEngine) = MergeCallsUseCase(engine, engine)

    private suspend fun FakeSipEngine.establish(target: String, video: Boolean): CallId {
        val media = if (video) MediaProfile.AUDIO_VIDEO else MediaProfile.AUDIO
        val uri = requireNotNull(SipUri.parse("sip:$target@sip.example.com").getOrNull())
        val callId = requireNotNull(placeCall(account.id, uri, media).getOrNull())
        simulateRemoteAnswer(callId)
        return callId
    }

    @Test
    fun `merging two audio calls mixes them here and never dials the bridge`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val first = engine.establish("1002", video = false)
        val second = engine.establish("1004", video = false)

        val result = useCase(engine)()

        assertEquals(setOf(first, second), assertIs<Outcome.Success<Set<CallId>>>(result).value)
        assertEquals(listOf(setOf(first, second)), engine.mixRequests)
        // The whole point of keeping ADR-009 for audio: no server was involved.
        assertTrue(engine.bridgeMergeRequests.isEmpty())
    }

    @Test
    fun `merging video and audio-only legs mixes them here and dials no room`() = runTest {
        // The regression this locks down. Mixed media used to be the one case still sent
        // to a FreeSWITCH room; on 2026-09-24 that room answered 480 and a live
        // three-party conference collapsed. Every leg is merged here now — the mixer
        // composes among the ones carrying video and the audio-only member stays in the
        // audio mix.
        val engine = FakeSipEngine().givenRegistered(account)
        val first = engine.establish("1002", video = true)
        val second = engine.establish("1004", video = false)

        val result = useCase(engine)()

        assertEquals(setOf(first, second), assertIs<Outcome.Success<Set<CallId>>>(result).value)
        assertEquals(setOf(first, second), engine.mixRequests.single())
        assertTrue(engine.bridgeMergeRequests.isEmpty())

        // Both legs are still this device's own calls: nothing was transferred away, so
        // the screen has no reason to re-point and the user keeps the call they were on.
        assertTrue(engine.activeCalls.value.any { it.callId == first })
        assertTrue(engine.activeCalls.value.any { it.callId == second })
    }

    @Test
    fun `every merged leg stays a call on this device`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val first = engine.establish("1002", video = true)
        engine.establish("1004", video = true)

        useCase(engine)()

        // A merge used to replace the legs with one call to the room; now it moves
        // nothing, which is what lets the call screen leave the user where they were.
        assertTrue(engine.activeCalls.value.any { it.callId == first })
    }

    @Test
    fun `an all-video merge is mixed on the device and never reaches the room`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val first = engine.establish("1002", video = true)
        val second = engine.establish("1004", video = true)

        val result = useCase(engine)()

        assertEquals(setOf(first, second), assertIs<Outcome.Success<Set<CallId>>>(result).value)
        // The handset composes each peer's canvas itself, so nothing is dialled and no
        // RTP goes near a conference room.
        assertTrue(engine.bridgeMergeRequests.isEmpty())
        assertEquals(setOf(first, second), engine.mixRequests.single())
    }

    @Test
    fun `a single established call is not a conference`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        engine.establish("1002", video = true)

        assertIs<Outcome.Failure<*>>(useCase(engine)())
        assertTrue(engine.bridgeMergeRequests.isEmpty())
    }

    @Test
    fun `four legs of mixed media are all mixed here`() = runTest {
        // The specified size: this handset plus three others, one of whom has no camera.
        // Two cameras plus this one is a picture of three, inside the mixer's four.
        val engine = FakeSipEngine().givenRegistered(account)
        val a = engine.establish("1002", video = true)
        val b = engine.establish("1004", video = true)
        val c = engine.establish("1005", video = false)

        val result = useCase(engine)()

        assertEquals(setOf(a, b, c), assertIs<Outcome.Success<Set<CallId>>>(result).value)
        assertEquals(setOf(a, b, c), engine.mixRequests.single())
        assertTrue(engine.bridgeMergeRequests.isEmpty())
    }
}
