package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.engine.ConferenceRoom
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.testing.FakeSipAccountRepository
import com.whatsappv2.domain.testing.FakeSipEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * One button, the right conference (ADR-003, ADR-009).
 *
 * [MergeTopologyTest] settles the rule; this settles that the rule is *acted on* — that a
 * video merge really does REFER its legs into the room and dial it, and that an audio merge
 * never touches the server.
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

    private fun useCase(engine: FakeSipEngine, room: ConferenceRoom = ConferenceRoom.DEFAULT) =
        MergeCallsUseCase(engine, engine, FakeSipAccountRepository().given(account), room)

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

        val mixed = assertIs<MergeResult.Mixed>(assertIs<Outcome.Success<MergeResult>>(result).value)
        assertEquals(setOf(first, second), mixed.callIds)
        assertEquals(listOf(setOf(first, second)), engine.mixRequests)
        // The whole point of keeping ADR-009 for audio: no server was involved.
        assertTrue(engine.bridgeMergeRequests.isEmpty())
    }

    @Test
    fun `merging video calls transfers every leg into the room and joins it`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val first = engine.establish("1002", video = true)
        val second = engine.establish("1004", video = true)

        val result = useCase(engine)()

        val bridged = assertIs<MergeResult.Bridged>(assertIs<Outcome.Success<MergeResult>>(result).value)

        val (legs, room) = engine.bridgeMergeRequests.single()
        assertEquals(setOf(first, second), legs)
        // Resolved against the account's domain, exactly as a dialled extension is.
        assertEquals("sip:3000@sip.example.com", room.render())

        // The returned call is this device's leg into the room, and it is a conference —
        // which is what makes the screen render the composed picture rather than a
        // one-to-one one.
        val joined = engine.activeCalls.value.single { it.callId == bridged.callId }
        assertTrue(joined.isConference)
        assertEquals(MediaProfile.AUDIO_VIDEO, joined.media)

        // Not mixed locally. A device that is both hosting and joining would hold native
        // ports open for legs that are on their way out.
        assertTrue(engine.mixRequests.isEmpty())
    }

    @Test
    fun `the merged legs are left to end with their own transfers`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val first = engine.establish("1002", video = true)
        engine.establish("1004", video = true)

        useCase(engine)()

        // Still present: the leg is the dialog the REFER travels in, so hanging it up here
        // would cut the transfer off before the peer had followed it.
        assertTrue(engine.activeCalls.value.any { it.callId == first })
    }

    @Test
    fun `a video merge with no room configured fails and changes nothing`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        engine.establish("1002", video = true)
        engine.establish("1004", video = true)

        val result = useCase(engine, ConferenceRoom.NONE)()

        assertIs<Outcome.Failure<*>>(result)
        assertTrue(engine.bridgeMergeRequests.isEmpty())
        // And emphatically not mixed instead: a silent downgrade to audio would take two
        // people's cameras away without telling them.
        assertTrue(engine.mixRequests.isEmpty())
    }

    @Test
    fun `a single established call is not a conference`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        engine.establish("1002", video = true)

        assertIs<Outcome.Failure<*>>(useCase(engine)())
        assertTrue(engine.bridgeMergeRequests.isEmpty())
    }

    @Test
    fun `four legs of mixed media all go to the room`() = runTest {
        // The specified size: this handset plus three others, one of whom has no camera.
        val engine = FakeSipEngine().givenRegistered(account)
        val a = engine.establish("1002", video = true)
        val b = engine.establish("1004", video = true)
        val c = engine.establish("1005", video = false)

        val result = useCase(engine)()

        assertIs<MergeResult.Bridged>(assertIs<Outcome.Success<MergeResult>>(result).value)
        assertEquals(setOf(a, b, c), engine.bridgeMergeRequests.single().first)
    }
}
