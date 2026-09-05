package com.whatsappv2.call

import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.engine.CallSnapshot

/**
 * Which notification a set of calls calls for (Task 37).
 *
 * Pure, and separate from the code that builds the notification, because the interesting
 * part is the choice rather than the layout: a ringing call outranks an established one,
 * an established call outranks nothing at all, and a device with no calls shows the
 * registration summary instead. All three are assertable on the JVM; none of them is
 * assertable through a `Notification`.
 */
sealed interface CallNotification {

    /** No call. The foreground service falls back to its registration summary. */
    data object None : CallNotification

    /** A call is ringing and must be answerable without unlocking the device. */
    data class Incoming(val call: CallSnapshot) : CallNotification

    /** A call is in progress. */
    data class Ongoing(val call: CallSnapshot) : CallNotification
}

object CallNotificationPolicy {

    /**
     * Picks the notification for [calls].
     *
     * Ringing wins. With a call already in progress and a second one arriving, the one the
     * user must decide about now is the new one — the established call is not going
     * anywhere, and burying the ringing call under it is how calls get missed (Task 56
     * builds the rest of call waiting on this).
     *
     * Among equals the oldest wins, so a notification does not shuffle between two calls
     * as their states change.
     */
    fun decide(calls: List<CallSnapshot>): CallNotification {
        val ringing = calls.filter { it.state is CallState.Incoming }.minByOrNull { it.startedAtEpochMillis }
        if (ringing != null) return CallNotification.Incoming(ringing)

        val ongoing = calls.filter { it.state.isActive }.minByOrNull { it.startedAtEpochMillis }
        return ongoing?.let { CallNotification.Ongoing(it) } ?: CallNotification.None
    }
}
