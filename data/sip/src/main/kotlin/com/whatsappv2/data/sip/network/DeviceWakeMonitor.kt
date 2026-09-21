package com.whatsappv2.data.sip.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The moments a sleeping device becomes a device somebody is about to use.
 *
 * Each one is a reason to check the registration *now* rather than when the next timer
 * says so. The timers are doze-proof ([WakeTimer]), but a timer is still a timer: a
 * person who picks the phone up thirty seconds before the next keepalive is a person who
 * will place a call on a registration that may have lapsed. The screen coming on, the
 * lock screen being passed, the app coming to the front and the platform leaving doze
 * are all the same statement — "the device is awake and in use" — and the recovery loop
 * answers every one of them with a REGISTER, throttled so a screen toggled on and off
 * repeatedly does not become a REGISTER storm.
 *
 * Behind an interface for the same reason [NetworkMonitor] is: the platform half needs a
 * `BroadcastReceiver` and an `Application`, and the rule — "re-register on wake" — is
 * asserted on the JVM without either.
 */
internal interface DeviceWakeMonitor {

    val wakes: Flow<WakeReason>

    companion object {
        /** A device that never reports waking. For tests, and for a graph with no platform. */
        val NONE: DeviceWakeMonitor = object : DeviceWakeMonitor {
            override val wakes: Flow<WakeReason> = emptyFlow()
        }
    }
}

/** Why the device counts as awake. Logged, and otherwise treated alike. */
internal enum class WakeReason {
    /** `ACTION_SCREEN_ON`. */
    SCREEN_ON,

    /** `ACTION_USER_PRESENT`: the lock screen was passed. */
    USER_PRESENT,

    /** `ACTION_DEVICE_IDLE_MODE_CHANGED` with idle now false: the platform left doze. */
    DOZE_EXIT,

    /** One of this app's activities came to the front. */
    APP_FOREGROUND,
}
