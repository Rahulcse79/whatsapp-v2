package com.whatsappv2.permission

/** Where a permission currently stands, from the app's point of view. */
sealed interface PermissionStatus {

    /** Held, either granted or not applicable on this Android version. */
    data object Granted : PermissionStatus

    /** Never asked. The rationale should be shown before the system dialog. */
    data object NotRequested : PermissionStatus

    /** Refused, but the system will still show its dialog if asked again. */
    data object Denied : PermissionStatus

    /**
     * Refused in a way Android will not prompt for again.
     *
     * Detected as: not granted, the system says no rationale is warranted, **and** we
     * have asked before. That last clause matters — before the first request Android
     * also reports no rationale, so without it every fresh install would look
     * permanently denied.
     */
    data object PermanentlyDenied : PermissionStatus

    val isGranted: Boolean get() = this is Granted

    /** True when asking again cannot help; only app settings can (Task 15 done-when #3). */
    val requiresSettings: Boolean get() = this is PermanentlyDenied
}

/** Remembers which permissions have been asked for, across process death. */
interface PermissionRequestTracker {
    fun hasBeenRequested(permission: AppPermission): Boolean
    fun markRequested(permission: AppPermission)

    /**
     * True once the first-run permission screen has been through (Task 72).
     *
     * Its own flag rather than "have we asked for anything yet": a user who skipped every
     * step has been asked for nothing, and inferring from the per-permission record would
     * show them the same screen on every launch for ever.
     */
    fun hasCompletedOnboarding(): Boolean

    fun markOnboardingComplete()
}
