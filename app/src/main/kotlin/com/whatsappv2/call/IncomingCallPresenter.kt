package com.whatsappv2.call

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.di.ApplicationScope
import com.whatsappv2.domain.engine.IncomingCall
import com.whatsappv2.domain.engine.SipCallController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts the incoming-call screen in front of a user who is already looking at the app.
 *
 * ## The gap this closes
 *
 * A ringing call is a notification (`CallNotifications.buildIncoming`), and that is the
 * right thing everywhere the app is *not* on screen: locked, its full-screen intent draws
 * the answer UI; in the background, the heads-up carries Answer and Decline. With one of
 * this app's own activities in front, neither happens — Android shows a foreground app a
 * heads-up at most, and on the TC15 it showed nothing at all: the call rang for 37 s
 * behind the call-history list, answerable only from the shade, while the same call with
 * `CallActivity` already open was offered as call waiting at once (2026-09-11, 12:56).
 * A dialler that is open when a call arrives is expected to show the call.
 *
 * ## Only while an activity is resumed
 *
 * The count of resumed activities is the whole test, and it is kept here rather than
 * read from the platform, because "is my app in the foreground" has no API and the
 * lifecycle callbacks are the one signal that is exact. Zero means the notification path
 * is the right one and this does nothing — starting an activity from the background is
 * refused on Android 10+ anyway, which is what the full-screen intent exists for.
 */
@Singleton
class IncomingCallPresenter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calls: SipCallController,
    @ApplicationScope private val scope: CoroutineScope,
    private val logger: Logger,
) : Application.ActivityLifecycleCallbacks {

    private var resumedActivities = 0

    fun start(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
        // Main: the count is written by the lifecycle callbacks on the main thread, and
        // `startActivity` belongs there too.
        scope.launch(Dispatchers.Main.immediate) {
            calls.incomingCalls.collect { present(it) }
        }
    }

    private fun present(call: IncomingCall) {
        if (resumedActivities == 0) return
        logger.info(TAG, "Inbound call with the app in front; showing the call screen")
        runCatching { context.startActivity(CallActivity.intentFor(context, call.callId)) }
            .onFailure { logger.warn(TAG, "Could not show the call screen: ${it.javaClass.simpleName}") }
    }

    override fun onActivityResumed(activity: Activity) {
        resumedActivities++
    }

    override fun onActivityPaused(activity: Activity) {
        resumedActivities = (resumedActivities - 1).coerceAtLeast(0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private companion object {
        const val TAG = "IncomingCallPresenter"
    }
}
