package com.whatsappv2.domain.engine

import com.whatsappv2.domain.call.AudioRoute
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.HangupReason

/**
 * The platform's own call registry — Telecom, on Android (Task 35, §3).
 *
 * ## Why the engine talks to this at all
 *
 * Because the platform knows something the SIP stack cannot: whether the user is already
 * on a cellular call. §3 requires that call to be honoured rather than talked over, and
 * the only honest way to honour it is to ask before the INVITE goes out and to accept a
 * refusal. Hand-rolled call notifications are a **rejected design** for exactly this
 * reason — an app that does not register its calls with Telecom cannot know what else the
 * device is doing.
 *
 * ## Why it is an interface in `:domain`
 *
 * `android.telecom` is an Android package, and `:data:sip` may not decide platform policy
 * any more than `:domain` may import Android. So the engine depends on this contract and
 * `:app` implements it, the same shape as [com.whatsappv2.domain.repository.SipAccountRepository].
 * The consequence that matters for testing: a JVM test substitutes a fake and asserts the
 * ordering — connection first, INVITE second — with no device in the room.
 *
 * Every method is safe to call for a call the platform has never heard of; the
 * implementation drops it rather than throwing. The engine is the source of truth for a
 * call's state and this is a mirror of it, so a mirror that has already been cleared is
 * not an error.
 */
interface PlatformCallRegistry {

    /**
     * Registers an outgoing call **before** its INVITE.
     *
     * @return false when the platform refused — almost always a cellular call in
     *   progress. A refusal is final: the caller must not place the call anyway.
     */
    suspend fun registerOutgoing(call: CallSnapshot): Boolean

    /**
     * Registers an inbound INVITE before anything rings.
     *
     * @return false when the platform refused, in which case the call must be rejected
     *   rather than shown. Forcing our own full-screen UI over a cellular call is the
     *   behaviour §3 rejects.
     */
    suspend fun registerIncoming(call: IncomingCall): Boolean

    /** The call is answered and media is flowing. */
    fun onConnected(callId: CallId)

    /**
     * The call ended for a reason the platform did not cause.
     *
     * A remote hangup, a 486, a transport failure. Without this the platform holds audio
     * focus for a call that is over.
     */
    fun onEnded(callId: CallId, reason: HangupReason)

    /**
     * Asks the platform to move call audio to [route].
     *
     * The platform owns routing — it arbitrates between apps, and it is what a Bluetooth
     * headset's own buttons talk to. Asking rather than setting is why this returns a
     * boolean: a [AudioRoute.BLUETOOTH] request with no headset connected must fail
     * rather than silently play on the earpiece (§5.2).
     */
    suspend fun requestAudioRoute(callId: CallId, route: AudioRoute): Boolean
}

/**
 * The registry for a platform that has none.
 *
 * Permits everything and remembers nothing. Two uses, and both are honest: a JVM test
 * that is not exercising the platform at all, and a device whose Telecom refused the
 * phone account — where the alternative to permitting the call is an app that cannot dial
 * at all.
 */
object UnmanagedCallRegistry : PlatformCallRegistry {
    override suspend fun registerOutgoing(call: CallSnapshot): Boolean = true
    override suspend fun registerIncoming(call: IncomingCall): Boolean = true
    override fun onConnected(callId: CallId) = Unit
    override fun onEnded(callId: CallId, reason: HangupReason) = Unit
    override suspend fun requestAudioRoute(callId: CallId, route: AudioRoute): Boolean = false
}
