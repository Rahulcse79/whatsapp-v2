package com.whatsappv2.feature.calls

import app.cash.turbine.test
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.call.SecondCallResponse
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The call screen over a conference this device mixes (ADR-009): the roster, and how
 * somebody gets into the room.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallViewModelConferenceTest : CallViewModelFixture() {

    @Test
    fun `a conference the engine forms without this screen's Merge is still shown as one`() = runTest {
        // A participant the join coordinator mixes in after the far end answers, or a
        // screen recreated mid-conference: the screen read a set of its own that only
        // its Merge button wrote, and described a room that had since changed.
        accounts.given(ACCOUNT)
        val first = placeCall()
        engine.simulateRemoteAnswer(first)
        val second = engine.placeCall(ACCOUNT.id, OTHER, MediaProfile.AUDIO).getOrNull()!!
        engine.simulateRemoteAnswer(second)
        val viewModel = viewModel().also { it.watch(first) }

        viewModel.uiState.test {
            awaitActive { it.call.phase == CallPhase.CONNECTED }
            engine.mixCalls(setOf(first, second))
            runCurrent()

            val shown = awaitActive { it.mixedCallCount == 2 }
            assertEquals(CONFERENCE_TITLE, shown.call.title)
            assertEquals(listOf("alice", "bob", "1003"), shown.conference!!.participants.map { it.label })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `somebody calling in during a live conference is offered a seat in it`() = runTest {
        // How a person who missed the conference gets in: they call the host, and the
        // prompt says "Add to conference" rather than making the host choose between
        // them and the room.
        val first = placeCall()
        engine.simulateRemoteAnswer(first)
        val second = engine.placeCall(ACCOUNT.id, OTHER, MediaProfile.AUDIO).getOrNull()!!
        engine.simulateRemoteAnswer(second)
        engine.mixCalls(setOf(first, second))
        val viewModel = viewModel().also { it.watch(first) }
        val caller = engine.simulateIncomingCall(ACCOUNT.id, SipUri.parse("sip:1004@sip.example.com").getOrNull()!!)

        viewModel.uiState.test {
            val prompted = awaitActive { it.secondCall != null }
            val prompt = prompted.secondCall!!
            assertTrue(prompt.canAddToConference, "a live conference can take them")
            assertEquals("a conference of 3", prompt.currentCallWith)

            viewModel.respondToSecondCall(caller.callId, SecondCallResponse.ACCEPT_INTO_CONFERENCE)
            runCurrent()

            // Answered, and the room untouched: neither leg was held or ended for them.
            val calls = engine.activeCalls.value.associateBy { it.callId }
            assertIs<CallState.Connected>(calls.getValue(caller.callId).state)
            assertIs<CallState.Connected>(calls.getValue(first).state)
            assertIs<CallState.Connected>(calls.getValue(second).state)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a held conference does not offer to seat a caller`() = runTest {
        val first = placeCall()
        engine.simulateRemoteAnswer(first)
        val second = engine.placeCall(ACCOUNT.id, OTHER, MediaProfile.AUDIO).getOrNull()!!
        engine.simulateRemoteAnswer(second)
        engine.mixCalls(setOf(first, second))
        engine.setHold(first, held = true)
        engine.setHold(second, held = true)
        val viewModel = viewModel().also { it.watch(first) }
        engine.simulateIncomingCall(ACCOUNT.id, SipUri.parse("sip:1004@sip.example.com").getOrNull()!!)

        viewModel.uiState.test {
            val prompt = awaitActive { it.secondCall != null }.secondCall!!
            assertFalse(prompt.canAddToConference, "the host stepped out; answering must not put them back in")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a merged conference is titled as one, and every member is listed with the user first`() = runTest {
        // The screen kept the first leg's extension as its title after Merge — "9196"
        // over a button reading "2 calls merged" (TC15, 2026-09-14). What the user is in
        // is a conference; that is the title, and the members are the roster below it.
        // A line of names under the title came first and truncated at seven members
        // (TC15, 2026-09-19); the roster is one row each and does not.
        accounts.given(ACCOUNT)
        val first = placeCall()
        engine.simulateRemoteAnswer(first)
        val second = engine.placeCall(ACCOUNT.id, OTHER, MediaProfile.AUDIO).getOrNull()!!
        engine.simulateRemoteAnswer(second)
        val viewModel = viewModel().also { it.watch(first) }
        runCurrent()

        viewModel.uiState.test {
            val alone = awaitActive { it.call.phase == CallPhase.CONNECTED }
            assertEquals("bob", alone.call.title, "a 1:1 call is titled by its extension")
            assertFalse(alone.call.isMixed)
            assertNull(alone.conference, "two separate calls are not a conference")

            viewModel.merge()
            runCurrent()

            val merged = awaitActive { it.mixedCallCount >= MIN_MIXED_IN_TEST && it.conference != null }
            assertEquals(CONFERENCE_TITLE, merged.call.title)
            assertNull(merged.call.subtitle, "the members are the roster, not a line that truncates")
            assertTrue(merged.call.isMixed)
            assertNull(merged.call.photoUri)

            val roster = merged.conference!!
            assertTrue(roster.rosterAvailable, "this device is the mixer, so it knows exactly who is here")
            assertEquals(
                listOf("alice", "bob", "1003"),
                roster.participants.map { it.label },
                "the user first, then every leg in the order it was called",
            )
            assertEquals(listOf(true, false, false), roster.participants.map { it.isSelf })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a conference merged into the bridge lists the people merged, and keeps them after their legs end`() = runTest {
        // The bridge publishes no roster, and the screen used to say only that. The device
        // that pressed Merge knows exactly whom it sent into the room, so the roster names
        // them, the user first — and goes on naming them after the merged legs have been
        // transferred away and ended, which is a second after Merge. It is a list of what
        // this device merged, said so, and never a count the bridge did not give.
        //
        // Mixed media, because that is what still reaches the bridge: an all-video merge
        // is composed on the device and never goes near the room.
        engine.givenRegistered(ACCOUNT)
        accounts.given(ACCOUNT)
        val first = engine.placeCall(ACCOUNT.id, REMOTE, MediaProfile.AUDIO_VIDEO).getOrNull()!!
        engine.simulateRemoteAnswer(first)
        val second = engine.placeCall(ACCOUNT.id, OTHER, MediaProfile.AUDIO).getOrNull()!!
        engine.simulateRemoteAnswer(second)
        val viewModel = viewModel().also { it.watch(first) }
        runCurrent()

        viewModel.uiState.test {
            awaitActive { it.call.phase == CallPhase.CONNECTED }
            viewModel.merge()
            runCurrent()

            val merged = awaitActive { it.conference != null }
            val roster = merged.conference!!
            assertTrue(roster.fromMerge, "the list is what this device merged, and says so")
            assertFalse(roster.rosterAvailable, "the bridge still published nothing")
            assertNull(roster.count, "no count the bridge did not give")
            assertEquals(listOf("alice", "bob", "1003"), roster.participants.map { it.label })
            assertEquals(listOf(true, false, false), roster.participants.map { it.isSelf })

            // The merged legs end as their transfers complete; the list does not change.
            engine.simulateTransferSucceeded(first)
            engine.simulateTransferSucceeded(second)
            engine.simulateRemoteAnswer(merged.call.callId)
            runCurrent()
            val settled = awaitActive { it.call.phase == CallPhase.CONNECTED && it.otherCalls.isEmpty() }
            assertEquals(listOf("alice", "bob", "1003"), settled.conference!!.participants.map { it.label })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a merged conference offers no held banner at all`() = runTest {
        // On the TC15 the bridge had both links open and the button read "2 calls merged",
        // over a banner saying the other member was on hold. The banner is tappable, and
        // the swap it offers holds everyone else — it would have torn down the conference
        // the user had just built (2026-09-12 12:01).
        val first = placeCall()
        engine.simulateRemoteAnswer(first)
        val second = placeCall()
        engine.simulateRemoteAnswer(second)
        engine.setHold(second, held = true)
        val viewModel = viewModel().also { it.watch(first) }
        runCurrent()

        viewModel.uiState.test {
            awaitActive { it.otherCalls.isNotEmpty() }

            viewModel.merge()
            runCurrent()

            val merged = awaitActive { it.mixedCallCount >= MIN_MIXED_IN_TEST }
            assertEquals(
                emptyList(),
                merged.otherCalls.map { it.title },
                "a conference member was drawn as on hold",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }
}
