package com.whatsappv2.permission

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.whatsappv2.core.designsystem.component.PermissionRationaleSheet

/** What a caller can do with a permission, and where it currently stands. */
class PermissionRequestState internal constructor(
    val permission: AppPermission,
    val status: PermissionStatus,
    private val onRequest: () -> Unit,
) {
    /**
     * Starts the flow: shows the rationale first if the permission has never been asked
     * for, opens app settings if it is permanently denied, and otherwise asks directly.
     */
    fun request() = onRequest()
}

/**
 * Drives one permission: rationale, system dialog, and the settings route.
 *
 * In-context by design — asked at the moment the capability is needed rather than at
 * launch, because a wall of dialogs on first run is how people learn to press Deny.
 *
 * The rationale is shown **before** the system dialog on a first request, since Android
 * gives only one chance: a user who declines without knowing why may never see the
 * prompt again.
 */
@Composable
fun rememberPermissionRequest(
    permission: AppPermission,
    coordinator: PermissionCoordinator,
    onResult: (granted: Boolean) -> Unit = {},
): PermissionRequestState {
    val context = LocalContext.current
    val activity = context.findActivity()

    var status by remember(permission) {
        mutableStateOf(coordinator.status(permission, activity))
    }
    var showRationale by remember(permission) { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        coordinator.markRequested(permission)
        status = coordinator.status(permission, activity)
        onResult(granted)
    }

    if (showRationale) {
        PermissionRationaleSheet(
            icon = permission.iconForSheet(),
            title = permission.title,
            rationale = if (status.requiresSettings) {
                permission.deniedExplanation
            } else {
                permission.rationale
            },
            permanentlyDenied = status.requiresSettings,
            onRequest = {
                showRationale = false
                if (status.requiresSettings) {
                    context.startActivity(coordinator.appSettingsIntent())
                } else {
                    launcher.launch(permission.manifestPermission)
                }
            },
            onDismiss = { showRationale = false },
        )
    }

    return PermissionRequestState(
        permission = permission,
        status = status,
        onRequest = {
            when {
                // Nothing to do; also covers versions where the permission does not exist.
                status.isGranted -> onResult(true)

                // The system dialog will never appear again, so explain and offer settings.
                status.requiresSettings -> showRationale = true

                // Explain before the one prompt Android will give us.
                status is PermissionStatus.NotRequested -> showRationale = true

                else -> launcher.launch(permission.manifestPermission)
            }
        },
    )
}

/**
 * The coordinator, available anywhere in the composition.
 *
 * Provided by `MainActivity`. A permission request happens deep inside a screen, and
 * threading the coordinator through every composable in between would couple all of them
 * to something only the leaf needs.
 */
val LocalPermissionCoordinator = staticCompositionLocalOf<PermissionCoordinator> {
    error("No PermissionCoordinator provided; MainActivity must supply one")
}
