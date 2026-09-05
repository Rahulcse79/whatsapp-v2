package com.whatsappv2.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.core.common.time.Clock
import com.whatsappv2.di.ApplicationScope
import com.whatsappv2.domain.engine.SipRegistrar
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.usecase.LoginUseCase
import com.whatsappv2.service.RegistrationService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The push wake path (Task 38, ADR-004, §2.5).
 *
 * ## Why this exists at all
 *
 * A registration alone does not survive Doze or process death, so on Android 12+ push is
 * the **primary** delivery path for an incoming call rather than a fallback. The sequence
 * ADR-004 specifies is: high-priority data message → wake → re-register if the binding is
 * stale → hold the process up with the foreground service → the INVITE arrives on the
 * restored registration → the incoming UI (Task 37) shows it.
 *
 * ## What this class decides: nothing
 *
 * [PushPayload] parses and [PushWakePolicy] decides; both are pure and both are tested.
 * What is left here is starting a service and asking the engine to register, which is the
 * part that genuinely needs a device.
 *
 * ## What it does not do
 *
 * It does not show a call. The push says only "wake up and re-register" — the caller's
 * identity arrives in the INVITE over the secured signalling channel, never in a push
 * payload (§7, DoD 12). A notification built from push data would be a notification built
 * from something anyone who can reach the gateway can set.
 */
@AndroidEntryPoint
class SipMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var registrar: SipRegistrar

    @Inject
    lateinit var login: LoginUseCase

    @Inject
    lateinit var tokens: PushTokenPublisher

    @Inject
    lateinit var clock: Clock

    @Inject
    lateinit var logger: Logger

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onMessageReceived(message: RemoteMessage) {
        val payload = PushPayload.from(message.data)
        val accountId = payload?.accountId?.let(::AccountId)
        val registration = accountId?.let { registrar.registrationState.value[it] }

        when (PushWakePolicy.decide(payload, registration, clock.nowEpochMillis())) {
            PushDecision.WAKE_AND_REGISTER -> {
                // The service first: it is what keeps the process alive long enough for
                // the REGISTER and the INVITE that follows it. Registering into a process
                // the platform may kill a second later is how a woken call is still missed.
                RegistrationService.start(this)
                accountId?.let { id -> scope.launch { login(id) } }
            }

            PushDecision.WAKE_ONLY -> RegistrationService.start(this)

            // Logged at info because each is a normal thing to see in the field: a push
            // that lost a race, a type this version does not act on, a malformed message
            // from a gateway being changed.
            PushDecision.IGNORE_STALE ->
                logger.info(TAG, "Ignoring a push for a call that can no longer be ringing")

            PushDecision.IGNORE_UNSUPPORTED ->
                logger.info(TAG, "Ignoring a push of type ${payload?.type}")

            PushDecision.IGNORE_MALFORMED ->
                logger.warn(TAG, "Ignoring a push that does not match the ADR-004 contract")
        }
    }

    /**
     * The token rotated.
     *
     * Publishing it re-registers with the new `pn-prid`, which is Task 38's third
     * done-when: a registrar still holding the old token wakes a device that no longer
     * has this app on it.
     *
     * Deprecated in firebase-messaging 25.1.2 in favour of `onRegistered`, which pairs
     * with `FirebaseMessaging.register()`. Moving to it changes where the token comes
     * from rather than what it is called, so it belongs to a task that can revisit
     * ADR-004's wake path with it. Marked rather than silently suppressed, so the
     * obligation travels with the code — the same way `SipConnection` carries the
     * platform's own deprecations.
     */
    @Deprecated("firebase-messaging 25.1.2 replaces this with onRegistered; see ADR-004")
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onNewToken(token: String) {
        logger.info(TAG, "Push token rotated")
        tokens.publish(token)
    }

    private companion object {
        const val TAG = "SipMessagingService"
    }
}
