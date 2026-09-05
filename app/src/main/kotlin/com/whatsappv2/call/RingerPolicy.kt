package com.whatsappv2.call

/**
 * The device's ringer setting, without an Android import.
 *
 * Mirrored rather than imported so the policy below can be asserted on the JVM — the same
 * arrangement as `TelecomPolicy`, and for the same reason: a rule that can only run on a
 * device is a rule nothing checks.
 */
enum class RingerMode {
    SILENT,
    VIBRATE,
    NORMAL,
}

/** Do Not Disturb, as `NotificationManager.getCurrentInterruptionFilter` reports it. */
enum class InterruptionFilter {
    /** Nothing is filtered. */
    ALL,

    /** Only what the user marked as priority gets through. */
    PRIORITY,

    /** Total silence. */
    NONE,

    /** Alarms only. */
    ALARMS,

    /** The platform reported something this app does not know. */
    UNKNOWN,
}

/** What the app should actually do when a call arrives. */
data class RingSignal(val playRingtone: Boolean, val vibrate: Boolean) {

    /** True when the call arrives without a sound or a buzz. */
    val isSilent: Boolean get() = !playRingtone && !vibrate

    companion object {
        val SILENT: RingSignal = RingSignal(playRingtone = false, vibrate = false)
    }
}

/**
 * Whether an incoming call rings, buzzes, or arrives in silence (Task 37).
 *
 * ## Why this app rings at all
 *
 * Telecom does not ring for self-managed calls — that is the app's job, precisely because
 * the app owns the UI. So the ringer setting has to be honoured here, deliberately, rather
 * than inherited from a notification channel: the channel is configured **silent** so the
 * platform does not ring over the top of this, which would produce two ringtones at once.
 *
 * ## The rules, and why each one is what it is
 *
 * - **Total silence and alarms-only mean silence.** The user asked for nothing, and a
 *   calling app is not an exception to that; the notification still posts, so a call is
 *   never lost, it is only quiet.
 * - **DND on priority follows the ringer.** "Priority only" is the user allowing some
 *   things through, and the platform decides which — for the *notification*, which is
 *   posted either way. Overriding the ringer here would make this app louder than the
 *   phone app under the same setting, which is not a decision an app gets to make.
 * - **Vibrate never rings, normal never doubles up.** A ringtone in vibrate mode is the
 *   bug people notice in a meeting. Ringing *and* buzzing at once is the platform's own
 *   "vibrate when ringing" preference, which this app cannot read, so it does not guess.
 */
object RingerPolicy {

    fun signalFor(mode: RingerMode, filter: InterruptionFilter): RingSignal = when (filter) {
        InterruptionFilter.NONE, InterruptionFilter.ALARMS -> RingSignal.SILENT

        // ALL, PRIORITY, and an unknown filter all defer to the ringer switch, which is
        // the setting the user reaches for physically and expects to be obeyed.
        InterruptionFilter.ALL,
        InterruptionFilter.PRIORITY,
        InterruptionFilter.UNKNOWN,
        -> when (mode) {
            RingerMode.SILENT -> RingSignal.SILENT
            RingerMode.VIBRATE -> RingSignal(playRingtone = false, vibrate = true)
            RingerMode.NORMAL -> RingSignal(playRingtone = true, vibrate = false)
        }
    }
}
