package com.whatsappv2.telecom

import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.di.ApplicationScope
import com.whatsappv2.domain.call.AudioRoute
import com.whatsappv2.domain.engine.SipCallController
import com.whatsappv2.domain.engine.SipMediaController
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.HangupReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries Telecom's callbacks to the engine, without a Service in the middle.
 *
 * ## Why this exists — the leak
 *
 * [SipConnectionService] used to be the [SipConnection.Listener] itself, so every
 * `SipConnection` held `listener = the service`. Those connections live in a **static**
 * map on the service's companion, because Telecom creates and destroys the service on its
 * own schedule while the rest of the app still needs to end a call. Static map → connection
 * → destroyed service: an `onDestroy`d `ConnectionService` was reachable from a GC root for
 * as long as its connection stayed in the map, and a new instance was retained every time
 * Telecom rebound. LeakCanary reported it as five instances of `ConnectionService$1`,
 * because the framework's own binder holds `this$0` and that is the reference it walks.
 *
 * A `@Singleton` listener has no such lifetime. Connections now hold this, which is meant
 * to live as long as the process, and no `Service` instance is reachable from static state.
 *
 * ## The scope is the application's, deliberately
 *
 * It used to be the service's, cancelled in `onDestroy`. That is wrong for the work being
 * done here: answering a call is not finished because Telecom happened to unbind the
 * service that received the button press. `@ApplicationScope` outlives the binding, which
 * is the lifetime the *call* actually has.
 */
@Singleton
internal class TelecomCallBridge @Inject constructor(
    private val calls: SipCallController,
    private val media: SipMediaController,
    private val logger: Logger,
    @ApplicationScope private val scope: CoroutineScope,
) : SipConnection.Listener {

    override fun onAnswered(callId: CallId) {
        logger.info(TAG, "Telecom answered $callId")
        // Audio, because Telecom's answer button has no way to say "with video" — a video
        // answer is offered by this app's own incoming screen (Task 54).
        scope.launch { calls.answer(callId, TelecomPolicy.telecomAnswerMedia) }
    }

    override fun onRejected(callId: CallId, reason: HangupReason) {
        logger.info(TAG, "Telecom rejected $callId ($reason)")
        SipConnectionService.release(callId)
        scope.launch { calls.reject(callId, reason) }
    }

    override fun onDisconnected(callId: CallId, reason: HangupReason) {
        logger.info(TAG, "Telecom disconnected $callId ($reason)")
        SipConnectionService.release(callId)
        scope.launch { calls.hangup(callId, reason) }
    }

    override fun onHoldChanged(callId: CallId, held: Boolean) {
        logger.info(TAG, "Hold changed for $callId: $held")
        scope.launch { calls.setHold(callId, held) }
    }

    override fun onAudioRouteChanged(callId: CallId, route: AudioRoute) {
        logger.info(TAG, "Audio route for $callId: $route")
        // Reported back through the same call the UI uses, so the in-call screen shows
        // where audio actually is. Asking the platform for the route it just announced is
        // a no-op there, which is why one path can serve both directions.
        scope.launch { media.setAudioRoute(callId, route) }
    }

    /**
     * A mute the **platform** changed — a headset button, or the system call UI.
     *
     * [SipConnection] only calls this when the value actually moved, which is the fix for
     * "mute does not work": Telecom reports its own mute state on every audio change, this
     * app has no public API to tell Telecom it muted itself, so the state Telecom reported
     * was permanently `false` and every route change used to un-mute the call the user had
     * just muted.
     */
    override fun onMuteChanged(callId: CallId, muted: Boolean) {
        logger.info(TAG, "Platform mute changed for $callId: $muted")
        scope.launch { media.setMuted(callId, muted) }
    }

    private companion object {
        const val TAG = "TelecomCallBridge"
    }
}
