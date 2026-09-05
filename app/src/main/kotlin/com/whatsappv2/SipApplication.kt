package com.whatsappv2

import android.app.Application
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.data.sip.SipEngineLifecycle
import com.whatsappv2.telecom.SipPhoneAccount
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point and Hilt's object-graph root.
 *
 * Named for what it is rather than for the repository: this is a SIP client, and the
 * class name is the first thing a new engineer reads.
 *
 * Deliberately thin. The foreground service and Telecom wiring belong to the tasks that
 * introduce them (28, 34); an Application class that starts services is how startup time
 * and crash-on-launch problems begin.
 *
 * ## The one thing it does start
 *
 * The SIP stack, and only because nothing else can. Registration is not a service: the
 * engine has to be up before `register` will do anything, the foreground service does not
 * exist until an account has already registered, and the account list is a screen the user
 * may never open. That leaves process start as the only point that is always before the
 * first REGISTER.
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

    override fun onCreate() {
        super.onCreate()
        logger.info(TAG, "Application started")
        sipEngine.start()
        // Registered at start, not before the first call: Telecom will not accept a
        // connection for an account it has never heard of, and the first call is exactly
        // when there is no time to find that out.
        phoneAccount.register()
    }

    private companion object {
        const val TAG = "SipApplication"
    }
}
