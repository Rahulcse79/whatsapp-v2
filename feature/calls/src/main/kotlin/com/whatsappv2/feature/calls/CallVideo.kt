package com.whatsappv2.feature.calls

import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.whatsappv2.core.designsystem.theme.AppTheme

/**
 * The video half of the call screen (Task 52, DoD 9).
 *
 * ## Two views, given to the stack together and taken back together
 *
 * The remote picture fills the screen and the local preview sits in a corner over it. Both
 * are plain [TextureView]s handed to the SIP stack through [CallActions.onVideoSurfaces],
 * which reaches `:data:sip` by way of a `:domain` port — this module never learns which
 * stack draws into them.
 *
 * The `DisposableEffect` is the part that matters. A surface handed to a native renderer
 * and never taken back is a texture it keeps writing into after the composable has gone:
 * a leak of every frame of the call, and on some devices a crash when the underlying
 * buffer is freed. `onDispose` is the one place that reliably runs — including on a
 * rotation, where the views are recreated and the stack must be pointed at the new ones.
 *
 * ## Aspect ratio is the stack's job here, and ours in the maths
 *
 * A `TextureView` scales its content to its bounds, which is what produces the stretching
 * Task 52's third done-when forbids. [VideoLayout] is the arithmetic that decides the
 * right bounds; this composable fills the space it is given and lets the renderer letterbox
 * inside it, so a remote resolution change is a differently-shaped picture rather than a
 * differently-stretched one.
 */
@Composable
internal fun CallVideo(
    call: CallDisplay,
    actions: CallActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val remoteView = remember { TextureView(context) }
    val previewView = remember { TextureView(context) }

    DisposableEffect(remoteView, previewView, call.showsLocalPreview) {
        actions.onVideoSurfaces(remoteView, previewView.takeIf { call.showsLocalPreview })
        onDispose { actions.onReleaseVideoSurfaces() }
    }

    Box(modifier = modifier.fillMaxSize().testTag(TAG_VIDEO)) {
        AndroidView(
            factory = { remoteView },
            modifier = Modifier.fillMaxSize().testTag(TAG_REMOTE),
        )

        if (call.showsLocalPreview) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(AppTheme.spacing.large)
                    .size(AppTheme.sizing.videoPreview)
                    .testTag(TAG_PREVIEW),
            )
        }
    }
}

internal const val TAG_VIDEO = "call-video"
internal const val TAG_REMOTE = "call-video-remote"
internal const val TAG_PREVIEW = "call-video-preview"
