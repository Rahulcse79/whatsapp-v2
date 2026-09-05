package com.whatsappv2.data.sip

import com.whatsappv2.core.common.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brings the SIP stack up, and puts it down again.
 *
 * ## Why this exists at all
 *
 * [LinphoneSipEngine] is `internal`, deliberately — nothing above `:data:sip` should be
 * able to name the class that owns the native stack. But something above it has to decide
 * *when* the stack exists, because the engine will not start itself: `register` returns
 * `EngineUnavailable` until [LinphoneSipEngine.start] has run, and a test asserts exactly
 * that.
 *
 * Between Task 27 and Task 30 nothing did. The engine was written, tested to 100% on its
 * registration path, and never bound or started — `SipEngineModule` still pointed at
 * [UnavailableSipEngine], so every account in the running app reported Offline no matter
 * what the user typed. Four tasks were built on top of a stack that was never switched on.
 * This class is the missing half: a public handle to an internal engine, holding nothing
 * of its own.
 *
 * ## Why not start it from the dependency graph
 *
 * Because a native stack that comes up as a side effect of injection comes up before the
 * app has decided it wants one — and on a device that is a socket, a media library and a
 * wake-up path, not just an object. [LinphoneSipEngine] says so in its own KDoc. Keeping
 * the start explicit means the decision is somewhere a person can read it.
 */
@Singleton
class SipEngineLifecycle @Inject internal constructor(
    private val engine: LinphoneSipEngine,
    private val logger: Logger,
) {

    /**
     * Starts the stack if it is not already running.
     *
     * Failures are logged, not thrown. This runs from `Application.onCreate`, where an
     * exception is a crash on launch: an app that cannot bring up its SIP stack should
     * still open, show its accounts, and say they are offline — which is what the engine
     * reports when it is not started, and is honest (§6). Taking the process down instead
     * would turn a bad network or a missing native library into an app that will not run.
     */
    fun start() {
        runCatching { engine.start() }
            .onFailure { logger.error(TAG, "SIP stack failed to start: ${it.javaClass.simpleName}") }
    }

    /** Releases the stack and everything it holds. */
    fun stop() {
        runCatching { engine.stop() }
            .onFailure { logger.error(TAG, "SIP stack failed to stop: ${it.javaClass.simpleName}") }
    }

    private companion object {
        const val TAG = "SipEngineLifecycle"
    }
}
