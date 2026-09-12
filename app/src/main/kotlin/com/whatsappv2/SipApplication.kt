package com.whatsappv2

import android.app.Application
import com.whatsappv2.audio.CallAudioCoordinator
import com.whatsappv2.call.IncomingCallPresenter
import com.whatsappv2.calllog.CallLogWriter
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.data.sip.SipEngineLifecycle
import com.whatsappv2.di.ApplicationScope
import com.whatsappv2.domain.usecase.RestoreRegistrationsUseCase
import com.whatsappv2.push.PushTokenPublisher
import com.whatsappv2.service.ServiceLauncher
import com.whatsappv2.telecom.SipPhoneAccount
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application entry point and Hilt's object-graph root.
 *
 * Named for what it is rather than for the repository: this is a SIP client, and the
 * class name is the first thing a new engineer reads.
 *
 * Deliberately thin. The foreground service belongs to the task that introduced it (28);
 * an Application class that starts services is how startup time and crash-on-launch
 * problems begin. What is started here is what has nowhere earlier to live: the SIP stack,
 * the Telecom account, the audio coordinator, and the push token — each of which must
 * exist before the first call, and none of which has a screen to belong to.
 *
 * ## Why the SIP stack in particular
 *
 * Because nothing else can. Registration is not a service: the engine has to be up before
 * `register` will do anything, the foreground service does not exist until an account has
 * already registered, and the account list is a screen the user may never open. That
 * leaves process start as the only point that is always before the first REGISTER.
 *
 * [SipEngineLifecycle.start] is failure-tolerant for the same reason the service's
 * foreground start is: an app that cannot bring up a native stack should still open and
 * report its accounts as offline, which is true, rather than refuse to launch.
 */
@HiltAndroidApp
class SipApplication : Application() {

    @Inject
    lateinit var logger: Logger

    @Inject
    lateinit var sipEngine: SipEngineLifecycle

    @Inject
    lateinit var phoneAccount: SipPhoneAccount

    @Inject
    lateinit var callAudio: CallAudioCoordinator

    @Inject
    lateinit var callLog: CallLogWriter

    @Inject
    lateinit var pushTokens: PushTokenPublisher

    @Inject
    lateinit var services: ServiceLauncher

    @Inject
    lateinit var incomingCalls: IncomingCallPresenter

    @Inject
    lateinit var restoreRegistrations: RestoreRegistrationsUseCase

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        logger.info(TAG, "Application started")
        sipEngine.start()
        // Registered at start, not before the first call: Telecom will not accept a
        // connection for an account it has never heard of, and the first call is exactly
        // when there is no time to find that out.
        phoneAccount.register()
        // Holds nothing until a call exists - no focus, no sensor, no listener - so
        // starting it here costs a coroutine and buys audio that follows every call,
        // including the ones answered from a lock screen this process never drew (Task 40).
        callAudio.start()
        // Started here rather than with the history screen: a call that ends while the
        // user is elsewhere - or with nothing on screen at all - is still a call to
        // record, and those are the missed ones (Task 47).
        callLog.start()
        // Nothing started the foreground service until now, so it never ran: an account
        // could register and a call could arrive with no service to post a notification
        // from. The same ServiceRunPolicy that stops it decides when to start it.
        services.start()
        // A call arriving while the app is on screen is shown, not merely notified: the
        // notification's full-screen intent only fires on a locked device, and a heads-up
        // over our own activity is not something every device draws (TC15, 2026-09-11).
        incomingCalls.start(this)
        // The token reaches the registrar on the next REGISTER's Contact header. A no-op
        // on a build with no Firebase configuration, which it logs rather than hides
        // (ADR-004, Task 38).
        pushTokens.publishCurrentToken()
        // Every account the user logged in and never logged out, registered again — the
        // process died, the intent did not. Before this, every process death left every
        // account Offline until somebody opened the app and pressed Log in, with no call
        // able to arrive in between. After the engine and the service launcher, so the
        // registration has a stack to go to and a service to be kept alive by.
        scope.launch {
            restoreRegistrations().forEach { (id, error) ->
                logger.warn(TAG, "Could not restore the registration of $id: $error")
            }
        }
    }

    private companion object {
        const val TAG = "SipApplication"
    }
}
