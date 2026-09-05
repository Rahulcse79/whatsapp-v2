package com.whatsappv2

import android.app.Application
import com.whatsappv2.audio.CallAudioCoordinator
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.data.sip.SipEngineLifecycle
import com.whatsappv2.push.PushTokenPublisher
import com.whatsappv2.service.ServiceLauncher
import com.whatsappv2.telecom.SipPhoneAccount
import dagger.hilt.android.HiltAndroidApp
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
    lateinit var pushTokens: PushTokenPublisher

    @Inject
    lateinit var services: ServiceLauncher

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
        // Nothing started the foreground service until now, so it never ran: an account
        // could register and a call could arrive with no service to post a notification
        // from. The same ServiceRunPolicy that stops it decides when to start it.
        services.start()
        // The token reaches the registrar on the next REGISTER's Contact header. A no-op
        // on a build with no Firebase configuration, which it logs rather than hides
        // (ADR-004, Task 38).
        pushTokens.publishCurrentToken()
    }

    private companion object {
        const val TAG = "SipApplication"
    }
}
