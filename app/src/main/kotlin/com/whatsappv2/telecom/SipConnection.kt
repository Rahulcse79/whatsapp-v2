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
        fun onMuteChanged(callId: CallId, muted: Boolean)
    }

    /**
     * The mute state Telecom last **reported**. A mirror of the platform, and nothing else.
     *
     * ## Why nothing else may write here
     *
     * A self-managed connection has no public way to tell Telecom "I muted myself" —
     * `Connection.setMuteState` is package private and `requestCallEndpointChange` (API 34)
     * covers routing only. So Telecom's own `CallAudioState.isMuted` stays `false` through
     * an app-side mute, and it repeats that `false` on every audio event.
     *
     * That is survivable as long as this field means one thing. It used to be seeded with
     * the *app's* mute as well, which is what broke the mute button: muting set this to
     * `true` while Telecom still said `false`, so the very next audio event — any route
     * change, a device appearing — read as a genuine `true -> false` transition and
     * un-muted a live microphone. The bug was the seed, not the comparison.
     *
     * Left as a pure mirror, the comparison is right in every direction: an app-side mute
     * moves neither side and raises nothing, a headset's mute button moves Telecom's value
     * and is forwarded, and so is the same button unmuting again.
     */
    private var platformMuted = false

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

    /**
     * Telecom asked for a hold.
     *
     * The request is forwarded and **nothing here moves Telecom's own state**. It used to
     * call `setOnHold()` immediately, which is the same optimism `PjsipSipEngine.setHold`
     * refuses for the app's own UI: the re-INVITE may be rejected, and a platform that
     * believes a running call is held offers a resume button that resumes nothing. The
     * state moves when the stack says so — the engine reports it back through
     * [reportHeld], for a hold from any source.
     */
    override fun onHold() {
        listener.onHoldChanged(callId, held = true)
    }

    override fun onUnhold() {
        listener.onHoldChanged(callId, held = false)
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
        val current = state ?: return
        listener.onAudioRouteChanged(callId, TelecomPolicy.audioRouteOf(current.route))

        // One callback carries both, and the mute half matters: a headset's own mute
        // button reaches this app through here and nowhere else.
        //
        // Only when Telecom's own value actually moved. It repeats that value on every
        // audio event, including a plain route change, so forwarding it unconditionally
        // would re-assert a mute state nobody touched — and because the platform's value
        // is `false` throughout an app-side mute, what it would re-assert is "unmuted".
        // See [platformMuted] for why this is the only thing that may write to it.
        if (platformMuted != current.isMuted) {
            platformMuted = current.isMuted
            listener.onMuteChanged(callId, current.isMuted)
        }
    }

    /** The far end is ringing. Telecom shows this as an outgoing call in progress. */
    fun reportRinging() = setDialing()

    /**
     * Asks the platform to move call audio to [routeMask].
     *
     * Deprecated in API 34 in favour of `requestCallEndpointChange`, and still the only
     * route control that exists across this app's supported range — `minSdk` is 26. The
     * same trade as [onCallAudioStateChanged], and marked the same way so the obligation
     * travels with the code rather than hiding behind a bare suppression.
     */
    @Suppress("DEPRECATION")
    fun requestAudioRoute(routeMask: Int) = setAudioRoute(routeMask)

    /** The far end answered. */
    fun reportActive() = setActive()

    /**
     * The call was held or resumed by something other than Telecom (Task 41).
     *
     * A hold from this app's own screen, or a re-INVITE the far end sent. Telecom learns
     * about neither by itself, and a platform that believes a held call is active offers a
     * hold button on the lock screen and a car display for a call that is already held.
     *
     * Told for a remote hold too: as far as the system UI is concerned the call is held
     * either way, and the distinction that matters — who may resume it — is the app's, in
     * [com.whatsappv2.domain.call.HoldParty].
     */
    fun reportHeld(held: Boolean) = if (held) setOnHold() else setActive()

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
