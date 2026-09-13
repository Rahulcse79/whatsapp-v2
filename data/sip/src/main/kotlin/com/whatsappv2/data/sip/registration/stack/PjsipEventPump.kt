package com.whatsappv2.data.sip.registration.stack

import com.whatsappv2.core.common.logging.Logger
import org.pjsip.pjsua2.Endpoint
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * pjsua2's worker loop, run on the gateway's executor so that callbacks land there too.
 *
 * With `uaConfig.threadCnt = 0` the library starts no thread of its own and nothing
 * happens until something calls `libHandleEvents()`: no REGISTER goes out, no INVITE
 * comes in, no timer fires. With `mainThreadOnly = true` as well, every callback — call,
 * account, log writer, media event — is delivered from inside that call, on the thread
 * that made it. This is that call, in a loop, on the one thread that is allowed to touch
 * PJSIP. `RealPjsipCoreGateway`'s class documentation has the crash this arrangement
 * closes.
 *
 * ## One task, many polls
 *
 * The loop runs *inside* one executor task and only hands the thread back when the
 * executor has something due. Between polls it looks at the head of the executor's queue;
 * a task that is due — posted by the engine, or a delayed one whose time has come — makes
 * the loop re-post itself at the *back* of the queue and return, so that task runs next
 * and the loop resumes after it. A task posted from inside a callback therefore runs
 * after the poll that delivered the callback has returned — which is after the native
 * frame that raised it is gone. That is what makes releasing a SWIG director from its
 * own callback safe.
 *
 * It was one task *per* poll at first, re-posted through the executor every 10 ms. That
 * cost 587 µs of CPU per poll on a TC15 — 5.5 % of a core, all day — against 45 µs for
 * pjmedia's equivalent native loop, because a debuggable app runs the boot image's
 * `java.util.concurrent` in the interpreter and a `ScheduledFutureTask` round trip is a
 * lot of interpreted code. A queue peek is not: the same loop in one task measured
 * 275 µs, the rest of which is the interpreter running the peek's lock and the JNI
 * bridge, and is what a release build compiles away.
 *
 * ## Fifty milliseconds, not pjsua's ten
 *
 * The timeout bounds one thing only: how long an operation posted to the executor waits
 * behind an *idle* poll. It is not the timer resolution — `pjsua_handle_events` shortens
 * its own wait to the next due timer, so retransmissions, refreshes and keep-alives fire
 * when they should at any bound — and it is not the I/O latency, because a packet wakes
 * the poll at once. What it buys is five times fewer wakeups on a registered, idle phone,
 * which is the state a phone is in for most of the day. Fifty milliseconds under a tap
 * that already crosses Telecom is not something a person can see.
 *
 * The loop is bound to the endpoint it was started for: [current] is asked before every
 * poll, and a pump left over from a stopped endpoint sees a different answer and ends.
 * Without that, a stop and a start posted back to back left two loops polling one library.
 *
 * The poll itself is pjsua's own `worker_thread`: `pjsua_handle_events(...)` until told
 * to stop, and on a negative return — an error, not "nothing happened" — wait the same
 * interval rather than spin.
 */
internal class PjsipEventPump(
    private val executor: ScheduledThreadPoolExecutor,
    private val logger: Logger,
    /** The endpoint the gateway currently holds, or null once it has been stopped. */
    private val current: () -> Endpoint?,
) {

    /** Begins polling [endpoint]. Call once per `libStart`, on the executor. */
    fun start(endpoint: Endpoint) {
        executor.execute { poll(endpoint) }
    }

    private fun poll(running: Endpoint) {
        while (current() === running) {
            val handled = runCatching { running.libHandleEvents(POLL_MILLIS) }
                .getOrElse { failure ->
                    logger.error(TAG, "libHandleEvents failed: ${failure.message}", failure)
                    -1
                }
            if (handled < 0) {
                executor.schedule({ poll(running) }, POLL_MILLIS, TimeUnit.MILLISECONDS)
                return
            }
            if (executor.hasDueWork()) {
                executor.execute { poll(running) }
                return
            }
        }
    }

    /**
     * Whether a task is waiting to run now. The queue is ordered by due time, so only its
     * head can be; a delayed task whose time has not come is not a reason to yield.
     */
    private fun ScheduledThreadPoolExecutor.hasDueWork(): Boolean {
        val head = queue.peek() as? Delayed ?: return false
        return head.getDelay(TimeUnit.NANOSECONDS) <= 0
    }

    internal companion object {
        const val TAG = RealPjsipCoreGateway.TAG

        /**
         * How long one poll may block waiting for the stack, and so the most a posted
         * operation waits behind an idle one. See the class documentation for why this is
         * not pjsua's own 10.
         */
        const val POLL_MILLIS = 50L
    }
}
