package com.whatsappv2.service

import android.content.pm.ServiceInfo
import android.os.Build

/**
 * Which foreground-service types this service may declare right now (Task 51, §3).
 *
 * ## Why this is a decision and not a constant
 *
 * From Android 14 a foreground service must declare what it is doing, and the platform
 * checks each declared type against a permission **at the moment `startForeground` is
 * called**. Declaring a type whose permission is not granted throws
 * `ForegroundServiceStartNotAllowedException` — and the whole point of Task 51's second
 * done-when is that a user who declined the camera keeps a working phone. A manifest that
 * declares `camera` and a service that always asks for it would crash exactly the person
 * the downgrade exists to protect.
 *
 * So the manifest declares everything this app can ever do, and this picks the subset that
 * is true and permitted at the time. Pure, so the combinations can be enumerated in a JVM
 * test rather than discovered on a handset running one Android version.
 *
 * ## The types, and why each one
 *
 * - `specialUse` — holding a registration. Not a phone call, not a data sync, not any
 *   other standard type; claiming one of those would be a false statement about
 *   behaviour.
 * - `phoneCall` — a call is in progress. Gated on `MANAGE_OWN_CALLS`, which this app holds
 *   because its calls are registered with Telecom.
 * - `microphone` — the call is capturing audio, which every call does. Gated on
 *   `RECORD_AUDIO`.
 * - `camera` — the call is sending video. Gated on `CAMERA`, and added only when a video
 *   call is actually running, so an audio call on a device with no camera permission
 *   declares nothing it cannot back up.
 */
internal object ForegroundServiceTypes {

    /**
     * The type mask for a service running for [reason].
     *
     * @param sdkInt the running platform version, injected so every branch is testable.
     * @param microphoneGranted `RECORD_AUDIO` is held.
     * @param cameraGranted `CAMERA` is held **and** the device has one.
     * @param videoActive a call is sending video right now.
     */
    fun of(
        reason: ServiceReason,
        sdkInt: Int = Build.VERSION.SDK_INT,
        microphoneGranted: Boolean = false,
        cameraGranted: Boolean = false,
        videoActive: Boolean = false,
    ): Int {
        // Before Q a service declared no type at all, and passing one is not merely
        // unnecessary — it is an argument the platform did not have.
        if (sdkInt < Build.VERSION_CODES.Q) return NONE

        if (reason != ServiceReason.ACTIVE_CALL) {
            // specialUse arrived in 14. Below that, phoneCall is the closest honest type
            // for a service whose whole purpose is being reachable for calls.
            return if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            }
        }

        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL

        // Both are additive and both are conditional on the permission being held, because
        // the platform verifies them here rather than at install time. A call whose
        // microphone permission was revoked mid-call still gets to keep running as a call.
        if (microphoneGranted) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (videoActive && cameraGranted) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA

        return types
    }

    /** What `startForeground` takes when the platform has no notion of service types. */
    const val NONE: Int = 0
}
