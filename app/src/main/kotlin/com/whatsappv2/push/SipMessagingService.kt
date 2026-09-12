package com.whatsappv2.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.core.common.result.errorOrNull
import com.whatsappv2.core.common.time.Clock
import com.whatsappv2.di.ApplicationScope
import com.whatsappv2.domain.engine.SipRegistrar
import com.whatsappv2.domain.repository.SipAccountRepository
import com.whatsappv2.domain.usecase.LoginUseCase
import com.whatsappv2.domain.usecase.RestoreRegistrationsUseCase
import com.whatsappv2.service.RegistrationService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The push wake path (Task 38, ADR-004, §2.5).
 *
 * ## Why this exists at all
 *
 * A registration alone does not survive Doze or process death, so on Android 12+ push is
 * the **primary** delivery path for an incoming call rather than a fallback. The sequence
 * ADR-004 specifies is: high-priority data message → wake → REGISTER → the gateway sees
 * the REGISTER and releases the call it was holding → the INVITE arrives on the restored
 * registration → the incoming UI (Task 37) shows it.
 *
 * ## What this class decides: nothing
 *
 * [PushPayload] parses, [PushWakePolicy] decides and [PushAccountResolver] names the
 * account; all three are pure and all three are tested. What is left here is starting a
 * service and asking the engine to register, which is the part that genuinely needs a
 * device.
 *
 * ## The REGISTER is never skipped
 *
 * The gateway does not send the INVITE until it sees a REGISTER that arrived after the
 * push. So whatever this process believes — registered, failed, never heard of the
 * account — a `WAKE` ends in a REGISTER; the only question [RegisterStep] answers is which
 * one. A process that trusted its own "Registered" and stayed quiet would leave the caller
 * listening to ringback until the gateway gave up.
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
    lateinit var accounts: SipAccountRepository

    @Inject
    lateinit var login: LoginUseCase

    @Inject
    lateinit var restoreRegistrations: RestoreRegistrationsUseCase

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

        when (PushWakePolicy.decide(payload, clock.nowEpochMillis())) {
            // `payload` is non-null on WAKE by construction; `decide` returns
            // IGNORE_MALFORMED for null, and this is the one place that knowledge is used.
            PushDecision.WAKE -> payload?.let(::wake)

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

    private fun wake(payload: PushPayload) {
        // The service first, and synchronously: it is what keeps the process alive long
        // enough for the REGISTER and the INVITE that follows it, and the platform's
        // permission to start a foreground service from a high-priority push is a window
        // measured in seconds. Registering into a process the platform may kill a second
        // later is how a woken call is still missed.
        RegistrationService.start(this)

        scope.launch {
            val target = PushAccountResolver.resolve(payload.accountId, accounts.observeAccounts().first())
            val step = PushWakePolicy.registerStep(target, target?.let { registrar.registrationState.value[it] })
            logger.info(TAG, "Push wake: $step")

            when (step) {
                RegisterStep.LOGIN -> login(checkNotNull(target)).errorOrNull()
                    ?.let { logger.warn(TAG, "Push wake could not log in: $it") }

                RegisterStep.REFRESH -> registrar.refreshRegistration(checkNotNull(target)).errorOrNull()
                    ?.let { logger.warn(TAG, "Push wake could not refresh the registration: $it") }

                RegisterStep.RESTORE_ALL -> restoreRegistrations().forEach { (id, error) ->
                    logger.warn(TAG, "Could not restore the registration of $id: $error")
                }
            }
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
