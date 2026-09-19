package com.whatsappv2.permission

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Asks for the microphone at the moment a call is placed, and places the call **only if it
 * is granted**.
 *
 * ## The defect this closes
 *
 * Nothing asked. [rememberCameraGate] existed for the video button and the dialler's plain
 * *Call* button had no gate at all, so a call placed without `RECORD_AUDIO` went straight
 * to the stack — which then could not open a capture device. Measured on a TC15
 * (2026-09-19): the INVITE path starts, Telecom creates the connection, and PJSIP spins on
 *
 *     E/AudioRecord: createRecord_l(384): AudioFlinger could not create record track, status: -1
 *     E/android.media.AudioRecord: Error code -20 when initializing native AudioRecord object
 *
 * a dozen times in a fifth of a second. The call never gets media: it hangs at *Calling*
 * or drops, and nothing on screen says why. The user sees a call that disconnects itself.
 *
 * ## Why this one refuses where the camera's gate proceeds
 *
 * [AppPermission.RECORD_AUDIO] is [DenialBehaviour.BLOCKS_FEATURE] and
 * [AppPermission.CAMERA] is [DenialBehaviour.DEGRADES_GRACEFULLY], and the two gates are
 * the code that makes those declarations true. A video call without a camera is still a
 * call, so [rememberCameraGate] runs `proceed` on either answer and the profile downgrades.
 * A call without a microphone is not a call — the far end hears silence and the stack
 * cannot even start — so this one runs `proceed` on a yes only.
 *
 * Refusing is not a dead end, which is the other half of `BLOCKS_FEATURE`. The rationale
 * sheet [rememberPermissionRequest] shows says what refusing costs **before** the system
 * dialog, and once Android will no longer prompt it offers the app-settings route instead.
 * So a second press of *Call* explains rather than doing nothing quietly — which is what
 * "blocks the feature with an explanation rather than dead-ending" means in
 * `PermissionOnboarding`'s comment. That promise was written before anything kept it.
 */
@Composable
internal fun rememberMicrophoneGate(): (proceed: () -> Unit) -> Unit {
    val coordinator = LocalPermissionCoordinator.current

    // Held across the system dialog, which is a different Activity: the press that started
    // this has to survive until an answer comes back. Same reason as the camera's.
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    val request = rememberPermissionRequest(AppPermission.RECORD_AUDIO, coordinator) { granted ->
        val proceed = pending
        pending = null
        // The one difference from the camera gate, and the whole point of a separate one.
        if (granted) proceed?.invoke()
    }

    return { proceed ->
        if (request.status.isGranted) {
            // Nothing to ask, and the common case by far — this must not cost a frame.
            proceed()
        } else {
            pending = proceed
            request.request()
        }
    }
}
