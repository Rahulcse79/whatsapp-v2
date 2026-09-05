package com.whatsappv2.telecom

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.domain.call.AudioRoute
import com.whatsappv2.domain.engine.CallSnapshot
import com.whatsappv2.domain.engine.IncomingCall
import com.whatsappv2.domain.engine.PlatformCallRegistry
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.SipUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Telecom, behind the engine's platform seam (Tasks 34 and 35, §3).
 *
 * ## What "register" means here
 *
 * Handing the call to `TelecomManager` and **waiting for the answer**. Telecom decides
 * asynchronously and through a different object than the one that asked — it calls back
 * into [SipConnectionService] — so this parks a waiter and suspends until the platform
 * either creates the connection or refuses it. That wait is the whole reason the engine
 * can promise a connection exists before the INVITE: without it, `placeCall` would return
 * while Telecom was still deciding and the INVITE would race the refusal.
 *
 * ## Refusals and absences are not the same thing
 *
 * - **Telecom refused** — a cellular call is in progress. §3 says honour it, so the call
 *   does not happen and nothing here looks for a way round it.
 * - **Telecom is not there at all** — no `TelecomManager`, or an OEM that would not accept
 *   the phone account. Refusing every call then would leave an app that cannot dial, which
 *   serves nobody. The call proceeds and the log says the platform is absent, which is the
 *   honest reading: nothing refused it, there was nothing to ask.
 *
 * `isOutgoingCallPermitted` is the platform's own answer to
 * [TelecomPolicy.mayPlaceCall]'s question — it is Telecom that knows about the cellular
 * call this app cannot see, which is why it is asked rather than guessed at.
 */
