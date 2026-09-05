package com.whatsappv2.telecom

import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.DisconnectCause
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.domain.call.AudioRoute
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.HangupReason

/**
 * One SIP call, as Telecom sees it (Task 34).
 *
 * ## It decides nothing
 *
 * Every override here is the same three lines: translate through [TelecomPolicy], tell the
 * listener, and move Telecom's own state along. That is deliberate — a `Connection` cannot
 * be constructed off a device, so any logic living here is logic no test can reach. The
 * rules are in [TelecomPolicy], which is a plain object, and they are asserted there.
 *
 * ## Two state machines, and they are not the same one
 *
 * Telecom has its own notion of a call's state (`setDialing`, `setActive`, `setOnHold`)
 * and the app has the Task 9 FSM. This class keeps Telecom's in step; it does not try to
 * make Telecom the source of truth. The SIP stack is the source of truth, because it is
 * the thing actually talking to the far end — Telecom does not know a 183 from a 200.
 */
internal class SipConnection(
    val callId: CallId,
    private val listener: Listener,
    private val logger: Logger,
) : Connection() {

    /** What this connection reports upward. Implemented by the service that owns it. */
    interface Listener {
        fun onAnswered(callId: CallId)
        fun onRejected(callId: CallId, reason: HangupReason)
        fun onDisconnected(callId: CallId, reason: HangupReason)
        fun onHoldChanged(callId: CallId, held: Boolean)
        fun onAudioRouteChanged(callId: CallId, route: AudioRoute)
    }

    init {
        // Self-managed: this app draws its own in-call UI and Telecom must not hand the
        // call to the system dialer.
        connectionProperties = PROPERTY_SELF_MANAGED
        // Everything a self-managed voice call needs Telecom to permit.
        connectionCapabilities = CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD or CAPABILITY_MUTE
        // Without this the platform treats the call as a media stream and routes it to the
        // speaker rather than the earpiece.
        audioModeIsVoip = true
    }

    override fun onAnswer() {
        logger.info(TAG, "Telecom answered $callId")
        listener.onAnswered(callId)
    }

    override fun onReject() {
        logger.info(TAG, "Telecom rejected $callId")
        listener.onRejected(callId, TelecomPolicy.rejectReason)
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
    }

    override fun onDisconnect() {
        logger.info(TAG, "Telecom disconnected $callId")
        listener.onDisconnected(callId, TelecomPolicy.disconnectReason)
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
    }

    override fun onHold() {
        listener.onHoldChanged(callId, held = true)
        setOnHold()
    }

    override fun onUnhold() {
        listener.onHoldChanged(callId, held = false)
        setActive()
    }

    /**
     * Deprecated in the platform, and still the one that fires here.
     *
     * `CallEndpoint` and `onCallEndpointChanged` replace this, and they arrived in API 34.
     * `minSdk` is 26, so on most of the range this app supports the replacement does not
     * exist and this callback is the only notification of a route change there is.
     * Overriding it is therefore correct, not legacy — and it stays correct until minSdk
     * moves, or until the app adopts `androidx.core:core-telecom`, which back-ports the
     * newer model.
     *
     * Marked rather than suppressed so the obligation travels with the code: a reader sees
     * the deprecation and its reason at the call site instead of finding a bare
     * `@Suppress` and having to work out what it was hiding.
     */
    @Deprecated("Platform replaced this with onCallEndpointChanged in API 34; minSdk is 26")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onCallAudioStateChanged(state: CallAudioState?) {
        val route = TelecomPolicy.audioRouteOf(state?.route ?: return)
        listener.onAudioRouteChanged(callId, route)
    }

    /** The far end is ringing. Telecom shows this as an outgoing call in progress. */
    fun reportRinging() = setDialing()

    /** The far end answered. */
    fun reportActive() = setActive()

    /**
     * The call ended for a reason that did not come from Telecom.
     *
     * A remote hangup, a network failure, a 486. Telecom has to be told, or the platform
     * keeps holding audio focus for a call that is over.
     */
    fun reportEnded(reason: HangupReason) {
        setDisconnected(DisconnectCause(disconnectCauseFor(reason)))
        destroy()
    }

    /**
     * The domain's reason, as Telecom's cause code.
     *
     * Telecom's vocabulary is coarser than the domain's on purpose — it drives platform
     * behaviour (whether to play a busy tone, whether to log a missed call), not what the
     * app displays. The app's own reason survives untranslated in the call log.
     */
    private fun disconnectCauseFor(reason: HangupReason): Int = when (reason) {
        HangupReason.LOCAL_HANGUP -> DisconnectCause.LOCAL
        HangupReason.LOCAL_REJECTED -> DisconnectCause.REJECTED
        HangupReason.REMOTE_HANGUP -> DisconnectCause.REMOTE
        HangupReason.BUSY -> DisconnectCause.BUSY
        HangupReason.DECLINED -> DisconnectCause.REJECTED
        HangupReason.NO_ANSWER -> DisconnectCause.MISSED
        HangupReason.CANCELLED -> DisconnectCause.CANCELED
        // Three different faults, one platform cause: Telecom's vocabulary has no way to
        // say "the codecs did not agree". The distinction is kept in the domain reason,
        // which is what the call log and the UI read.
        HangupReason.NETWORK_FAILURE,
        HangupReason.MEDIA_FAILURE,
        HangupReason.SERVER_ERROR,
        -> DisconnectCause.ERROR
    }

    private companion object {
        const val TAG = "SipConnection"
    }
}
