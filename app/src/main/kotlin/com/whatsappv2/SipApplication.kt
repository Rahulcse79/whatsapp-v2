package com.whatsappv2

import android.app.Application
import com.whatsappv2.core.common.logging.Logger
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point and Hilt's object-graph root.
 *
 * Named for what it is rather than for the repository: this is a SIP client, and the
 * class name is the first thing a new engineer reads.
 *
 * Deliberately thin. Registration, the foreground service and Telecom wiring belong to
 * the tasks that introduce them (28, 34); an Application class that starts services is
 * how startup time and crash-on-launch problems begin.
 */
@HiltAndroidApp
class SipApplication : Application() {

    @Inject
    lateinit var logger: Logger

    override fun onCreate() {
        super.onCreate()
        logger.info(TAG, "Application started")
    }

    private companion object {
        const val TAG = "SipApplication"
    }
}
