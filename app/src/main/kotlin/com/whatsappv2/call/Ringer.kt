package com.whatsappv2.call

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.whatsappv2.core.common.logging.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays the ringtone and the vibration for an incoming call (Task 37).
 *
 * Thin on purpose: [RingerPolicy] decides *whether* to ring, this only does it. The split
 * is what makes "silent mode is respected" a unit test rather than a manual check with a
 * handset and a switch.
 *
 * ## Why the app owns the ringtone
 *
 * Telecom does not ring for self-managed calls, and the notification channel is
 * deliberately silent so the platform does not ring over the top of this. That leaves one
 * ringtone, started and stopped in one place, which is also the only way to be sure it
 * stops: a channel-driven sound outlives an answered call by however long the sound is.
 */
@Singleton
class Ringer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) {

    private var ringtone: Ringtone? = null
    private var ringing = false

    /**
     * Starts ringing, unless the device says not to.
     *
     * Idempotent: an incoming call whose notification is rebuilt — a display-name lookup
     * completing, say — must not start a second ringtone over the first.
     */
    fun start() {
        if (ringing) return
        val signal = RingerPolicy.signalFor(currentRingerMode(), currentInterruptionFilter())
        if (signal.isSilent) {
            logger.info(TAG, "Incoming call arrives silently by device setting")
            return
        }
        ringing = true

        if (signal.playRingtone) startRingtone()
        if (signal.vibrate) startVibration()
    }

    /** Stops whatever [start] began. Safe to call when nothing is ringing. */
    fun stop() {
        ringing = false
        runCatching { ringtone?.stop() }
            .onFailure { logger.warn(TAG, "Could not stop the ringtone: ${it.javaClass.simpleName}") }
        ringtone = null
        runCatching { vibrator()?.cancel() }
    }

    /**
     * Starts the device's ringtone.
     *
     * `RingtoneManager` and `Ringtone`'s attribute setter are deprecated on the newest
     * platforms in favour of `Ringtone.Builder`, which arrived in API 36 — `minSdk` is 26,
     * so across almost the whole supported range this is the only ringtone API there is.
     * Suppressed on this function alone, so the obligation is visible at the place it
     * applies rather than hidden at the top of the file.
     */
    @Suppress("DEPRECATION")
    private fun startRingtone() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return

        runCatching {
            RingtoneManager.getRingtone(context, uri)?.apply {
                // USAGE_NOTIFICATION_RINGTONE, so the ringtone follows the ring volume
                // slider rather than media, and ducks for the call it announces.
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        }
            .onSuccess { ringtone = it }
            .onFailure { logger.error(TAG, "Could not start the ringtone: ${it.javaClass.simpleName}") }
    }

    private fun startVibration() {
        val vibrator = vibrator() ?: return
        val effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, VIBRATION_REPEAT_INDEX)
        runCatching { vibrator.vibrate(effect) }
            .onFailure { logger.warn(TAG, "Could not vibrate: ${it.javaClass.simpleName}") }
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    private fun currentRingerMode(): RingerMode {
        val audio = context.getSystemService(AudioManager::class.java)
        return when (audio?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
            AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
            AudioManager.RINGER_MODE_NORMAL -> RingerMode.NORMAL
            // No AudioManager at all is not a device that should be made to ring.
            else -> RingerMode.SILENT
        }
    }

    private fun currentInterruptionFilter(): InterruptionFilter {
        val notifications = context.getSystemService(NotificationManager::class.java)
        return when (notifications?.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> InterruptionFilter.ALL
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> InterruptionFilter.PRIORITY
            NotificationManager.INTERRUPTION_FILTER_NONE -> InterruptionFilter.NONE
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> InterruptionFilter.ALARMS
            else -> InterruptionFilter.UNKNOWN
        }
    }

    private companion object {
        const val TAG = "Ringer"

        /** Off, buzz, off — the cadence a phone call has always had. */
        val VIBRATION_PATTERN = longArrayOf(0, 1_000, 1_000)

        /** Repeat from the start of the pattern, so it buzzes until the call is handled. */
        const val VIBRATION_REPEAT_INDEX = 0
    }
}
