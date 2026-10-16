package com.whatsappv2.domain.engine

/**
 * Whether this device can send video at all (Task 51, §5.2, §7).
 *
 * ## Why a port and not a check at the call site
 *
 * Two different things stop a camera being usable, and only one of them is a permission:
 * the user declined `CAMERA`, or the device has no camera — the manifest marks the
 * feature `required="false"` precisely so the app installs on those. Both answers arrive
 * from Android, so the question cannot be asked from `:domain`, and both have the same
 * consequence, so the caller should not have to tell them apart.
 *
 * The consequence is Task 51's second done-when: **downgrade, never fail**. A video call
 * placed on a device that cannot capture becomes an audio call
 * ([com.whatsappv2.domain.model.MediaProfile.downgradedWhenCameraUnavailable]) and goes
 * out. Refusing it instead would make a privacy choice the app invited into a broken
 * phone.
 *
 * Read per call rather than cached: a permission granted in Settings while the app is in
 * the background is granted, and a cached "no" would keep video off until the process
 * restarted.
 */
interface CameraAvailability {

    /**
     * True when a camera exists and this app may use it.
     *
     * The one method deliberately answers both questions with one boolean. A caller that
     * could tell "declined" from "no hardware" would be tempted to nag about the first,
     * and §7 asks for a declined permission to be respected rather than re-litigated in
     * the middle of a call.
     */
    fun isCameraUsable(): Boolean
}

/**
 * The answer for a context with no camera at all.
 *
 * Used by JVM tests that are not exercising video, and as the honest default in a graph
 * where nothing has bound a real one: a `false` downgrades video calls to audio, which
 * still places the call.
 */
object NoCameraAvailable : CameraAvailability {
    override fun isCameraUsable(): Boolean = false
}
