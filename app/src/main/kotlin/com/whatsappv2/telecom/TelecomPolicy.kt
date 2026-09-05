package com.whatsappv2.telecom

import com.whatsappv2.domain.call.AudioRoute
import com.whatsappv2.domain.call.CallEvent
import com.whatsappv2.domain.model.HangupReason

/**
 * Every decision Telecom asks of this app, with no Android type in sight (Task 34).
 *
 * ## Why this is a separate object
 *
 * The same reason `ServiceRunPolicy` is: the task's done-when list says the Telecom
 * callbacks must drive the FSM, and the only way to *assert* that is to be able to call
 * the mapping without a `ConnectionService`, a `PhoneAccount`, or a device. `dumpsys
 * telecom` tells you what happened afterwards; it does not tell you the rule was right.
 *
 * So [SipConnection] holds no logic. It receives a callback from the platform, asks this
 * object what that means, and forwards the answer. Everything below is a pure function of
 * its arguments and is unit-tested.
 */
internal object TelecomPolicy {

    /**
     * What `Connection.onDisconnect` means.
     *
     * Telecom does not say who hung up — it says the local user pressed the red button,
     * which is [HangupReason.LOCAL_HANGUP] by definition. A remote hangup arrives from the
     * SIP stack instead and never comes through this path; conflating the two loses the
     * distinction the call log is built on (Task 47).
     */
    val disconnectReason: HangupReason = HangupReason.LOCAL_HANGUP

    /** What `Connection.onReject` means. Distinct from a hangup: nothing was ever answered. */
    val rejectReason: HangupReason = HangupReason.LOCAL_REJECTED

    /**
     * Maps Telecom's audio-route bitmask to the domain's enum.
     *
     * Bluetooth wins over a wired headset, which wins over the speaker. That order is not
     * arbitrary: Telecom reports the *set* of currently-routed devices, and when someone
     * has a headset connected and the speaker also on, the headset is where they are
     * listening. Earpiece is the fallback because it is the one every handset has.
     *
     * @param routeMask `CallAudioState.getRoute()`, passed as an Int so this file needs no
     *   Android import and can be tested on the JVM.
     */
    fun audioRouteOf(routeMask: Int): AudioRoute = when {
        routeMask and ROUTE_BLUETOOTH != 0 -> AudioRoute.BLUETOOTH
        routeMask and ROUTE_WIRED_HEADSET != 0 -> AudioRoute.WIRED_HEADSET
        routeMask and ROUTE_SPEAKER != 0 -> AudioRoute.SPEAKER
        else -> AudioRoute.EARPIECE
    }

    /** The domain's enum back to Telecom's bitmask, for a route this app asks for. */
    fun routeMaskOf(route: AudioRoute): Int = when (route) {
        AudioRoute.BLUETOOTH -> ROUTE_BLUETOOTH
        AudioRoute.WIRED_HEADSET -> ROUTE_WIRED_HEADSET
        AudioRoute.SPEAKER -> ROUTE_SPEAKER
        AudioRoute.EARPIECE -> ROUTE_EARPIECE
    }

    /** Hold and unhold, as the FSM understands them. */
    fun holdEvent(held: Boolean): CallEvent =
        if (held) CallEvent.LocalHold else CallEvent.LocalResume

    /**
     * Whether a SIP call may be placed or shown while the cellular radio has one.
     *
     * §3 and the task's third done-when: a native call in progress must be honoured, not
     * talked over. Telecom answers this properly through `isOutgoingCallPermitted` and by
     * refusing the connection, and this app must **not** force its own UI in front of a
     * call the user is already on.
     *
     * Stated as a rule here so the intent is testable and cannot drift into "show it
     * anyway" during a later task.
     */
    fun mayPlaceCall(cellularCallInProgress: Boolean, telecomPermits: Boolean): Boolean =
        telecomPermits && !cellularCallInProgress

    /**
     * Telecom's audio-route constants, mirrored rather than imported.
     *
     * `CallAudioState.ROUTE_*` are `@hide`-adjacent platform constants whose values are
     * part of the public binder contract and have not changed since Lollipop. Copying them
     * is what lets this object stay free of Android so the mapping can be asserted on the
     * JVM. `SipConnection` is where the real constants are used, and its own use of them
     * is a straight pass-through with no branching to get wrong.
     */
    const val ROUTE_EARPIECE = 1
    const val ROUTE_BLUETOOTH = 2
    const val ROUTE_WIRED_HEADSET = 4
    const val ROUTE_SPEAKER = 8
}
