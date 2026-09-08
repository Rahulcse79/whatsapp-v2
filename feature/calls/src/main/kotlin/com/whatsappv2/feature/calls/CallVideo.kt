package com.whatsappv2.feature.calls

import android.view.SurfaceHolder
import android.view.SurfaceView
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
 * ## Surfaces, not textures — and that is the SIP stack's requirement
 *
 * PJSIP does not take a view. It renders into an `android.view.Surface` handed to a
 * `VideoWindow`, so what the stack needs is a `SurfaceHolder`'s surface and the two
 * moments it becomes valid and invalid — which a `TextureView` does not expose in the
 * shape the renderer wants.
 *
 * A `SurfaceView` is also the better fit for the job. It composites on its own layer
 * rather than through the view hierarchy's texture, which for full-screen video means
 * fewer copies per frame — and the allocation pressure during a video call was already a
 * measured problem on this app, not a theoretical one.
 *
 * ## The callback is the contract, not the composable's lifetime
 *
 * A surface is not valid because the composable exists; it is valid between
 * `surfaceCreated` and `surfaceDestroyed`, which do not line up with composition. Handing
 * the stack a surface before it is created draws nothing, and holding one after it is
 * destroyed is a native write into freed memory. So the holder callback drives the
 * hand-over, and `onDispose` releases — a surface the renderer keeps after the screen has
 * gone is a crash on some devices and a leak of every frame on the rest.
 *
 * ## Aspect ratio
 *
 * The renderer letterboxes inside the bounds it is given; [VideoLayout] is the arithmetic
 * that decides those bounds. This composable fills the space it has, so a remote
 * resolution change is a differently-shaped picture rather than a differently-stretched
 * one — Task 52's third done-when.
 */
@Composable
internal fun CallVideo(
    call: CallDisplay,
    actions: CallActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val remoteView = remember { SurfaceView(context) }
    val previewView = remember {
        SurfaceView(context).apply {
            // Two SurfaceViews overlap here, and a SurfaceView does not compose with the
            // view hierarchy the way an ordinary view does — it punches through to its own
            // layer. Without this the preview is drawn *behind* the full-screen remote
            // video and is simply invisible, which reads as "the local preview is broken"
            // rather than as a z-order problem.
            setZOrderMediaOverlay(true)
        }
    }

    DisposableEffect(remoteView, previewView, call.showsLocalPreview) {
        // Held here so the two surfaces can be reported together: the stack takes both at
        // once, and one arriving without the other would detach the one already attached.
        var remote: Any? = null
        var preview: Any? = null

        fun publish() = actions.onVideoSurfaces(remote, preview)

        val remoteCallback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                remote = holder.surface
                publish()
            }

            // A format or size change replaces the underlying buffer, so the stack has to
            // be pointed at it again rather than assuming the one it holds is still good.
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                remote = holder.surface
                publish()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                remote = null
                publish()
            }
        }

        val previewCallback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                preview = holder.surface
                publish()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                preview = holder.surface
                publish()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                preview = null
                publish()
            }
        }

        remoteView.holder.addCallback(remoteCallback)
        if (call.showsLocalPreview) previewView.holder.addCallback(previewCallback)

        onDispose {
            remoteView.holder.removeCallback(remoteCallback)
            previewView.holder.removeCallback(previewCallback)
            actions.onReleaseVideoSurfaces()
        }
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
