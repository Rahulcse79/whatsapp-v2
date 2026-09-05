package com.whatsappv2.audio

import android.content.Context
import android.os.PowerManager
import com.whatsappv2.core.common.logging.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the screen off while the phone is at an ear (Task 40).
 *
 * `PROXIMITY_SCREEN_OFF_WAKE_LOCK` is the platform's own mechanism for this: the sensor is
 * read by the system, which blanks the display while something is close and restores it
 * when it is not. Reading the sensor directly and blanking the screen by hand would be a
 * worse copy of it, and would fight the platform for control of the display.
 *
 * ## Held only during a call on the earpiece
 *
 * §6 is strict about wake locks, and this one earns its keep for exactly as long as the
 * phone is against a face: any other route means the phone is not at an ear, and a call
 * that has ended means nothing should be held at all. Releasing is therefore idempotent
 * and is called from more places than acquiring.
 */
@Singleton
class ProximityLock @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) {

    private var lock: PowerManager.WakeLock? = null

    /** Acquires the lock if the device has a proximity sensor. Safe to call twice. */
    fun acquire() {
        if (lock?.isHeld == true) return

        val power = context.getSystemService(PowerManager::class.java) ?: return
        if (!power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            // Plenty of devices have no proximity sensor. Not an error: the screen simply
            // stays on, which is what those devices do for every calling app.
            logger.debug(TAG, "No proximity wake lock on this device")
            return
        }

        val acquired = power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, WAKE_LOCK_TAG)
        runCatching { acquired.acquire(MAX_HOLD_MILLIS) }
            .onSuccess { lock = acquired }
            .onFailure { logger.warn(TAG, "Could not acquire the proximity lock: ${it.javaClass.simpleName}") }
    }

    /** Releases the lock. Safe to call when nothing is held. */
    fun release() {
        val held = lock ?: return
        runCatching { if (held.isHeld) held.release() }
            .onFailure { logger.warn(TAG, "Could not release the proximity lock: ${it.javaClass.simpleName}") }
        lock = null
    }

    private companion object {
        const val TAG = "ProximityLock"

        /**
         * The tag `PowerManager` sees, which is not the logging tag.
         *
         * The platform wants `owner:purpose`, so the app's name prefixes it: a battery
         * report that says only "ProximityLock" names nothing anyone can trace to an app.
         */
        const val WAKE_LOCK_TAG = "whatsappv2:proximity"

        /**
         * A hard ceiling on the lock, in milliseconds.
         *
         * Four hours. Long enough that no real call hits it, short enough that a bug which
         * loses the release does not black out a phone until it is rebooted — which is the
         * failure mode §6 is written to prevent.
         */
        const val MAX_HOLD_MILLIS = 4L * 60L * 60L * 1_000L
    }
}
