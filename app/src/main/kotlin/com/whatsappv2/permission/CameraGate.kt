package com.whatsappv2.permission

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Asks for the camera at the moment a video call is pressed, then places it either way.
 *
 * ## Why this exists
 *
 * Task 72's first-run screen is the only place the camera is asked for, and it is
 * skippable by design. Somebody who skipped it — or declined, or granted it and later
 * revoked it — pressed the dialler's video button and got an audio call with a snackbar
 * and no prompt. The call worked; the *feature* looked broken, which is the failure Task
 * 74 said the in-context request was there to prevent, and the request was never written.
 *
 * ## It gates the prompt, never the call
 *
 * `proceed` runs whatever the user answers. `MediaProfile.downgradedWhenCameraUnavailable`
 * is the rule — **downgrade, never refuse** (Task 51) — so a declined camera still places
 * an audio call and still says so. Blocking here would turn a privacy choice the app
 * invited into a dead button, which is exactly what §5.2 forbids.
 *
 * The rationale sheet and the app-settings route come free: [rememberPermissionRequest]
 * shows the reason before the one prompt Android will give, and offers Settings once the
 * system will no longer ask.
 */
@Composable
internal fun rememberCameraGate(): (proceed: () -> Unit) -> Unit {
    val coordinator = LocalPermissionCoordinator.current

    // Held across the system dialog, which is a different Activity: the press that
    // started this has to survive until an answer comes back.
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    val request = rememberPermissionRequest(AppPermission.CAMERA, coordinator) {
        // Granted or refused, the call goes out. Only the profile differs.
        val proceed = pending
        pending = null
        proceed?.invoke()
    }

    return { proceed ->
        if (request.status.isGranted) {
            // Nothing to ask. Also covers the versions and devices where the permission
            // is implicitly held.
            proceed()
        } else {
            // `request()` shows the rationale before the one prompt Android will give,
            // or routes to Settings once the system will no longer ask. Either way the
            // answer comes back through the callback above, which runs `pending`.
            pending = proceed
            request.request()
        }
    }
}