@Singleton
class TelecomCallRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val phoneAccount: SipPhoneAccount,
    private val logger: Logger,
) : PlatformCallRegistry {

    /**
     * The call this registry muted the device's microphone for, if any.
     *
     * Volatile because the two writers do not share a thread: mute arrives on a coroutine
     * from the engine, and the end of a call arrives on the SIP stack's own thread.
     */
    @Volatile
    private var mutedCall: CallId? = null

    override suspend fun registerOutgoing(call: CallSnapshot): Boolean {
        val telecom = telecomManager() ?: return permitWithoutPlatform()

        // Asked first because Telecom answers this one synchronously, and a "no" costs
        // nothing to find out before anything is handed over.
        if (!phoneAccount.outgoingCallPermitted()) {
            logger.info(TAG, "Telecom will not permit an outgoing call right now")
            return false
        }

        val waiter = SipConnectionService.expect(call.callId)
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccount.handle)
            // Nested: Telecom passes this inner bundle through to the ConnectionRequest,
            // while the outer one configures the placement itself.
            putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, callExtras(call.callId))
        }

        // Spelled out as a try/catch, not runCatching: `placeCall` names the revocable
        // CALL_PHONE alongside the MANAGE_OWN_CALLS this app declares, and lint reads only
        // the explicit form as handling the SecurityException a refusal arrives as.
        val handedOver = try {
            telecom.placeCall(call.remote.toTelecomUri(), extras)
            true
        } catch (e: SecurityException) {
            logger.error(TAG, "Telecom refused the placement: ${e.javaClass.simpleName}")
            false
        }

        return awaitDecision(call.callId, waiter, handedOver)
    }

    override suspend fun registerIncoming(call: IncomingCall): Boolean {
        val telecom = telecomManager() ?: return permitWithoutPlatform()

        val waiter = SipConnectionService.expect(call.callId)
        val extras = callExtras(call.callId).apply {
            putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, call.from.toTelecomUri())
        }

        val handedOver = runCatching { telecom.addNewIncomingCall(phoneAccount.handle, extras) }
            .onFailure { logger.error(TAG, "Telecom refused the inbound call: ${it.javaClass.simpleName}") }
            .isSuccess

        return awaitDecision(call.callId, waiter, handedOver)
    }

    override fun onConnected(callId: CallId) = SipConnectionService.reportActive(callId)

    /**
     * Keeps Telecom's own hold state in step (Task 41).
     *
     * The re-INVITE is the app's, or the far end's; Telecom sees neither. Without this the
     * lock screen and a car display offer a hold button for a call that is already held,
     * and pressing it asks the engine to hold a held call.
     */
    override fun onHoldChanged(callId: CallId, held: Boolean) =
        SipConnectionService.reportHeld(callId, held)

    /**
     * Mutes the device's microphone alongside the stack's own mute (Task 42).
     *
     * **Telecom has no public setter for this.** `Connection.setMuteState` is package
     * private and `requestCallEndpointChange` (API 34) covers routing only, so a
     * self-managed connection cannot report that it muted itself. What is left is the
     * platform microphone flag — the same one the system's mute control writes — which is
     * why setting it here is the app and the platform agreeing rather than two mutes.
     *
     * The mute is the call's, not the device's, so [onEnded] releases it. A microphone
     * left muted after the call that muted it is a device-wide mute with nothing on screen
     * to explain it, and the next app to record hears silence.
     */
    override fun setMuted(callId: CallId, muted: Boolean) {
        val audio = context.getSystemService(AudioManager::class.java) ?: run {
            logger.warn(TAG, "No AudioManager; the platform microphone was not muted")
            return
        }
        audio.isMicrophoneMute = muted
        mutedCall = callId.takeIf { muted }
    }

    override fun onEnded(callId: CallId, reason: HangupReason) {
        if (mutedCall == callId) setMuted(callId, muted = false)
        SipConnectionService.reportEnded(callId, reason)
    }

    override suspend fun requestAudioRoute(callId: CallId, route: AudioRoute): Boolean =
        SipConnectionService.requestAudioRoute(callId, TelecomPolicy.routeMaskOf(route))

    /**
     * Waits for Telecom to create or refuse the connection.
     *
     * A timeout counts as a refusal. The alternative — proceeding without a connection —
     * is a call the platform does not know about: no audio focus, no arbitration with the
     * cellular radio, and no way to end it from the lock screen. A call that visibly did
     * not start is better than one that half did.
     */
    private suspend fun awaitDecision(
        callId: CallId,
        waiter: CompletableDeferred<Boolean>,
        handedOver: Boolean,
    ): Boolean {
        if (!handedOver) {
            SipConnectionService.forget(callId)
            return false
        }

        val decision = withTimeoutOrNull(DECISION_TIMEOUT_MILLIS) { waiter.await() }
        if (decision == null) {
            logger.error(TAG, "Telecom did not answer for $callId; treating it as a refusal")
            SipConnectionService.forget(callId)
        }
        return decision == true
    }

    private fun telecomManager(): TelecomManager? =
        context.getSystemService(TelecomManager::class.java)

    private fun permitWithoutPlatform(): Boolean {
        logger.warn(TAG, "No Telecom on this device; the call proceeds unmanaged")
        return true
    }

    private fun callExtras(callId: CallId) = Bundle().apply {
        putString(SipConnectionService.EXTRA_CALL_ID, callId.value)
    }

    /**
     * The address Telecom shows, and hands back on the `ConnectionRequest`.
     *
     * `sip:`, which is the scheme the phone account declares — Telecom rejects a placement
     * whose scheme the account never claimed. The user part may be absent on a URI that
     * names only a host, and `sip:host` is still a valid thing to dial.
     */
    private fun SipUri.toTelecomUri(): Uri {
        val address = user?.let { "$it@${host.rendered}" } ?: host.rendered
        return Uri.fromParts(PhoneAccount.SCHEME_SIP, address, null)
    }

    private companion object {
        const val TAG = "TelecomCallRegistry"

        /**
         * How long to wait for Telecom to create or refuse a connection.
         *
         * Three seconds: long enough for a busy device to answer, short enough that a
         * platform which never answers does not leave the user holding a dead dialler.
         */
        const val DECISION_TIMEOUT_MILLIS = 3_000L
    }
}
