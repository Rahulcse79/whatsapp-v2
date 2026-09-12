package com.whatsappv2.telecom

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.HangupReason
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CompletableDeferred
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
 * Nothing outside this module has any business holding a `Connection`. The manifest names
 * the class by string and the JVM sees it as public, so Telecom binds it exactly as before.
 *
 * ## It is not the listener, and that is a leak fix
 *
 * This service used to implement [SipConnection.Listener] and hand itself to every
 * connection it created. Connections live in a **static** map below, so a destroyed
 * service stayed reachable from a GC root for as long as one of its connections did —
 * which LeakCanary reported as retained `ConnectionService$1` instances, the framework's
 * own binder being what holds `this$0`. [TelecomCallBridge] is a `@Singleton` and takes
 * that role now; nothing here outlives its binding.
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
internal class SipConnectionService : ConnectionService() {

    @Inject
    lateinit var logger: Logger

    /**
     * Where Telecom's callbacks go.
     *
     * A `@Singleton`, not this service: see the class documentation. Connections hold it
     * for their whole lifetime, and it must not be something Telecom can destroy.
     */
    @Inject
    lateinit var bridge: TelecomCallBridge

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection? {
        val callId = request?.extras?.getString(EXTRA_CALL_ID)
        if (callId == null) {
            logger.error(TAG, "Outgoing connection requested with no call id")
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        }
        // Telecom answering after we stopped waiting. Cancelling is what keeps the
        // platform's call list honest; left alone this becomes a permanent DIALING call
        // that makes every future call "on another call".
        if (wasAbandoned(CallId(callId))) {
            logger.warn(TAG, "Telecom created $callId after the wait timed out; cancelling it")
            return Connection.createCanceledConnection()
        }

        return newConnection(CallId(callId)).also {
            // Without this the platform's record of the call has `handle=null`: the system
            // call UI, the lock screen and a car display all show a call from nobody, and
            // `dumpsys telecom` confirms it. The app's own screen looked right only
            // because it reads the engine directly rather than Telecom.
            request.address?.let { address ->
                it.setAddress(address, TelecomManager.PRESENTATION_ALLOWED)
            }
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
            // The caller's address, which reached Telecom in EXTRA_INCOMING_CALL_ADDRESS
            // and comes back on the request. Setting it here is what puts a number on the
            // incoming-call UI the platform draws; without it the call rings as `handle=
            // null` and nothing outside this app knows who is calling.
            request.address?.let { address ->
                it.setAddress(address, TelecomManager.PRESENTATION_ALLOWED)
            }
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
        SipConnection(callId, listener = bridge, logger = logger)
            .also { connections[callId] = it }

    /**
     * Empties the static registry, because it does not die with this instance.
     *
     * Telecom destroys this service when it holds no more connections, so in the ordinary
     * case both maps are already empty and this is a no-op. What it exists for is the case
     * where they are not: entries left behind are `Connection`s the platform has already
     * torn its side of, and they would sit in a **static** map for the life of the process
     * — answering [liveCallIds] with calls that do not exist, which is precisely the
     * question a rebuilt screen asks after a process death.
     *
     * They are not `destroy()`ed on the way out. Telecom has unbound by the time this runs
     * and the binder behind each one is gone; the call's real state lives in the FSM and
     * the SIP stack, which is where it was always the source of truth.
     *
     * Waiters are released rather than dropped. A caller suspended in
     * `TelecomCallRegistry.awaitDecision` would otherwise sit until its own timeout for an
     * answer that can no longer come.
     */
    override fun onDestroy() {
        val orphans = connections.keys.toList()
        if (orphans.isNotEmpty()) {
            logger.warn(TAG, "Telecom destroyed the service holding ${orphans.size} connection(s)")
        }
        connections.clear()

        pending.keys.toList().forEach { callId -> pending.remove(callId)?.complete(false) }
        super.onDestroy()
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
        /**
         * The calls Telecom is holding a connection for, right now (Task 45).
         *
         * This is the platform's record, not the app's: it is what a rebuilt screen asks
         * "is there still a call?" after the process was killed and restarted. An empty
         * answer is a real answer — the call did not survive — and the screen finishing
         * on it is better than one stuck on Loading for a call that is over.
         */
        fun liveCallIds(): Set<CallId> = connections.keys.toSet()

        fun expect(callId: CallId): CompletableDeferred<Boolean> =
            CompletableDeferred<Boolean>().also { pending[callId] = it }

        /**
         * Calls this app stopped waiting for, so a late connection can be cancelled.
         *
         * Bounded by the fact that an entry only survives until Telecom answers for that
         * id, and every id is used once. A call Telecom never answers for at all leaves
         * one dead entry, which is a string — the alternative was leaving a live call in
         * the platform, which is what this whole mechanism exists to stop.
         */
        private val abandoned = ConcurrentHashMap.newKeySet<CallId>()

        /**
         * Abandons a wait that timed out or was never handed over.
         *
         * Removing the waiter is not enough, and that was the bug. Telecom can still call
         * [onCreateOutgoingConnection] *after* the timeout: it creates a real connection,
         * sets it DIALING, and nobody ever ends it, because the app gave up on the call
         * and never placed it. The result is a phantom call in the platform for the life
         * of the process — `dumpsys telecom` showed two of them against zero real legs —
         * and Telecom then refuses every later call, which reaches the user as the one
         * sentence that names a cause that is not true: "Your phone is on another call".
         *
         * So the id is remembered, and a connection that arrives for it is cancelled on
         * the spot.
         */
        fun forget(callId: CallId) {
            pending.remove(callId)
            abandoned += callId
        }

        /** True when this call was given up on; clears the mark, since ids are used once. */
        private fun wasAbandoned(callId: CallId): Boolean = abandoned.remove(callId)

        private fun settle(callId: CallId, created: Boolean) {
            pending.remove(callId)?.complete(created)
        }

        /**
         * Drops a connection from the registry without telling Telecom anything.
         *
         * For the callbacks that arrive *from* Telecom — it already knows the call is over,
         * and this only stops the map holding the connection afterwards.
         */
        fun release(callId: CallId) {
            connections.remove(callId)
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
         * Tells Telecom a call was held or resumed by something it did not do (Task 41).
         *
         * Safe for a call the platform never accepted: the map has no entry and nothing
         * happens, which is the same contract every other method here keeps.
         */
        fun reportHeld(callId: CallId, held: Boolean) {
            connections[callId]?.reportHeld(held)
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
