package com.whatsappv2.data.sip.network.platform

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.data.sip.network.DeviceWakeMonitor
import com.whatsappv2.data.sip.network.WakeReason
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The platform's "the device is in use again" signals, reduced to [WakeReason].
 *
 * Four sources, all of them the platform's own and none of them needing a permission:
 *
 * - `ACTION_SCREEN_ON` and `ACTION_USER_PRESENT`, which can only be received by a
 *   receiver registered at runtime — the manifest form has been refused since Android 8.
 * - `PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED`, read as a wake only when the device
 *   is leaving idle: the platform saying "you may use the network again" is the best
 *   possible moment to find out whether the lease survived.
 * - `Application.ActivityLifecycleCallbacks`, counting started activities, so the app
 *   coming to the front is a wake without a dependency on `lifecycle-process`.
 *
 * Emitted, not decided: what to do about a wake — and how often — is the recovery
 * coordinator's rule, where it is tested. Every emission from here would be a REGISTER
 * without that throttle, and a screen toggled on and off is exactly the case it exists
 * for.
 *
 * Registered only while collected, like [ConnectivityNetworkMonitor]: the receiver lives
 * for the life of the stack and no longer.
 */
@Singleton
internal class BroadcastWakeMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) : DeviceWakeMonitor {

    override val wakes: Flow<WakeReason> = callbackFlow {
        val power = context.getSystemService(PowerManager::class.java)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> trySend(WakeReason.SCREEN_ON)
                    Intent.ACTION_USER_PRESENT -> trySend(WakeReason.USER_PRESENT)
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED ->
                        // Entering idle is not a wake. Leaving it is.
                        if (power?.isDeviceIdleMode == false) trySend(WakeReason.DOZE_EXIT)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        // All three are protected system broadcasts, so nothing but the platform can
        // send them; NOT_EXPORTED is the honest declaration and the one Android 14
        // requires to be made at all. The flag exists from 33; below that the two-argument
        // form is the only one there is.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        val application = context.applicationContext as? Application
        val lifecycle = object : Application.ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                // Zero to one is the app coming to the front. One to two is a screen
                // change inside an app that was already there.
                if (started++ == 0) trySend(WakeReason.APP_FOREGROUND)
            }

            override fun onActivityStopped(activity: Activity) {
                if (started > 0) started--
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        application?.registerActivityLifecycleCallbacks(lifecycle)
            ?: logger.warn(TAG, "No Application; the app coming to the front is not a wake")

        awaitClose {
            context.unregisterReceiver(receiver)
            application?.unregisterActivityLifecycleCallbacks(lifecycle)
        }
    }

    private companion object {
        const val TAG = "SipWakeMonitor"
    }
}
