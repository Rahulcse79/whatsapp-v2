package com.whatsappv2.data.sip.network.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Where an [AlarmWakeTimer] alarm lands.
 *
 * Declared in the manifest, not registered at runtime, so the alarm's intent is explicit
 * and the platform delivers it whether or not the process is alive — a process the
 * platform ended is started for it, which brings the registration restore with it. When
 * the process is alive the timer is a singleton and knows which action the key names.
 *
 * Nothing is done here beyond the hand-off: `onReceive` runs on the main thread with the
 * platform's own wake lock held only for its duration, and the timer takes its own for the
 * work that follows.
 */
@AndroidEntryPoint
internal class WakeTimerReceiver : BroadcastReceiver() {

    @Inject
    lateinit var timer: AlarmWakeTimer

    override fun onReceive(context: Context, intent: Intent) {
        // No super.onReceive: Hilt's plugin rewrites this class to extend a generated
        // base and inserts the injecting call at the top of this method. See
        // CallActionReceiver in :app for the same note at length.
        if (intent.action != WakeTimerIntent.ACTION) return
        val key = WakeTimerIntent.keyOf(intent) ?: return
        timer.fire(key)
    }
}
