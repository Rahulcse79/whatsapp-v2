package com.whatsappv2.feature.calls

import app.cash.turbine.test
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.call.AudioRoute
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.call.SecondCallResponse
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.engine.VideoSize
import com.whatsappv2.domain.engine.VideoSizes
import com.whatsappv2.domain.model.DtmfDigit
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.testing.FakeSipEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The call screen's state, driven by [FakeSipEngine] (Tasks 37 and 39).
 *
 * No SIP server, no device, no Telecom: the engine's fake runs the real
 * `CallStateMachine`, so a screen tested against it is tested against the same rules
 * production enforces.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallViewModelTest : CallViewModelFixture() {

    @Test
    fun `an outgoing call renders its phase as the stack moves it`() = runTest {
        val callId = placeCall()
        val viewModel = viewModel().also { it.watch(callId) }

        viewModel.uiState.test {
            assertIs<CallUiState.Loading>(awaitItem())

            engine.simulateRemoteRinging(callId)
            awaitDisplay { it.phase == CallPhase.RINGING }

            engine.simulateRemoteAnswer(callId)
            awaitDisplay { it.phase == CallPhase.CONNECTED }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `early media is its own phase, because it is audible`() = runTest {
        // Reporting it as ringing would leave the app playing a local ringback over the
        // announcement the network is already sending.
        val callId = placeCall()
        val viewModel = viewModel().also { it.watch(callId) }

        viewModel.uiState.test {
            skipItems(1)
            engine.simulateRemoteEarlyMedia(callId)

            awaitDisplay { it.phase == CallPhase.EARLY_MEDIA }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the timer counts from the answer, not from the dial`() = runTest {
        // Task 39's third done-when: driven by call start rather than a counter. The call
        // rang for thirty seconds and the duration must not include them.
        val callId = placeCall()
        val viewModel = viewModel().also { it.watch(callId) }

        viewModel.uiState.test {
            skipItems(1)
            clock.advanceBy(THIRTY_SECONDS)
            engine.simulateRemoteAnswer(callId)
            // Zero, not thirty: the call rang for half a minute and none of it counts.
            awaitDisplay { it.phase == CallPhase.CONNECTED && it.durationSeconds == 0L }

            clock.advanceBy(TEN_SECONDS)
            advanceTimeBy(TICK)
            runCurrent()

            awaitDisplay { it.durationSeconds == TEN_SECONDS / MILLIS_PER_SECOND }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a duration recomputed from the answer cannot drift`() = runTest {
        // The same subtraction gives the same answer however many ticks were missed,
        // which is the whole reason it is a subtraction and not a counter.
        val callId = placeCall()
        engine.simulateRemoteAnswer(callId)
        val viewModel = viewModel().also { it.watch(callId) }

        viewModel.uiState.test {
            skipItems(1)
            clock.advanceBy(ONE_HOUR)
            advanceTimeBy(TICK)
            runCurrent()

            awaitDisplay { it.durationSeconds == ONE_HOUR / MILLIS_PER_SECOND }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a call that ends finishes the screen`() = runTest {
        val callId = placeCall()
        engine.simulateRemoteAnswer(callId)
        val viewModel = viewModel().also { it.watch(callId) }

        viewModel.uiState.test {
            skipItems(1)
            // The screen has to have the call before it can lose it: Finished is "absent
            // after it was present", and a hangup in the same turn as the subscription
            // takes the call away before the screen was ever shown it.
            awaitDisplay { it.phase == CallPhase.CONNECTED }
            engine.simulateRemoteHangup(callId)

            awaitFinished()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a call that fails finishes the screen with the reason`() = runTest {
        // A busy line, a number that does not exist: the screen used to close on these
        // exactly as on a hang-up, and the dialler came back with nothing to say.
        val callId = placeCall()
        val viewModel = viewModel().also { it.watch(callId) }

        viewModel.uiState.test {
            skipItems(1)
            awaitDisplay { it.phase == CallPhase.CALLING }
            engine.simulateRemoteRejection(callId, SipError.Busy(BUSY_HERE))

            val finished = awaitFinished()
            assertEquals("That line was busy", finished.reason)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an inbound call is answered with what was offered, not with more`() = runTest {
        // Answering an audio offer with video is an escalation the peer never asked for.
        engine.givenRegistered(ACCOUNT)
        val incoming = engine.simulateIncomingCall(ACCOUNT.id, REMOTE)
        val viewModel = viewModel().also { it.watch(incoming.callId) }

        viewModel.uiState.test {
            skipItems(1)
            viewModel.answer(withVideo = false)
            runCurrent()

            val connected = awaitDisplay { it.phase == CallPhase.CONNECTED }
            assertEquals(MediaProfile.AUDIO.hasVideo, connected.videoOffered)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hold-and-answer moves the screen to the call just answered`() = runTest {
        // On the TC15 the screen stayed on the first call — now on hold, Resume button and
        // all — under a banner saying the second call was "on hold". The user had just
        // answered it; it was the live one. An accept re-points the screen like a swap does.
        val first = placeCall()
        engine.simulateRemoteAnswer(first)
        val viewModel = viewModel().also { it.watch(first) }
        val second = engine.simulateIncomingCall(ACCOUNT.id, REMOTE)

        viewModel.uiState.test {
            skipItems(1)
            viewModel.respondToSecondCall(second.callId, SecondCallResponse.ACCEPT_AND_HOLD)
            runCurrent()

            val shown = awaitDisplay { it.callId == second.callId && it.phase == CallPhase.CONNECTED }
            assertEquals(second.callId, shown.callId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ending the active call of a pair moves the screen to the held one`() = runTest {
        // The screen used to finish, leaving the held call reachable only from the
        // notification.
        val first = placeCall()
        engine.simulateRemoteAnswer(first)
        val viewModel = viewModel().also { it.watch(first) }
        val second = engine.simulateIncomingCall(ACCOUNT.id, REMOTE)

        viewModel.uiState.test {
            skipItems(1)
            viewModel.respondToSecondCall(second.callId, SecondCallResponse.ACCEPT_AND_HOLD)
            runCurrent()
            awaitDisplay { it.callId == second.callId && it.phase == CallPhase.CONNECTED }

            engine.simulateRemoteHangup(second.callId)
            runCurrent()

            val shown = awaitDisplay { it.callId == first }
            assertEquals(first, shown.callId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a call the screen is moved to, and that ends before it is drawn, finishes the screen`() = runTest {
        // Completing an attended transfer: the first call goes with the transfer and the
        // screen follows the consultation call - which the server hangs up within tens of
        // milliseconds. Here the screen is not even subscribed while that happens (the
        // user switched apps mid-consultation). It used to come back to "Connecting" for a
        // call that had already ended, with nothing to dismiss it.
        val first = placeCall()
        engine.simulateRemoteAnswer(first)
        val viewModel = viewModel().also { it.watch(first) }
        viewModel.uiState.test {
            skipItems(1)
            awaitDisplay { it.phase == CallPhase.CONNECTED }
            cancelAndIgnoreRemainingEvents()
        }
        // Past the view model's WhileSubscribed timeout, so the state flow really stops.
        advanceTimeBy(SUBSCRIPTION_TIMEOUT + 1)
        runCurrent()

        val consultation = engine.placeCall(ACCOUNT.id, REMOTE, MediaProfile.AUDIO).getOrNull()!!
        engine.simulateRemoteAnswer(consultation)
        engine.simulateRemoteHangup(first)
        runCurrent()
        engine.simulateRemoteHangup(consultation)
        runCurrent()

        viewModel.uiState.test {
            awaitFinished()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a transfer after the screen re-points goes to the call on screen`() = runTest {
        // The defect this covers, seen on a TC15: two calls, end the first, the screen
        // correctly follows the second — and Transfer then reported "That call has
        // already ended" about the call that had, while the one on screen was connected
        // and audible. The screen was handing the transfer the call id its route was
        // opened with, which a re-point makes stale.
        accounts.given(ACCOUNT)
        val first = placeCall()
        engine.simulateRemoteAnswer(first)
        val viewModel = viewModel().also { it.watch(first) }
        val second = engine.simulateIncomingCall(ACCOUNT.id, REMOTE)

        viewModel.uiState.test {
            // The first call has to be *shown* before the re-point, or the screen never
            // had the stale id in the first place and the test proves nothing.
            awaitDisplay { it.callId == first }
            viewModel.respondToSecondCall(second.callId, SecondCallResponse.ACCEPT_AND_HOLD)
            runCurrent()
            awaitDisplay { it.callId == second.callId && it.phase == CallPhase.CONNECTED }
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.transfer.blind("9198")
        runCurrent()

        // The second call is the one on screen, so it is the one that gets the REFER.
        val referred = engine.invocations.last { it.operation == FakeSipEngine.Operation.TRANSFER }
        assertTrue(
            referred.detail.startsWith("${second.callId.value}:"),
            "the REFER named ${referred.detail}, not the call on screen",
        )
    }

    @Test
    fun `recording after the screen re-points records the call on screen`() = runTest {
        accounts.given(ACCOUNT)
        val first = placeCall()
        engine.simulateRemoteAnswer(first)
        val viewModel = viewModel().also { it.watch(first) }
        val second = engine.simulateIncomingCall(ACCOUNT.id, REMOTE)

        viewModel.uiState.test {
            awaitDisplay { it.callId == first }
            viewModel.respondToSecondCall(second.callId, SecondCallResponse.ACCEPT_AND_HOLD)
            runCurrent()
            awaitDisplay { it.callId == second.callId && it.phase == CallPhase.CONNECTED }
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.recording.confirm()
        runCurrent()

        // Recording the call that ended would refuse; recording the wrong live call would
        // be worse — it captures a conversation nobody consented to on that leg.
        assertEquals(setOf(second.callId), recorder.active.value)
    }

    @Test
    fun `the held banner names a call that is actually held, and nothing else`() = runTest {
        // `otherCalls` used to be "every call except the one on screen", while the only
        // thing that renders it says "… is on hold — tap to swap". So a second call was
        // announced as held whatever it was doing.
        val first = placeCall()
        engine.simulateRemoteAnswer(first)
        val second = placeCall()
        engine.simulateRemoteAnswer(second)
        val viewModel = viewModel().also { it.watch(first) }

        viewModel.uiState.test {
            // Both connected: there is nothing on hold, so there is nothing to announce.
            val connected = awaitActive { it.call.callId == first && it.call.phase == CallPhase.CONNECTED }
            assertTrue(
                connected.otherCalls.isEmpty(),
                "a connected call was offered as held: ${connected.otherCalls.map { it.title }}",
            )

            engine.setHold(second, held = true)
            runCurrent()

            val held = awaitActive { it.otherCalls.isNotEmpty() }
            assertEquals(listOf(second), held.otherCalls.map { it.callId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the screen is told what shape the far end's picture is, and when it changes`() = runTest {
        // PJSIP's renderer stretches a frame to whatever bounds it is handed, so the view
        // has to be sized from this number rather than filling the space (TC15,
        // 2026-09-14: a landscape frame down a portrait screen, faces a head too tall).
        // It has to keep arriving, too — a conference canvas reflows as people join, and
        // a screen that only ever saw the first frame's shape would stretch every one
        // after it.
        val callId = placeCall()
        engine.simulateRemoteAnswer(callId)
        val viewModel = viewModel().also { it.watch(callId) }
        runCurrent()

        viewModel.uiState.test {
            val first = awaitActive { it.videoSizes == VideoSizes.UNKNOWN }
            assertFalse(first.videoSizes.remote.isKnown, "nothing is decoded yet")

            surfaces.sizes.value = VideoSizes(remote = VideoSize(352, 288), local = VideoSize(1080, 1080))
            runCurrent()
            val decoded = awaitActive { it.videoSizes.remote.isKnown }
            assertEquals(VideoSize(352, 288), decoded.videoSizes.remote)
            assertEquals(VideoSize(1080, 1080), decoded.videoSizes.local, "the self-view is stretched too")

            // The bridge reflows its canvas as a third member joins.
            surfaces.sizes.value = decoded.videoSizes.copy(remote = VideoSize(720, 1280))
            runCurrent()
            val reflowed = awaitActive { it.videoSizes.remote == VideoSize(720, 1280) }
            assertEquals(VideoSize(720, 1280), reflowed.videoSizes.remote)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a connected call offers Add call, so a conference can be started at all`() = runTest {
        // Merge only appears once two calls exist. Without this control the only route to
        // a second outgoing leg was to leave the call screen, reopen the app and find the
        // dialler, so local mixing (ADR-009) was reachable in principle and not in fact.
        val only = placeCall()
        engine.simulateRemoteAnswer(only)
        val viewModel = viewModel().also { it.watch(only) }

        viewModel.uiState.test {
            val connected = awaitDisplay { it.phase == CallPhase.CONNECTED }
            assertTrue(connected.availability.canAddCall, "a connected call could not add a second")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `merging mixes every established call on this device`() = runTest {
        // ADR-009: the phone has one audio bridge and one microphone, so "merge" can only
        // mean all of them — there is no pair to choose.
        val first = placeCall()
        engine.simulateRemoteAnswer(first)
        val viewModel = viewModel().also { it.watch(first) }
        val second = engine.simulateIncomingCall(ACCOUNT.id, REMOTE)
        engine.answer(second.callId, MediaProfile.AUDIO)
        runCurrent()

        viewModel.merge()
        runCurrent()

        assertEquals(listOf(setOf(first, second.callId)), engine.mixRequests)
    }

    @Test
    fun `a call that is still ringing is not mixed in`() = runTest {
        // It has no audio to contribute. It joins by itself when it is answered, because
        // the stack re-plans the mix on every media change.
        val first = placeCall()
        engine.simulateRemoteAnswer(first)
        val second = placeCall()
        engine.simulateRemoteAnswer(second)
        val ringing = engine.simulateIncomingCall(ACCOUNT.id, REMOTE)
        val viewModel = viewModel().also { it.watch(first) }
        runCurrent()

        viewModel.merge()
        runCurrent()

        val mixed = engine.mixRequests.single()
        assertTrue(ringing.callId !in mixed, "a ringing call was mixed in: $mixed")
        assertEquals(setOf(first, second), mixed)
    }

    @Test
    fun `one call is not a conference, and the stack is never asked`() = runTest {
        val only = placeCall()
        engine.simulateRemoteAnswer(only)
        val viewModel = viewModel().also { it.watch(only) }
        runCurrent()

        viewModel.merge()
        runCurrent()

        assertTrue(engine.mixRequests.isEmpty(), "the stack was asked to mix ${engine.mixRequests}")
    }

    @Test
    fun `declining sends a decline, not a busy`() = runTest {
        // The caller hears the difference: 603 means the user was there and said no.
        engine.givenRegistered(ACCOUNT)
        val incoming = engine.simulateIncomingCall(ACCOUNT.id, REMOTE)
        val viewModel = viewModel().also { it.watch(incoming.callId) }

        viewModel.reject()
        runCurrent()

        val terminated = assertIs<CallState.Terminated>(engine.terminatedCalls.single().state)
        assertEquals(HangupReason.LOCAL_REJECTED, terminated.reason)
    }

    @Test
    fun `muting reaches the engine and comes back in the state`() = runTest {
        val callId = placeCall()
        engine.simulateRemoteAnswer(callId)
        val viewModel = viewModel().also { it.watch(callId) }

        viewModel.uiState.test {
            skipItems(1)
            viewModel.setMuted(true)
            runCurrent()

            awaitDisplay { it.controls.isMuted }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a second press while the first is in flight is dropped, not queued`() = runTest {
        // Task 76. Two mutes racing each other end with the icon and the microphone
        // disagreeing, and whichever reply lands second wins for reasons the user cannot
        // see. The action is claimed synchronously on the press, so the second one here —
        // issued before the scheduler has run the first — finds MUTE already in flight.
        val callId = placeCall()
        engine.simulateRemoteAnswer(callId)
        val viewModel = viewModel().also { it.watch(callId) }
        engine.clearInvocations()

        viewModel.setMuted(true)
        viewModel.setMuted(false)
        runCurrent()

        val mutes = engine.invocations.count { it.operation == FakeSipEngine.Operation.SET_MUTED }
        assertEquals(1, mutes, "the opposite request was queued behind the one in flight")
    }

    @Test
    fun `a refused mute leaves the control usable and the microphone honest`() = runTest {
        // The reason Task 76 acknowledges the press but never the outcome: an optimistic
        // icon would read "Muted" over a live microphone whenever the engine refused.
        val callId = placeCall()
        engine.simulateRemoteAnswer(callId)
        engine.failNext(FakeSipEngine.Operation.SET_MUTED, SipError.EngineUnavailable)
        val viewModel = viewModel().also { it.watch(callId) }

        // Subscribed for the duration, so uiState is live rather than parked on its
        // initial value - WhileSubscribed does not run the upstream for a bare read.
        viewModel.uiState.test {
            skipItems(1)

            viewModel.events.test {
                viewModel.setMuted(true)
                runCurrent()
                assertIs<CallEvent.ActionFailed>(awaitItem())
            }

            val state = viewModel.uiState.value
            assertIs<CallUiState.Active>(state)
            assertFalse(state.call.controls.isMuted, "a refused mute was shown as muted")
            assertFalse(CallAction.MUTE in state.pendingActions, "the control was left stuck busy")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the speaker is a route, so turning it off returns to the earpiece`() = runTest {
        val callId = placeCall()
        engine.simulateRemoteAnswer(callId)
        val viewModel = viewModel().also { it.watch(callId) }

        viewModel.uiState.test {
            skipItems(1)
            viewModel.setSpeakerOn(true)
            runCurrent()
            awaitDisplay { it.controls.audioRoute == AudioRoute.SPEAKER }

            viewModel.setSpeakerOn(false)
            runCurrent()
            awaitDisplay { it.controls.audioRoute == AudioRoute.EARPIECE }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `holding and resuming move the call through the FSM the engine enforces`() = runTest {
        // The fake runs the real CallStateMachine, so this is the same rule production
        // applies: Connected -> Held -> Resuming -> Connected, and nothing skipped.
        val callId = placeCall()
        engine.simulateRemoteAnswer(callId)
        val viewModel = viewModel().also { it.watch(callId) }

        viewModel.uiState.test {
            skipItems(1)
            viewModel.setHold(true)
            runCurrent()
            awaitDisplay { it.phase == CallPhase.ON_HOLD }

            viewModel.setHold(false)
            runCurrent()
            awaitDisplay { it.phase == CallPhase.CONNECTED }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a hold applied by the far end is shown as theirs, and offers no resume`() = runTest {
        // Pressing resume would change nothing: the far end is the only side that can
        // lift it, which is why the phase and the availability are different values.
        val callId = placeCall()
        engine.simulateRemoteAnswer(callId)
        val viewModel = viewModel().also { it.watch(callId) }

        viewModel.uiState.test {
            skipItems(1)
            engine.simulateRemoteHold(callId)

            val held = awaitDisplay { it.phase == CallPhase.HELD_BY_REMOTE }
            assertEquals(false, held.availability.canResume)
            assertEquals(false, held.availability.canSendDtmf)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a keypad press sends exactly that digit`() = runTest {
        // One digit per press: an IVR acts on each tone as it arrives, so a batch sent
        // together is a sequence the caller never typed.
        val callId = placeCall()
        engine.simulateRemoteAnswer(callId)
        val viewModel = viewModel().also { it.watch(callId) }

        viewModel.sendDtmf(DtmfDigit.STAR)
        viewModel.sendDtmf(DtmfDigit.SEVEN)
        runCurrent()

        assertEquals(
            listOf("${callId.value}:*", "${callId.value}:7"),
            engine.invocations
                .filter { it.operation == FakeSipEngine.Operation.SEND_DTMF }
                .map { it.detail },
        )
    }

    @Test
    fun `a digit the engine refuses is reported, not lost`() = runTest {
        // A tone that never left is worse than an error: the caller keeps pressing keys
        // an IVR will never hear.
        val callId = placeCall()
        engine.simulateRemoteAnswer(callId)
        engine.failNext(FakeSipEngine.Operation.SEND_DTMF, SipError.EngineUnavailable)
        val viewModel = viewModel().also { it.watch(callId) }

        viewModel.events.test {
            viewModel.sendDtmf(DtmfDigit.ONE)
            runCurrent()

            val event = assertIs<CallEvent.ActionFailed>(awaitItem())
            assertEquals(CallAction.DTMF, event.action)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an action the engine refuses is reported rather than swallowed`() = runTest {
        // The user pressed a button and is owed an answer. A silent failure is how a
        // screen ends up looking broken with nothing in the log.
        val callId = placeCall()
        engine.simulateRemoteAnswer(callId)
        engine.failNext(FakeSipEngine.Operation.HANGUP, SipError.EngineUnavailable)
        val viewModel = viewModel().also { it.watch(callId) }

        viewModel.events.test {
            viewModel.hangUp()
            runCurrent()

            val event = assertIs<CallEvent.ActionFailed>(awaitItem())
            assertEquals(CallAction.HANG_UP, event.action)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------------------------------------------------------------- helpers

    @Test
    fun `a caller in the address book is shown by the name the user filed them under`() =
        runTest {
            // Task 49's first done-when. The contact's name wins over whatever the far end
            // put in its From header: the user's own word for a person is the one they
            // will recognise.
            contacts.given(REMOTE, name = "Bob Smith", photoUri = "content://photo/1")
            val callId = placeCall()
            val viewModel = viewModel().also { it.watch(callId) }

            viewModel.uiState.test {
                skipItems(1)
                val call = awaitDisplay { it.title == "Bob Smith" }

                assertEquals("content://photo/1", call.photoUri)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a caller nobody has filed is still shown, by their address`() = runTest {
        // Also the shape of a device where READ_CONTACTS was declined: the screen is
        // exactly what it was before contacts existed, which is the whole promise.
        val callId = placeCall()
        val viewModel = viewModel().also { it.watch(callId) }

        viewModel.uiState.test {
            skipItems(1)
            val call = awaitDisplay { it.title.isNotBlank() }

            assertNull(call.photoUri)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `merging video calls moves the screen to the conference leg`() = runTest {
        // The defect this pins: a bridged merge replaces every leg with one call to the
        // room, so a screen still watching a merged leg would show it being transferred
        // away and then ending — the conference the user just built, apparently hanging up
        // on them.
        engine.givenRegistered(ACCOUNT)
        // The room is resolved against the account's domain, so the repository has to
        // know the account — `givenRegistered` is the engine's business, not its.
        accounts.given(ACCOUNT)
        val first = engine.placeCall(ACCOUNT.id, REMOTE, MediaProfile.AUDIO_VIDEO).getOrNull()!!
        engine.simulateRemoteAnswer(first)
        val second = engine.placeCall(ACCOUNT.id, OTHER, MediaProfile.AUDIO_VIDEO).getOrNull()!!
        engine.simulateRemoteAnswer(second)

        val viewModel = viewModel().also { it.watch(first) }
        runCurrent()

        viewModel.uiState.test {
            awaitActive { it.call.phase == CallPhase.CONNECTED }

            viewModel.merge()
            runCurrent()

            val conference = awaitActive { it.conference != null }
            // Watching the room, not either merged leg.
            assertTrue(conference.call.callId != first && conference.call.callId != second)
            assertEquals(
                "sip:3000@sip.example.com",
                engine.conferences.value.single { it.callId == conference.call.callId }
                    .conferenceUri.render(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `merging audio calls mixes here and leaves the screen where it is`() = runTest {
        engine.givenRegistered(ACCOUNT)
        val first = engine.placeCall(ACCOUNT.id, REMOTE, MediaProfile.AUDIO).getOrNull()!!
        engine.simulateRemoteAnswer(first)
        val second = engine.placeCall(ACCOUNT.id, OTHER, MediaProfile.AUDIO).getOrNull()!!
        engine.simulateRemoteAnswer(second)

        val viewModel = viewModel().also { it.watch(first) }
        runCurrent()

        viewModel.uiState.test {
            awaitActive { it.call.phase == CallPhase.CONNECTED }

            viewModel.merge()
            runCurrent()

            val merged = awaitActive { it.call.isMixed }
            assertEquals(first, merged.call.callId, "a local mix keeps every leg, and the screen")
            assertTrue(engine.bridgeMergeRequests.isEmpty(), "no server was involved")
            cancelAndIgnoreRemainingEvents()
        }
    }
}

/** 486, the response the fake turns into `HangupReason.BUSY`. */
private const val BUSY_HERE = 486
