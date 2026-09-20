package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.call.SecondCallResponse
import com.whatsappv2.domain.engine.NoCameraAvailable
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The three ways somebody joins a conference this device mixes, end to end through the
 * fake engine: added by the host, calling in during it, and called back as a group.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConferenceJoinCoordinatorTest {

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

    private fun uri(user: String) = requireNotNull(SipUri.parse("sip:$user@example.com").getOrNull())

    @Test
    fun `a participant the host adds joins the conference when they answer, with no merge`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val (joins, job) = running(engine)
        val conference = conferenceOfTwo(engine)

        // The host presses Add on the conference screen and dials a third person.
        val third = engine.placeCall(account.id, uri("carol"), MediaProfile.AUDIO).getOrNull()!!
        joins.joinOnConnect(third)
        runCurrent()
        assertEquals(conference, engine.mixedCalls.value, "nothing happens while they ring")

        engine.simulateRemoteAnswer(third)
        runCurrent()

        assertEquals(conference + third, engine.mixedCalls.value, "answered means in the room")
        job.cancel()
    }

    @Test
    fun `somebody calling in during the conference is added when the host chooses to`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val (joins, job) = running(engine)
        val conference = conferenceOfTwo(engine)
        val caller = engine.simulateIncomingCall(account.id, uri("dave")).callId

        val result = CallWaitingUseCase(engine, NoCameraAvailable, joins)
            .respond(caller, SecondCallResponse.ACCEPT_INTO_CONFERENCE)
        runCurrent()

        assertTrue(result.getOrNull() != null, "the answer must succeed: $result")
        assertEquals(conference + caller, engine.mixedCalls.value)
        assertTrue(
            engine.activeCalls.value.filter { it.callId in conference }.all { it.state is CallState.Connected },
            "the room was neither held nor ended for the newcomer",
        )
        job.cancel()
    }

    @Test
    fun `a group called back from history becomes a conference as its legs answer`() = runTest {
        // Two legs placed at once; Telecom would park the first when the second connects,
        // and the fake engine's mixCalls resumes a held member exactly as the real one.
        val engine = FakeSipEngine().givenRegistered(account)
        val (joins, job) = running(engine)

        val first = engine.placeCall(account.id, uri("bob"), MediaProfile.AUDIO).getOrNull()!!
        val second = engine.placeCall(account.id, uri("carol"), MediaProfile.AUDIO).getOrNull()!!
        joins.joinOnConnect(first)
        joins.joinOnConnect(second)
        engine.simulateRemoteAnswer(first)
        runCurrent()
        assertTrue(engine.mixedCalls.value.isEmpty(), "one answered leg is a call, not a conference")

        engine.setHold(first, held = true)
        engine.simulateRemoteAnswer(second)
        runCurrent()

        assertEquals(setOf(first, second), engine.mixedCalls.value)
        job.cancel()
    }

    @Test
    fun `a conference called back is dialled one member at a time, and forms as they answer`() = runTest {
        // Telecom permits one outgoing call in progress: the second member is dialled
        // only once the first has been answered (or has given up), from the
        // coordinator's own scope — the screen that asked is gone by then.
        val engine = FakeSipEngine().givenRegistered(account)
        val (joins, job) = running(engine)
        val dialled = mutableListOf<String>()
        val members = listOf("bob", "carol", "dave").map { user ->
            suspend {
                dialled += user
                engine.placeCall(account.id, uri(user), MediaProfile.AUDIO).getOrNull()
            }
        }

        val first = joins.callBack(members)
        runCurrent()
        assertEquals(listOf("bob"), dialled, "the rest wait for the first to be answered")

        engine.simulateRemoteAnswer(first!!)
        runCurrent()
        assertEquals(listOf("bob", "carol"), dialled)
        val second = engine.activeCalls.value.single { it.remote == uri("carol") }.callId
        engine.simulateRemoteAnswer(second)
        runCurrent()

        assertEquals(setOf(first, second), engine.mixedCalls.value, "two answered members are a conference")
        assertEquals(listOf("bob", "carol", "dave"), dialled, "and the third goes out once the second settled")
        val third = engine.activeCalls.value.single { it.remote == uri("dave") }.callId
        engine.simulateRemoteAnswer(third)
        runCurrent()
        assertEquals(setOf(first, second, third), engine.mixedCalls.value)
        job.cancel()
    }

    @Test
    fun `a member who does not answer does not hold the others up for good`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val (joins, job) = running(engine)
        val dialled = mutableListOf<String>()
        val members = listOf("bob", "carol").map { user ->
            suspend {
                dialled += user
                engine.placeCall(account.id, uri(user), MediaProfile.AUDIO).getOrNull()
            }
        }

        val first = joins.callBack(members)!!
        runCurrent()
        engine.simulateRemoteHangup(first)
        runCurrent()

        assertEquals(listOf("bob", "carol"), dialled, "a leg that ended is settled, and the next goes out")
        job.cancel()
    }

    @Test
    fun `a call nobody asked to have joined is left as a call`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val (_, job) = running(engine)
        val conference = conferenceOfTwo(engine)

        val consultation = engine.placeCall(account.id, uri("erin"), MediaProfile.AUDIO).getOrNull()!!
        engine.simulateRemoteAnswer(consultation)
        runCurrent()

        assertEquals(conference, engine.mixedCalls.value, "a transfer's consultation call is private")
        job.cancel()
    }

    @Test
    fun `a request for a call that ends before it answers is forgotten`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val (joins, job) = running(engine)
        val conference = conferenceOfTwo(engine)

        val third = engine.placeCall(account.id, uri("carol"), MediaProfile.AUDIO).getOrNull()!!
        joins.joinOnConnect(third)
        runCurrent()
        engine.simulateRemoteHangup(third)
        runCurrent()

        assertEquals(conference, engine.mixedCalls.value)
        job.cancel()
    }

    @Test
    fun `the dialler is told when a call placed now would be a participant`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val (joins, job) = running(engine)
        assertFalse(joins.hostingLiveConference.first(), "no conference, no adding")

        val conference = conferenceOfTwo(engine)
        assertTrue(joins.hostingLiveConference.first(), "a live conference is one people can be added to")

        conference.forEach { engine.setHold(it, held = true) }
        runCurrent()
        assertFalse(joins.hostingLiveConference.first(), "a held conference is not being added to")
        job.cancel()
    }

    private fun TestScope.running(engine: FakeSipEngine): Pair<ConferenceJoinCoordinator, Job> {
        val joins = ConferenceJoinCoordinator(engine, engine)
        val job = launch { joins.run() }
        runCurrent()
        return joins to job
    }

    /** Two answered calls, mixed, as the host's conference screen would have them. */
    private suspend fun TestScope.conferenceOfTwo(engine: FakeSipEngine): Set<CallId> {
        val a = engine.placeCall(account.id, uri("bob"), MediaProfile.AUDIO).getOrNull()!!
        engine.simulateRemoteAnswer(a)
        val b = engine.placeCall(account.id, uri("frank"), MediaProfile.AUDIO).getOrNull()!!
        engine.simulateRemoteAnswer(b)
        engine.mixCalls(setOf(a, b))
        runCurrent()
        assertEquals(setOf(a, b), engine.mixedCalls.value)
        return setOf(a, b)
    }
}
