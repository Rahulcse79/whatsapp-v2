package com.whatsappv2.telecom

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.domain.call.AudioRoute
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.HangupReason
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Telecom's entry point into this app (Task 34, §3).
 *
 * Self-managed, so Telecom asks this service to create a [Connection] and then leaves the
 * UI alone. What it does *not* leave alone is arbitration: it knows about the cellular
 * call this app cannot see, and it will refuse a connection rather than let a SIP call
 * talk over one. That refusal is the third done-when, and honouring it means doing nothing
 * when it happens — no fallback, no forcing our own screen in front.
 *
 * ## Registry, not state
 *
 * Live connections are held here by [CallId] so the rest of the app can tell Telecom that
 * a call ended for a reason Telecom did not cause — a remote hangup, a 486. Without that
 * the platform keeps audio focus for a call that is over. It is a map and nothing more;
 * the call's actual state lives in the FSM and the SIP stack.
 */
@AndroidEntryPoint
class SipConnectionService : ConnectionService(), SipConnection.Listener {

    @Inject
    lateinit var logger: Logger

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection? {
        val callId = request?.extras?.getString(EXTRA_CALL_ID)
        if (callId == null) {
            logger.error(TAG, "Outgoing connection requested with no call id")
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        }
        return newConnection(CallId(callId)).also { it.setDialing() }
    }

    /**
     * Telecom refused the call.
     *
     * Almost always because the user is on a cellular call. §3 says to honour that, so the
     * only thing this does is record it — the placing code learns through the failed
     * connection, and nothing here tries to place it anyway.
     */
    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        logger.warn(TAG, "Telecom refused an outgoing call; a native call is likely in progress")
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
        return newConnection(CallId(callId)).also { it.setRinging() }
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        // Task 37 turns this into a missed-call entry. Refusing to show it is correct
        // regardless: the user is on another call and Telecom said so.
        logger.warn(TAG, "Telecom refused an incoming call; it will not be shown")
    }

    private fun newConnection(callId: CallId): SipConnection =
        SipConnection(callId, listener = this, logger = logger)
            .also { connections[callId] = it }

    // ---------------------------------------------------------------- SipConnection.Listener
    //
    // Task 35 onward gives these bodies: answering and hanging up need the engine's call
    // controller, which reports EngineUnavailable today. Logging rather than pretending is
    // the same choice UnavailableSipEngine makes - a stub that looked like it worked would
    // let the in-call screen be built against behaviour that does not exist.

    override fun onAnswered(callId: CallId) {
        logger.info(TAG, "Answer requested for $callId")
    }

    override fun onRejected(callId: CallId, reason: HangupReason) {
        logger.info(TAG, "Reject requested for $callId ($reason)")
        connections.remove(callId)
    }

    override fun onDisconnected(callId: CallId, reason: HangupReason) {
        logger.info(TAG, "Disconnect requested for $callId ($reason)")
        connections.remove(callId)
    }

    override fun onHoldChanged(callId: CallId, held: Boolean) {
        logger.info(TAG, "Hold changed for $callId: $held")
    }

    override fun onAudioRouteChanged(callId: CallId, route: AudioRoute) {
        logger.info(TAG, "Audio route for $callId: $route")
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

        /** Tells Telecom a call ended for a reason Telecom did not cause. */
        fun reportEnded(callId: CallId, reason: HangupReason) {
            connections.remove(callId)?.reportEnded(reason)
        }

        /** Tells Telecom the far end answered. */
        fun reportActive(callId: CallId) {
            connections[callId]?.reportActive()
        }
    }
}
