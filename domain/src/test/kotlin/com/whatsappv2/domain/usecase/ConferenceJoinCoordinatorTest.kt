package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.call.SecondCallResponse
import com.whatsappv2.domain.engine.CallPlacement
import com.whatsappv2.domain.engine.ConferenceRoom
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
import com.whatsappv2.domain.testing.FakeSipAccountRepository
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
    fun `a call-back dials every member at the same time and mixes them as they answer`() = runTest {
        // The history screen's swipe on a conference row. Every member's phone rings at
        // once; each is mixed in as they answer. The joins happen in the coordinator's
        // own scope — the screen that asked is gone by then.
        val engine = FakeSipEngine().givenRegistered(account)
        val (joins, job) = running(engine)
        val dialled = mutableListOf<Pair<String, CallPlacement>>()
        fun member(user: String): suspend (CallPlacement) -> CallId? = { placement ->
            dialled += user to placement
            engine.placeCall(account.id, uri(user), MediaProfile.AUDIO, placement).getOrNull()
        }
        val members = listOf(member("bob"), member("carol"), member("dave"))

        val first = joins.callBack(members)!!
        runCurrent()
        assertEquals(
            listOf("bob", "carol", "dave"),
            dialled.map { it.first },
            "everyone is dialled before anyone answers",
        )
        assertEquals(
            listOf(CallPlacement.STANDALONE, CallPlacement.CONFERENCE_MEMBER, CallPlacement.CONFERENCE_MEMBER),
            dialled.map { it.second },
            "the first leg is the platform's; the rest are placed beside it",
        )
        assertEquals(3, engine.activeCalls.value.size, "three INVITEs are out")
        assertEquals(
            listOf(true, false, false),
            engine.activeCalls.value.map { it.platformManaged },
            "only the first leg is registered with the platform",
        )

        val second = engine.activeCalls.value.single { it.remote == uri("carol") }.callId
        val third = engine.activeCalls.value.single { it.remote == uri("dave") }.callId
        engine.simulateRemoteAnswer(third)
        runCurrent()
        assertTrue(engine.mixedCalls.value.isEmpty(), "one answered member is a call, not a conference")

        engine.simulateRemoteAnswer(first)
        runCurrent()
        assertEquals(
            setOf(first, third),
            engine.mixedCalls.value,
            "two answered members are a conference, whichever answered first",
        )

        engine.simulateRemoteAnswer(second)
        runCurrent()
        assertEquals(setOf(first, second, third), engine.mixedCalls.value)
        job.cancel()
    }

    @Test
    fun `the platform's leg ending first hands its connection to a survivor`() = runTest {
        // The first member is busy and the INVITE fails while the others still ring. The
        // platform must not think the call is over: a survivor takes the registration.
        val engine = FakeSipEngine().givenRegistered(account)
        val (joins, job) = running(engine)
        fun member(user: String): suspend (CallPlacement) -> CallId? = { placement ->
            engine.placeCall(account.id, uri(user), MediaProfile.AUDIO, placement).getOrNull()
        }
        val members = listOf(member("bob"), member("carol"))

        val first = joins.callBack(members)!!
        runCurrent()
        val second = engine.activeCalls.value.single { it.remote == uri("carol") }
        assertFalse(second.platformManaged)

        engine.simulateRemoteHangup(first)
        runCurrent()
        assertTrue(
            engine.activeCalls.value.single().platformManaged,
            "the surviving leg now carries the platform's side of the call",
        )
        job.cancel()
    }

    @Test
    fun `a first member who cannot be dialled is skipped and the next leads`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val (joins, job) = running(engine)
        val dialled = mutableListOf<Pair<String, CallPlacement>>()
        fun member(user: String, dialable: Boolean): suspend (CallPlacement) -> CallId? = { placement ->
            dialled += user to placement
            if (dialable) engine.placeCall(account.id, uri(user), MediaProfile.AUDIO, placement).getOrNull() else null
        }
        val members = listOf(
            member("bob", dialable = false),
            member("carol", dialable = true),
            member("dave", dialable = true),
        )

        val first = joins.callBack(members)
        runCurrent()
        assertEquals(engine.activeCalls.value.first { it.remote == uri("carol") }.callId, first)
        assertEquals(
            listOf(
                "bob" to CallPlacement.STANDALONE,
                "carol" to CallPlacement.STANDALONE,
                "dave" to CallPlacement.CONFERENCE_MEMBER,
            ),
            dialled,
            "the lead is tried standalone until one goes out; the rest follow beside it",
        )
        job.cancel()
    }

    @Test
    fun `a call-back with nobody dialable places nothing`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val (joins, job) = running(engine)
        val none = List<suspend (CallPlacement) -> CallId?>(2) { { _ -> null } }
        assertEquals(null, joins.callBack(none))
        assertTrue(engine.activeCalls.value.isEmpty())
        job.cancel()
    }

    @Test
    fun `a video call-back dials everyone at once and builds the conference in the bridge`() = runTest {
        // The history screen's left swipe on a conference. Every member is dialled with
        // video at the same moment; the first two to answer are sent to the room together
        // and this device follows them in; each later one is sent to join the leg this
        // device already holds.
        val engine = FakeSipEngine().givenRegistered(account)
        val (joins, job) = running(engine)
        val dialled = mutableListOf<CallId>()
        fun member(user: String): suspend (CallPlacement) -> CallId? = { placement ->
            engine.placeCall(account.id, uri(user), MediaProfile.AUDIO_VIDEO, placement).getOrNull()
                ?.also { dialled += it }
        }

        val first = joins.callBack(listOf(member("bob"), member("carol"), member("dave")), video = true)
        runCurrent()
        assertEquals(dialled.first(), first, "the screen follows the first leg out")
        assertEquals(3, dialled.size, "every member rings at once")

        engine.simulateRemoteAnswer(dialled[0])
        runCurrent()
        assertTrue(engine.bridgeMergeRequests.isEmpty(), "one answered leg is a call, not a conference")

        // Telecom parks the first leg when the second connects.
        engine.setHold(dialled[0], held = true)
        engine.simulateRemoteAnswer(dialled[1])
        runCurrent()
        assertEquals(
            setOf(dialled[0], dialled[1]),
            engine.bridgeMergeRequests.single().first,
            "both legs go to the room",
        )
        val roomLeg = engine.conferences.value.single().callId
        assertTrue(
            engine.activeCalls.value.single { it.callId == roomLeg }.platformManaged,
            "nothing registered is dialling — the lead is held — so the room leg is registered like any call",
        )

        engine.simulateRemoteAnswer(roomLeg)
        engine.setHold(roomLeg, held = true)
        engine.simulateRemoteAnswer(dialled[2])
        runCurrent()
        assertEquals(setOf(dialled[2]), engine.bridgeMergeRequests.last().first, "only the newcomer is sent in")
        assertEquals(roomLeg, engine.conferences.value.single().callId, "the room is not dialled a second time")
        val key = engine.activeCalls.value.first().conferenceKey
        assertTrue(
            engine.activeCalls.value.all { it.isConference && it.conferenceKey == key },
            "every leg and the room leg carry the one conference key: ${engine.activeCalls.value}",
        )
        job.cancel()
    }

    @Test
    fun `the room is joined beside a still-ringing lead without asking the platform`() = runTest {
        // The two members who answered first were not the lead. The lead's INVITE is
        // the platform's, still ringing, and a registered join would be refused — so
        // the room is joined as a member, and takes the registration when the lead goes.
        val engine = FakeSipEngine().givenRegistered(account)
        val (joins, job) = running(engine)
        val dialled = mutableListOf<CallId>()
        fun member(user: String): suspend (CallPlacement) -> CallId? = { placement ->
            engine.placeCall(account.id, uri(user), MediaProfile.AUDIO_VIDEO, placement).getOrNull()
                ?.also { dialled += it }
        }
        joins.callBack(listOf(member("bob"), member("carol"), member("dave")), video = true)
        runCurrent()

        engine.simulateRemoteAnswer(dialled[1])
        engine.simulateRemoteAnswer(dialled[2])
        runCurrent()

        val roomLeg = engine.conferences.value.single().callId
        assertFalse(
            engine.activeCalls.value.single { it.callId == roomLeg }.platformManaged,
            "joined beside the ringing lead",
        )
        assertEquals(setOf(dialled[1], dialled[2]), engine.bridgeMergeRequests.single().first)

        engine.simulateRemoteHangup(dialled[0])
        runCurrent()
        assertEquals(
            1,
            engine.activeCalls.value.count { it.platformManaged },
            "one leg carries the platform's side once the lead is gone: ${engine.activeCalls.value}",
        )
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
        val merge = MergeCallsUseCase(engine, engine, FakeSipAccountRepository().given(account), ConferenceRoom.DEFAULT)
        val joins = ConferenceJoinCoordinator(engine, engine, merge)
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
