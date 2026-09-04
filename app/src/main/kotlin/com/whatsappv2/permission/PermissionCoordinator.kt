package com.whatsappv2.permission

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers "where does this permission stand?" and knows how to open app settings.
 *
 * Kept out of the composables so the rules can be reasoned about and tested without a
 * Compose runtime, and so a screen cannot invent its own definition of "permanently
 * denied" — the subtlety below is exactly the kind that gets copied wrong.
 */
@Singleton
class PermissionCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tracker: PermissionRequestTracker,
) {

    fun status(permission: AppPermission, activity: Activity?): PermissionStatus {
        // A permission that does not exist on this version is implicitly held. Reporting
        // it as denied would make the UI nag about something the user cannot grant.
        if (!permission.appliesToThisDevice) return PermissionStatus.Granted

        // Install-time permissions are granted at install or not at all.
        if (!permission.isRuntimePermission) return granted(permission)

        if (isGranted(permission)) return PermissionStatus.Granted
        if (!tracker.hasBeenRequested(permission)) return PermissionStatus.NotRequested

        // shouldShowRequestPermissionRationale is false BOTH before the first request and
        // after a permanent denial. Only the "have we asked" record separates them.
        val canAskAgain = activity?.shouldShowRequestPermissionRationale(
            permission.manifestPermission,
        ) ?: true

        return if (canAskAgain) PermissionStatus.Denied else PermissionStatus.PermanentlyDenied
    }

    fun markRequested(permission: AppPermission) = tracker.markRequested(permission)

    /**
     * The intent that opens this app's settings page.
     *
     * The only route back for a permanently denied permission: the system dialog will
     * never appear again, so a button that merely re-requests would silently do nothing.
     */
    fun appSettingsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun isGranted(permission: AppPermission): Boolean =
        ContextCompat.checkSelfPermission(context, permission.manifestPermission) ==
            PackageManager.PERMISSION_GRANTED

    private fun granted(permission: AppPermission): PermissionStatus =
        if (isGranted(permission)) PermissionStatus.Granted else PermissionStatus.Denied
}
