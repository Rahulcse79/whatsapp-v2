package com.whatsappv2.data.sip.network.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.data.sip.network.WakeTimer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/**
 * [WakeTimer] on `AlarmManager`, which is the only timer Android keeps while the CPU
 * sleeps.
 *
 * **The only class in the project that touches `AlarmManager` for the SIP stack.** It
 * sits in the platform package beside [ConnectivityNetworkMonitor] for the same reason:
 * it needs a device to mean anything, and the rules it fires are asserted on the JVM
 * through the [WakeTimer] seam with the coroutine implementation.
 *
 * ## Elapsed realtime, wakeup, while idle
 *
 * `ELAPSED_REALTIME_WAKEUP` is `CLOCK_BOOTTIME`: it counts through suspend, which is the
 * entire point (see [WakeTimer]). `WAKEUP` brings the CPU up to deliver it. And the
 * `AllowWhileIdle` variants are the ones doze honours — a plain alarm is deferred to the
 * next maintenance window, which on a handset left on a desk overnight is hours away.
 *
 * Exact when the platform lets it be: on Android 12+ that needs `SCHEDULE_EXACT_ALARM`
 * (declared, user-grantable) or an exemption — and an app the user has excluded from
 * battery optimisation is exempt, which this one is on the reference handset. When it is
 * not, the inexact variant is used and *said so in the log*, because the recovery
 * coordinator arms its keepalive at half the lease precisely so an inexact alarm cannot
 * land past it. Either way it fires through doze; the difference is a window of up to
 * three quarters of the delay.
 *
 * ## The receiver, and why it holds a wake lock
 *
 * The alarm arrives as a broadcast to [WakeTimerReceiver], declared in the manifest so
 * the intent is explicit and delivery does not depend on the process having registered
 * anything. The platform holds the CPU only for the length of `onReceive`, and the work
 * that matters — a REGISTER handed to the PJSIP thread, sent, and answered — happens
 * after `onReceive` returns. [fire] therefore takes a partial wake lock for
 * [WAKE_LOCK_MILLIS] before running the action: long enough for a REGISTER to be answered
 * or to time out, and released by the timeout rather than by anything that could forget
 * to.
 *
 * ## Keys
 *
 * `PendingIntent`s are matched by `Intent.filterEquals`, which ignores extras, so two
 * keys need two intents that *differ*: the key rides in the data URI as well as in an
 * extra. The action a key should run is held in this object, not in the intent — an alarm
 * that outlives the process (a restart, a crash) arrives with a key nobody is waiting on
 * and does nothing, which is correct: the restore that comes with the new process
 * re-registers everything anyway.
 */
@Singleton
internal class AlarmWakeTimer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) : WakeTimer {

    private val actions = ConcurrentHashMap<String, () -> Unit>()

    private val alarms: AlarmManager? get() = context.getSystemService(AlarmManager::class.java)

    override fun schedule(key: String, after: Duration, action: () -> Unit) {
        val manager = alarms ?: run {
            logger.error(TAG, "AlarmManager unavailable; $key will not fire")
            return
        }
        actions[key] = action
        val at = SystemClock.elapsedRealtime() + after.inWholeMilliseconds
        val pending = pendingIntent(key)

        if (canBeExact(manager)) {
            runCatching { manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending) }
                .onFailure {
                    // The permission check is the platform's, and it can say no after
                    // canScheduleExactAlarms said yes (revoked between the two). Inexact
                    // is late, not lost.
                    logger.warn(TAG, "Exact alarm refused for $key (${it.javaClass.simpleName}); using inexact")
                    manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
                }
        } else {
            logger.info(TAG, "Exact alarms not permitted; $key is inexact (may land up to 75% late)")
            manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
        }
        logger.debug(TAG, "Alarm $key in ${after.inWholeSeconds}s")
    }

    override fun cancel(key: String) {
        if (actions.remove(key) == null) return
        alarms?.cancel(pendingIntent(key))
    }

    /** The alarm for [key] went off. Called by [WakeTimerReceiver] on the main thread. */
    fun fire(key: String) {
        val action = actions.remove(key) ?: run {
            logger.debug(TAG, "Alarm $key fired with nothing waiting on it")
            return
        }
        holdCpu(key)
        logger.info(TAG, "Alarm $key fired")
        action()
    }

    private fun holdCpu(key: String) {
        val power = context.getSystemService(PowerManager::class.java) ?: return
        runCatching {
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$WAKE_LOCK_TAG:$key").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_MILLIS)
            }
        }.onFailure { logger.warn(TAG, "No wake lock for $key: ${it.message}") }
    }

    private fun canBeExact(manager: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()

    private fun pendingIntent(key: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            key.hashCode(),
            WakeTimerIntent.intentFor(context, key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private companion object {
        const val TAG = "SipWakeTimer"
        const val WAKE_LOCK_TAG = "whatsappv2:sip-wake"

        /**
         * How long the CPU is held after an alarm.
         *
         * A REGISTER on a LAN is answered in tens of milliseconds, and ten seconds covers
         * the first five retransmissions of one that was lost (RFC 3261 T1 doubling:
         * 0.5, 1, 2, 4 s). It is deliberately NOT PJSIP's full 32 s transaction timeout:
         * the keepalive fires every half-lease for as long as the account is logged in,
         * and holding the CPU for 32 of every 90 seconds would turn a sleeping handset
         * into a warm one. A REGISTER that is still unanswered when this lapses finishes
         * — as a 408 and a scheduled retry — the next time the device is awake for any
         * reason, which for an unreachable registrar costs nothing.
         */
        const val WAKE_LOCK_MILLIS = 10_000L
    }
}

/** The key an alarm carries. Shared by the timer and its receiver. */
internal object WakeTimerIntent {
    const val ACTION = "com.whatsappv2.data.sip.WAKE_TIMER"
    const val EXTRA_KEY = "key"
    const val SCHEME = "sip-wake-timer"

    fun intentFor(context: Context, key: String): Intent =
        Intent(ACTION)
            .setClass(context, WakeTimerReceiver::class.java)
            .setData(Uri.fromParts(SCHEME, key, null))
            .putExtra(EXTRA_KEY, key)

    fun keyOf(intent: Intent): String? = intent.getStringExtra(EXTRA_KEY) ?: intent.data?.schemeSpecificPart
}
