package com.whatsappv2.permission

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.vector.ImageVector

/** The icon shown on a permission's rationale sheet. */
internal fun AppPermission.iconForSheet(): ImageVector = when (this) {
    AppPermission.RECORD_AUDIO -> Icons.Filled.Mic
    AppPermission.CAMERA -> Icons.Filled.Videocam
    AppPermission.POST_NOTIFICATIONS -> Icons.Filled.Notifications
    AppPermission.BLUETOOTH_CONNECT -> Icons.Filled.Bluetooth
    AppPermission.READ_CONTACTS -> Icons.Filled.Contacts
    AppPermission.MANAGE_OWN_CALLS -> Icons.Filled.Call
}

/**
 * Unwraps the Activity from a Compose context.
 *
 * `shouldShowRequestPermissionRationale` is an Activity method, and a Compose
 * `LocalContext` is often a ContextWrapper rather than the Activity itself.
 */
internal fun Context.findActivity(): Activity? = generateSequence(this) {
    (it as? ContextWrapper)?.baseContext
}.filterIsInstance<Activity>().firstOrNull()
