package com.whatsappv2.data.sip

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
     * The stack version, or null if the native libraries could not be loaded.
     *
     * Returns null rather than throwing: a missing or mis-built `.so` should surface as a
     * clear log line and a degraded app, not a crash on launch before anything is on
     * screen.
     */
    fun version(): String? = runCatching { Factory.instance().version }
        .onFailure { logger.error(TAG, "SIP stack failed to load: ${it.javaClass.simpleName}") }
        .getOrNull()

    /** Logs the stack version once, at startup. */
    fun logVersion() {
        when (val version = version()) {
            null -> logger.error(TAG, "SIP stack unavailable")
            else -> logger.info(TAG, "SIP stack $version")
        }
    }

    private companion object {
        const val TAG = "SipStack"
    }
}
