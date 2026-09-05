package com.whatsappv2.telecom

import com.whatsappv2.domain.call.AudioRoute
import com.whatsappv2.domain.call.CallEvent
import com.whatsappv2.domain.model.HangupReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Telecom rules, asserted without a device (Task 34).
 *
 * The task's done-when list suggests `adb shell dumpsys telecom`, which needs a handset
 * and only reports what already happened. Extracting the decisions makes the rules
 * themselves checkable — the same trade Task 28 made with `ServiceRunPolicy`.
 */
class TelecomPolicyTest {

    // ---------------------------------------------------------------- audio routing

    @Test
    fun `a bluetooth headset wins over everything else`() {
        // Telecom reports the SET of routed devices, not one. Someone with a headset
        // paired and the speaker also on is listening to the headset.
        val both = TelecomPolicy.ROUTE_BLUETOOTH or TelecomPolicy.ROUTE_SPEAKER
        assertEquals(AudioRoute.BLUETOOTH, TelecomPolicy.audioRouteOf(both))
    }

    @Test
    fun `a wired headset wins over the speaker`() {
        val both = TelecomPolicy.ROUTE_WIRED_HEADSET or TelecomPolicy.ROUTE_SPEAKER
        assertEquals(AudioRoute.WIRED_HEADSET, TelecomPolicy.audioRouteOf(both))
    }

    @Test
    fun `each route maps on its own`() {
        assertEquals(AudioRoute.BLUETOOTH, TelecomPolicy.audioRouteOf(TelecomPolicy.ROUTE_BLUETOOTH))
        assertEquals(
            AudioRoute.WIRED_HEADSET,
            TelecomPolicy.audioRouteOf(TelecomPolicy.ROUTE_WIRED_HEADSET),
        )
        assertEquals(AudioRoute.SPEAKER, TelecomPolicy.audioRouteOf(TelecomPolicy.ROUTE_SPEAKER))
        assertEquals(AudioRoute.EARPIECE, TelecomPolicy.audioRouteOf(TelecomPolicy.ROUTE_EARPIECE))
    }

    @Test
    fun `an unknown route falls back to the earpiece, which every handset has`() {
        // Rather than throwing. A route this app does not recognise is not a reason to
        // fail a call that is already connected.
        assertEquals(AudioRoute.EARPIECE, TelecomPolicy.audioRouteOf(0))
        assertEquals(AudioRoute.EARPIECE, TelecomPolicy.audioRouteOf(UNKNOWN_ROUTE_BIT))
    }

    @Test
    fun `every route survives a round trip`() {
        // A mapping that is not its own inverse is how "switch to speaker" turns the
        // speaker off.
        AudioRoute.entries.forEach { route ->
            assertEquals(
                route,
                TelecomPolicy.audioRouteOf(TelecomPolicy.routeMaskOf(route)),
                "$route did not survive a round trip",
            )
        }
    }

    // ---------------------------------------------------------------- hold

    @Test
    fun `hold and unhold reach the FSM as local events`() {
        // Local, not remote: the user pressed hold on their own device. A remote hold is a
        // re-INVITE from the far end and arrives from the stack instead, and the FSM
        // treats the two differently.
        assertEquals(CallEvent.LocalHold, TelecomPolicy.holdEvent(held = true))
        assertEquals(CallEvent.LocalResume, TelecomPolicy.holdEvent(held = false))
    }

    // ---------------------------------------------------------------- disconnects

    @Test
    fun `a Telecom disconnect is a local hangup and a reject is a reject`() {
        // Telecom never tells us the far end hung up - that arrives from the stack. If
        // these were the same value the call log could not tell a missed call from one the
        // user ended, which is the whole point of Task 47.
        assertEquals(HangupReason.LOCAL_HANGUP, TelecomPolicy.disconnectReason)
        assertEquals(HangupReason.LOCAL_REJECTED, TelecomPolicy.rejectReason)
    }

    // ---------------------------------------------------------------- cellular calls

    @Test
    fun `a native call in progress stops a SIP call being placed`() {
        // §3 and the task's third done-when. Telecom is the only thing that knows about
        // the cellular call, so its answer is authoritative and this rule only says the
        // app must not override it.
        assertFalse(
            TelecomPolicy.mayPlaceCall(cellularCallInProgress = true, telecomPermits = true),
            "a cellular call must be honoured even if Telecom would allow this one",
        )
        assertFalse(
            TelecomPolicy.mayPlaceCall(cellularCallInProgress = false, telecomPermits = false),
            "Telecom's refusal is final",
        )
        assertTrue(TelecomPolicy.mayPlaceCall(cellularCallInProgress = false, telecomPermits = true))
    }

    private companion object {
        /** A bit no documented route uses, standing in for a platform that grew a new one. */
        const val UNKNOWN_ROUTE_BIT = 64
    }
}
