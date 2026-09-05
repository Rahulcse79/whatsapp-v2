package com.whatsappv2.service

import android.content.Context
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.di.ApplicationScope
import com.whatsappv2.domain.engine.SipCallController
import com.whatsappv2.domain.engine.SipRegistrar
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts the foreground service when there is something to hold open.
 *
 * ## The half that was missing
 *
 * [RegistrationService] has always known when to **stop** — [ServiceRunPolicy] decides and
 * the service applies it. Nothing knew when to start it, so it never ran: an account could
 * register, a call could arrive, and no notification would appear because there was no
 * service to post one. Tasks 37 and 38 both depend on the service being up, which is what
 * makes this the moment to close the gap rather than a tidy-up.
 *
 * The same policy decides both directions, so the service cannot be started for a reason
 * it would immediately stop for.
 *
 * ## Why starting twice is fine
 *
 * `startForegroundService` on a running service is another `onStartCommand`, which this
 * service answers with `START_NOT_STICKY` and nothing else. The alternative — tracking
 * whether it is running from out here — would be a second copy of state the service
 * already owns, and the two would disagree the first time the platform killed it.
 */
@Singleton
class ServiceLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registrar: SipRegistrar,
    private val calls: SipCallController,
    @ApplicationScope private val scope: CoroutineScope,
    private val logger: Logger,
) {

    fun start() {
        scope.launch {
            combine(registrar.registrationState, calls.activeCalls) { registrations, active ->
                ServiceRunPolicy.decide(registrations, active.size)
            }
                .distinctUntilChanged()
                .collect { decision ->
                    if (decision is ServiceDecision.Run) launchService()
                }
        }

        // An inbound INVITE, as an event rather than a state change. `incomingCalls` is a
        // buffered flow for exactly this case (§2.5): a call arriving while the app is
        // starting up must not wait for a state diff to be noticed, and the service is
        // what holds the process up long enough to answer it.
        scope.launch {
            calls.incomingCalls.collect {
                logger.info(TAG, "Inbound call; starting the service to hold the process up")
                launchService()
            }
        }
    }

    private fun launchService() {
        // Android 12+ refuses a background foreground-service start in many situations,
        // and it throws. Caught rather than allowed to crash: the app still registers and
        // still takes calls while it is in the foreground, which is more than a dead
        // process does.
        runCatching { RegistrationService.start(context) }
            .onFailure { logger.error(TAG, "Could not start the service: ${it.javaClass.simpleName}") }
    }

    private companion object {
        const val TAG = "ServiceLauncher"
    }
}
