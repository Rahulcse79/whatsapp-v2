package com.whatsappv2.data.sip.stack

import com.whatsappv2.core.common.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the native SIP stack is present and usable (ADR-006).
 *
 * ## What "loaded" means for PJSIP
 *
 * PJSIP has no factory and no lazy loader. `libpjsua2.so` is loaded by an explicit
 * `System.loadLibrary`, and if that fails nothing else in the stack can run — there is no
 * later point at which it recovers.
 *
 * So this loads it here, once, and reports what happened. Doing it in a class the
 * application asks at startup — rather than in a `companion object` somewhere down the
 * call path — is what turns "the app crashes on the first call" into "the app says the
 * stack is unavailable", which is the difference between a bug report and a diagnosis.
 */
@Singleton
class SipStackInfo @Inject constructor(
    private val logger: Logger,
) {

    fun isLoaded(): Boolean = loaded

    fun logStatus() {
        if (loaded) {
            logger.info(TAG, "SIP stack loaded (PJSIP)")
        } else {
            logger.error(TAG, "SIP stack unavailable: $failure")
        }
    }

    private companion object {
        const val TAG = "SipStack"

        /** The SWIG wrapper, which links pjsua2 and the whole of PJSIP behind it. */
        const val LIBRARY = "pjsua2"

        private var failure: String? = null

        /**
         * Loaded once for the process, in a static initialiser.
         *
         * `UnsatisfiedLinkError` is an `Error` rather than an `Exception`, so it is caught
         * by name: a missing ABI must leave the app reporting an unavailable stack, not
         * take the process down at class-load time.
         */
        private val loaded: Boolean = try {
            System.loadLibrary(LIBRARY)
            true
        } catch (e: UnsatisfiedLinkError) {
            failure = e.message ?: e.javaClass.simpleName
            false
        }
    }
}
