package com.whatsappv2.domain.testing

import com.whatsappv2.domain.call.AudioRoute
import com.whatsappv2.domain.engine.CallSnapshot
import com.whatsappv2.domain.engine.IncomingCall
import com.whatsappv2.domain.engine.PlatformCallRegistry
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.HangupReason

/**
 * A [PlatformCallRegistry] with no platform behind it.
 *
 * Telecom cannot be constructed off a device, which would otherwise put "the connection
 * exists before the INVITE" and "a refusal is honoured" beyond the reach of any test. Both
 * are rules rather than mechanisms, so both are asserted here instead: the fake records
 * what it was asked and answers whatever the test told it to.
 *
 * [onRegisterOutgoing] is the hook that makes ordering checkable — a test can assert from
 * inside it that no INVITE has been sent yet, which is the whole of Task 35's Telecom
 * requirement and is invisible from the outside once both calls have returned.
 *
 * Not thread-safe, deliberately: tests drive it from one coroutine, and locking would hide
 * ordering bugs rather than expose them.
 */
class FakePlatformCallRegistry(
    /** What the platform answers for an outgoing call. False stands for a cellular call. */
    var permitOutgoing: Boolean = true,

    /** What the platform answers for an inbound INVITE. */
    var permitIncoming: Boolean = true,

    /** Routes the platform will accept. A request outside this set is refused. */
    var availableRoutes: Set<AudioRoute> = AudioRoute.entries.toSet(),
) : PlatformCallRegistry {

    val registeredOutgoing: MutableList<CallSnapshot> = mutableListOf()
    val registeredIncoming: MutableList<IncomingCall> = mutableListOf()
    val connected: MutableList<CallId> = mutableListOf()
    val ended: MutableList<Pair<CallId, HangupReason>> = mutableListOf()
    val requestedRoutes: MutableList<Pair<CallId, AudioRoute>> = mutableListOf()

    /** Runs while the platform is being asked, before the answer is given. */
    var onRegisterOutgoing: (CallSnapshot) -> Unit = {}

    override suspend fun registerOutgoing(call: CallSnapshot): Boolean {
        onRegisterOutgoing(call)
        registeredOutgoing += call
        return permitOutgoing
    }

    override suspend fun registerIncoming(call: IncomingCall): Boolean {
        registeredIncoming += call
        return permitIncoming
    }

    override fun onConnected(callId: CallId) {
        connected += callId
    }

    override fun onEnded(callId: CallId, reason: HangupReason) {
        ended += callId to reason
    }

    override suspend fun requestAudioRoute(callId: CallId, route: AudioRoute): Boolean {
        requestedRoutes += callId to route
        return route in availableRoutes
    }
}
