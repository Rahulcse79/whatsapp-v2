package com.whatsappv2.data.sip.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * A timer that keeps counting while the device sleeps.
 *
 * ## The clock the recovery loop was actually running on
 *
 * Every timer the registration path used to have was a coroutine `delay`, and on Android
 * that is `System.nanoTime()` — `CLOCK_MONOTONIC`, which **stops while the CPU is
 * suspended**. So does PJSIP's own timer heap (`pj_gettickcount`), which is what schedules
 * its re-REGISTER at `expiry - 5 s`. A handset in deep sleep spends almost all of its time
 * suspended, and both timers advance only during the few seconds an hour the kernel wakes
 * it for something else.
 *
 * What that produced, measured on a Galaxy M14 on 2026-09-21: after ~2.5 h idle, with
 * Wi-Fi up and the IP unchanged and the process alive, the extension was gone from the
 * registrar's `sofia status profile internal reg`, the screen said "Reconnecting…", and it
 * stayed that way until the app was force-stopped. The registration had expired
 * server-side (180 s, and nothing refreshed it), the next attempt had failed, and the
 * retry after that was sitting in a `delay` that had barely started.
 *
 * The only thing that fires on wall-clock time through suspend is an `AlarmManager` alarm
 * with `ELAPSED_REALTIME_WAKEUP` — `CLOCK_BOOTTIME`, which counts sleep — and that is what
 * the platform implementation is. This interface is the seam so the coordinator's rules
 * stay testable with virtual time on the JVM.
 *
 * ## Contract
 *
 * One timer per [key]. Scheduling a key that is already scheduled replaces it — there is
 * never a stale timer and a fresh one both waiting under the same name. Firing runs
 * [action] on an unspecified thread and drops the key; a timer that wants to run again
 * schedules itself again. [cancel] of a key that is not scheduled is a no-op.
 */
internal interface WakeTimer {

    fun schedule(key: String, after: Duration, action: () -> Unit)

    fun cancel(key: String)

    companion object {
        /**
         * A timer that accepts everything and fires nothing.
         *
         * The engine's default for the JVM. Not [CoroutineWakeTimer], and deliberately:
         * the keepalive re-arms itself every time it fires, which under a test scope's
         * `advanceUntilIdle` is a timer that is never idle — virtual time runs to the end
         * of the universe and the test with it. The engine's tests do not exercise
         * retries or keepalives; the coordinator's do, with the coroutine timer and
         * `advanceTimeBy`, where a self-arming timer is exactly what is wanted.
         */
        val NONE: WakeTimer = object : WakeTimer {
            override fun schedule(key: String, after: Duration, action: () -> Unit) = Unit
            override fun cancel(key: String) = Unit
        }
    }
}

/**
 * [WakeTimer] on a coroutine `delay`.
 *
 * **Not what the app binds**, for the reason in the interface's documentation: it stalls
 * in deep sleep. It exists for the JVM, where a `TestScope` makes it exact and lets every
 * recovery rule be asserted against virtual time — and it is the default the coordinator
 * constructs when nothing is injected, so the existing tests are unchanged.
 */
internal class CoroutineWakeTimer(private val scope: CoroutineScope) : WakeTimer {

    private val jobs = mutableMapOf<String, Job>()

    override fun schedule(key: String, after: Duration, action: () -> Unit) {
        jobs.remove(key)?.cancel()
        jobs[key] = scope.launch {
            delay(after)
            // Dropped before the action, not after: the action may schedule the same
            // key again, and removing afterwards would cancel what it just scheduled.
            jobs.remove(key)
            action()
        }
    }

    override fun cancel(key: String) {
        jobs.remove(key)?.cancel()
    }
}
