package com.whatsappv2.telecom

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.domain.call.AudioRoute
import com.whatsappv2.domain.engine.SipCallController
import com.whatsappv2.domain.engine.SipMediaController
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.HangupReason
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Telecom's entry point into this app (Task 34, §3).
 *
 * Self-managed, so Telecom asks this service to create a [Connection] and then leaves the
 * UI alone. What it does *not* leave alone is arbitration: it knows about the cellular
 * call this app cannot see, and it will refuse a connection rather than let a SIP call
 * talk over one. That refusal is honoured by doing nothing — no fallback, no forcing our
 * own screen in front.
 *
 * ## Internal, and named in the manifest
 *
 * Nothing outside this module has any business holding a `Connection`, and Kotlin agrees:
 * a public class may not implement [SipConnection.Listener], which is internal because
 * `SipConnection` is. The manifest names the class by string and the JVM sees it as
 * public, so Telecom binds it exactly as before.
 *
 * ## Registry, not state
 *
 * Live connections are held here by [CallId] so the rest of the app can tell Telecom that
 * a call ended for a reason Telecom did not cause — a remote hangup, a 486. Without that
 * the platform keeps audio focus for a call that is over. It is a map and nothing more;
 * the call's actual state lives in the FSM and the SIP stack.
 *
 * ## The callbacks now do something (Task 35)
 *
 * Answer, reject, disconnect, hold and mute arrive here from the platform — from the
 * lock-screen buttons, from a Bluetooth headset, from Android Auto — and are forwarded to
 * the engine, which is the one thing that can act on them. They were logged and dropped
 * until the call controller existed; it does now.
 */
@AndroidEntryPoint
internal class SipConnectionService : ConnectionService(), SipConnection.Listener {

    @Inject
    lateinit var logger: Logger

    @Inject
    lateinit var calls: SipCallController

    @Inject
    lateinit var media: SipMediaController

    /**
     * Where the forwarded callbacks run.
     *
     * The engine's operations suspend and Telecom's callbacks do not, so there has to be
     * somewhere to put the work. Cancelled with the service: a callback still running for
     * a service Telecom has torn down is acting on a call nothing is showing.
     */
    private val scope = CoroutineScope(SupervisorJob())

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection? {
        val callId = request?.extras?.getString(EXTRA_CALL_ID)
        if (callId == null) {
            logger.error(TAG, "Outgoing connection requested with no call id")
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        }
        return newConnection(CallId(callId)).also {
            it.setDialing()
            settle(CallId(callId), created = true)
        }
    }

    /**
     * Telecom refused the call.
     *
     * Almost always because the user is on a cellular call. §3 says to honour that, so the
     * only thing this does is record it and release whoever is waiting — the engine then
     * fails the call with `CallNotPermitted` and no INVITE is sent.
     */
    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        logger.warn(TAG, "Telecom refused an outgoing call; a native call is likely in progress")
        request?.extras?.getString(EXTRA_CALL_ID)?.let { settle(CallId(it), created = false) }
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection? {
        val callId = request?.extras?.getString(EXTRA_CALL_ID)
        if (callId == null) {
            logger.error(TAG, "Incoming connection requested with no call id")
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        }
        return newConnection(CallId(callId)).also {
            it.setRinging()
            settle(CallId(callId), created = true)
        }
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        // The user is on another call and Telecom said so. The engine answers 486 and
        // shows nothing: refusing to display it is the whole behaviour (§3).
        logger.warn(TAG, "Telecom refused an incoming call; it will not be shown")
        request?.extras?.getString(EXTRA_CALL_ID)?.let { settle(CallId(it), created = false) }
    }

    private fun newConnection(callId: CallId): SipConnection =
        SipConnection(callId, listener = this, logger = logger)
            .also { connections[callId] = it }

    // ---------------------------------------------------------------- SipConnection.Listener

    override fun onAnswered(callId: CallId) {
        logger.info(TAG, "Telecom answered $callId")
        // Audio, because Telecom's answer button has no way to say "with video" — a video
        // answer is offered by this app's own incoming screen (Task 54).
        scope.launch { calls.answer(callId, TelecomPolicy.telecomAnswerMedia) }
    }

    override fun onRejected(callId: CallId, reason: HangupReason) {
        logger.info(TAG, "Telecom rejected $callId ($reason)")
        connections.remove(callId)
        scope.launch { calls.reject(callId, reason) }
    }

    override fun onDisconnected(callId: CallId, reason: HangupReason) {
        logger.info(TAG, "Telecom disconnected $callId ($reason)")
        connections.remove(callId)
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

    override fun onMuteChanged(callId: CallId, muted: Boolean) {
        logger.info(TAG, "Mute changed for $callId: $muted")
        scope.launch { media.setMuted(callId, muted) }
    }

    companion object {
        private const val TAG = "SipConnectionService"

        /** The app's own call id, carried through Telecom's extras and back. */
        const val EXTRA_CALL_ID = "com.whatsappv2.telecom.CALL_ID"

        /**
         * Live connections, keyed by the app's call id.
         *
         * Static because Telecom constructs and destroys the service on its own schedule
         * and the caller that needs to end a call is not holding a binding to it. A
         * `ConcurrentHashMap` because Telecom's callbacks and the SIP stack's events do
         * not share a thread.
         */
        private val connections = ConcurrentHashMap<CallId, SipConnection>()

        /**
         * Callers waiting to hear whether Telecom created a connection.
         *
         * The platform answers asynchronously and through a different object than the one
         * that asked, so the answer has to be parked somewhere both can reach. This is
         * what makes "the connection exists before the INVITE" enforceable rather than
         * hoped for.
         */
        private val pending = ConcurrentHashMap<CallId, CompletableDeferred<Boolean>>()

        /** Registers interest in [callId] before asking Telecom for it. */
        fun expect(callId: CallId): CompletableDeferred<Boolean> =
            CompletableDeferred<Boolean>().also { pending[callId] = it }

        /** Abandons a wait that timed out, so the map does not grow a dead entry per call. */
        fun forget(callId: CallId) {
            pending.remove(callId)
        }

        private fun settle(callId: CallId, created: Boolean) {
            pending.remove(callId)?.complete(created)
        }

        /** Tells Telecom a call ended for a reason Telecom did not cause. */
        fun reportEnded(callId: CallId, reason: HangupReason) {
            connections.remove(callId)?.reportEnded(reason)
        }

        /** Tells Telecom the far end answered. */
        fun reportActive(callId: CallId) {
            connections[callId]?.reportActive()
        }

        /**
         * Asks Telecom to route this call's audio.
         *
         * @return false when there is no connection to ask — a call the platform never
         *   accepted, which is exactly when a caller must not report the route as changed.
         */
        fun requestAudioRoute(callId: CallId, routeMask: Int): Boolean {
            val connection = connections[callId] ?: return false
            connection.requestAudioRoute(routeMask)
            return true
        }
    }
}
