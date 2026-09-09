package com.whatsappv2.data.sip

import com.whatsappv2.core.common.logging.Logger
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Publishing onto the streams that cross the `SipEngine` seam — and reporting a refusal
 * instead of swallowing it.
 *
 * ## The defect this exists to remove
 *
 * `MutableSharedFlow.tryEmit` returns `false` — **without emitting** — when the buffer is
 * full and the flow's overflow policy is `SUSPEND`. Three call sites in `PjsipSipEngine`
 * discarded that boolean, so `endedCalls`, `transferEvents` and `videoRequests` dropped
 * silently while `docs/lld.md` promised none of them did
 * (`docs/reconciliation.md` A-5).
 *
 * The buffer is 64 and a phone will rarely fill it, which is exactly what makes this the
 * kind of defect that surfaces once, in the field, with nothing to reproduce it from. A
 * WARN naming the stream is what turns it into a diagnosable report.
 *
 * ## Why not suspend instead
 *
 * For `endedCalls` the emit site is `endCall`, which is not a suspend function and is
 * reached from three non-suspend paths. `incomingCalls` — the one stream where a loss is
 * unacceptable, because a dropped inbound call never rang and never reached the log — is
 * published with a real suspending `emit` from inside a coroutine, and does not use this.
 *
 * The per-stream capacity and policy table is `docs/data-structures.md` §1.2.
 */
internal fun <T> MutableSharedFlow<T>.emitOrReport(logger: Logger, value: T, stream: String) {
    if (!tryEmit(value)) {
        logger.warn(
            TAG,
            "$stream dropped an event: its buffer is full and the collector is not keeping " +
                "up. This is a loss, not a delay.",
        )
    }
}

private const val TAG = "SipSeam"
