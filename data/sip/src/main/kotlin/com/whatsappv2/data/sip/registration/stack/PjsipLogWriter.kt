package com.whatsappv2.data.sip.registration.stack

import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.data.sip.registration.SipTraceRedaction
import org.pjsip.pjsua2.LogEntry
import org.pjsip.pjsua2.LogWriter

/**
 * Routes PJSIP's own log — including the SIP message trace — into the app's logger.
 *
 * ## Why this exists
 *
 * Without it, `EpConfig` carries no `logConfig.writer`, and everything pjsua2 has to say
 * goes to its default sink, which on Android is nowhere. The consequence is not
 * theoretical: an inbound call rang, Telecom cancelled it three seconds later, and the
 * entire app-side record of that call was **zero log lines**. No INVITE, no response code,
 * no SDP, nothing to distinguish "the far end hung up" from "we answered 488".
 *
 * A SIP client whose SIP is invisible cannot be debugged, and every call failure becomes a
 * guess. This is the fix for that.
 *
 * ## It is a SWIG director, so it must be held
 *
 * `LogWriter` has a native peer that keeps a pointer back to this object. If Kotlin
 * collects it while PJSIP still holds that pointer, the next log line dereferences freed
 * memory and takes the process down — and since the writer is called from PJSIP's own
 * threads, that crash lands nowhere near the cause. The official sample keeps its writer
 * in a field with the comment *"Maintain reference to avoid auto garbage collecting"*;
 * [RealPjsipCoreGateway] does the same, for the same reason it holds every `PjCall`.
 *
 * ## Credentials never reach the log
 *
 * A REGISTER carries the digest response computed from the account password.
 * [SipTraceRedaction] removes it, and the server's challenge is deliberately left intact —
 * see that class for the distinction.
 *
 * ## Levels
 *
 * PJSIP's are 1 error, 2 warning, 3 info, 4 and above trace. They are mapped onto the
 * app's own facade rather than flattened, so a PJSIP error is an error in logcat and the
 * message trace stays at debug — which the release logger compiles away to an empty body.
 */
internal class PjsipLogWriter(
    private val logger: Logger,
    /**
     * Whether the trace is switched on, asked afresh for every line.
     *
     * A lambda rather than a value: pjsua2 reads `logConfig` once at `libInit`, so a
     * writer built with the setting frozen in could only change with a stack restart -
     * and dropping a call to turn a diagnostic on is not a trade worth making. Asked per
     * line, the switch in Settings takes effect on the next message.
     */
    private val enabled: () -> Boolean,
) : LogWriter() {

    override fun write(entry: LogEntry) {
        // Before anything is formatted or redacted. PJSIP has already built the string by
        // the time it reaches here, but the redaction pass and the logcat write are ours
        // and are worth skipping when nobody asked for a trace.
        if (!enabled()) return

        // Defensive: this runs on a PJSIP thread, and an exception thrown back across the
        // JNI boundary into C++ is undefined behaviour rather than a stack trace. A
        // logging call is never worth taking the process down for.
        runCatching {
            val message = SipTraceRedaction.redact(entry.msg.orEmpty()).trimEnd()
            if (message.isEmpty()) return

            when (entry.level) {
                LEVEL_ERROR -> logger.error(TAG, message)
                LEVEL_WARNING -> logger.warn(TAG, message)
                LEVEL_INFO -> logger.info(TAG, message)
                else -> logger.debug(TAG, message)
            }
        }
    }

    internal companion object {
        const val TAG = "PjsipTrace"

        private const val LEVEL_ERROR = 1
        private const val LEVEL_WARNING = 2
        private const val LEVEL_INFO = 3
    }
}
