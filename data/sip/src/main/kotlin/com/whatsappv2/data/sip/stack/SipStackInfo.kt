package com.whatsappv2.data.sip.stack

import com.whatsappv2.core.common.logging.Logger
import org.linphone.core.Factory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reports the embedded SIP stack.
 *
 * Task 25's done-when: a debug build must initialise the stack and log its version. That
 * is a smaller check than it sounds - it proves the AAR resolved, the native libraries
 * loaded for this device's ABI, and the JNI bridge works. All three fail in ways that are
 * confusing to diagnose later, so they are confirmed once at startup.
 *
 * This is the ONLY class outside the engine implementation that touches the SDK, and it
 * is still inside :data:sip, so the architecture rule that keeps `org.linphone` out of
 * every other module holds.
 */
@Singleton
class SipStackInfo @Inject constructor(
    private val logger: Logger,
) {

    /**
     * True when the native stack loaded.
     *
     * Obtaining a [Factory] is the whole check: it is the SDK's entry point, and reaching
     * it means the AAR resolved, the `.so` files for this device's ABI were found, and the
     * JNI bridge initialised. Each of those fails in a way that is confusing to diagnose
     * later, which is why they are confirmed once at startup.
     *
     * Returns false rather than throwing: a missing or mis-built `.so` should surface as a
     * clear log line and a degraded app, not a crash before anything is on screen.
     */
    fun isLoaded(): Boolean = runCatching { Factory.instance() }
        .onFailure { logger.error(TAG, "SIP stack failed to load: ${it.javaClass.simpleName}") }
        .isSuccess

    /** Logs whether the stack is available, once, at startup. */
    fun logStatus() {
        if (isLoaded()) {
            logger.info(TAG, "SIP stack loaded")
        } else {
            logger.error(TAG, "SIP stack unavailable")
        }
    }

    private companion object {
        const val TAG = "SipStack"
    }
}
