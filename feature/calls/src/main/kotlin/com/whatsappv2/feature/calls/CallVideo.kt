package com.whatsappv2.feature.calls

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.viewinterop.AndroidView
import com.whatsappv2.domain.engine.VideoSize
import com.whatsappv2.domain.engine.VideoSizes

/**
 * The video half of the call screen (Task 52, DoD 9).
 *
 * ## Two surfaces, two kinds of view — and the SIP stack does not mind which
 *
 * PJSIP does not take a view. It renders into an `android.view.Surface` handed to a
 * `VideoWindow` — `setWindow` in `pjsua2.i` is `ANativeWindow_fromSurface` and nothing
 * more — so what the stack needs from this side is a surface and the two moments it
 * becomes valid and invalid. A `SurfaceHolder` gives those for a `SurfaceView`; a
 * `SurfaceTextureListener` gives them for a `TextureView`, wrapped as
 * `Surface(surfaceTexture)`. Either is a window the OpenGL renderer draws into.
 *
 * The **remote** picture is a `SurfaceView`. It composites on its own layer rather than
 * through the view hierarchy's texture, which for full-screen video means fewer copies
 * per frame — and the allocation pressure during a video call was already a measured
 * problem on this app, not a theoretical one.
 *
 * The **self-view** is a `TextureView`, and it has to be. It floats over the remote
 * picture in a box the user resizes, and the camera is scaled to *cover* that box, so the
 * overflow has to be cropped — which a `SurfaceView` cannot be. It does not draw into its
 * parent's canvas: it punches a hole and SurfaceFlinger composites its surface at the
 * view's own bounds, so neither `clipChildren` nor a Compose `clip` reaches it, and with
 * the remote view having already punched the whole window transparent the oversized
 * preview simply showed through everywhere — on a TC15 the camera drew across the full
 * width of the screen (2026-09-17). A `TextureView` draws through the hierarchy like any
 * other view, so [SelfPreview]'s crop is a real crop, and it sits above the remote
 * picture's hole with no z-order arrangement at all.
 *
 * ## The callback is the contract, not the composable's lifetime
 *
 * A surface is not valid because the composable exists; it is valid between
 * `surfaceCreated` and `surfaceDestroyed` — `onSurfaceTextureAvailable` and
 * `onSurfaceTextureDestroyed` for the preview — which do not line up with composition.
 * Handing the stack a surface before it is created draws nothing, and holding one after it
 * is destroyed is a native write into freed memory. So the view callbacks drive the
 * hand-over, and `onDispose` releases — a surface the renderer keeps after the screen has
 * gone is a crash on some devices and a leak of every frame on the rest.
 *
 * ## Aspect ratio: the renderer does NOT letterbox
 *
 * This used to say the renderer letterboxed inside whatever bounds it was given. It does
 * not. `pjmedia_vid_dev_opengl_draw` binds the frame to a fixed full-clip-space quad with
 * fixed texture coordinates and sets the viewport to the whole surface
 * (`opengl_dev.c:271-300`): every frame is *stretched* to the view's bounds, whatever
 * shape either of them is. Three TC15s on 2026-09-14 showed it plainly — one letterboxed
 * its picture, two stretched a landscape frame down a portrait screen and the faces came
 * out a head taller than they should be.
 *
 * So the bounds are the only control there is, and this composable sizes the view to the
 * frame rather than filling the space: [VideoLayout.cover] for a one-to-one call, which
 * fills the screen and lets the overflow fall off its edges, and [VideoLayout.fit] for a
 * conference, where the picture is a grid of people and cropping it cuts somebody in
 * half. [VideoSizes] carries the frame shapes and is recomputed on every change, so a
 * peer that rotates mid-call gets a differently-shaped view rather than a
 * differently-stretched one — Task 52's third done-when, now actually met.
 *
 * The overflow from [VideoLayout.cover] is clipped by the edge of the *display*, not by
 * Compose: a `SurfaceView` composites on its own layer and an ancestor's clip does not
 * reach it. That is sound while the video is full-screen, which is the only place cover
 * is used. Anything drawing this smaller must pass [RemoteVideoScaling.Fit].
 */
@Composable
internal fun CallVideo(
    call: CallDisplay,
    actions: CallActions,
    sizes: VideoSizes = VideoSizes.UNKNOWN,
    scaling: RemoteVideoScaling = RemoteVideoScaling.Cover,
    onPictureTap: (() -> Unit)? = null,
    pictureTapLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Re-read on every configuration change: rotating the device recreates the activity
    // by default, but a manifest that opts out of that would otherwise leave the camera
    // sending the old way up. The stack keeps the value, so repeating it is harmless.
    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration) {
        actions.onDisplayRotation(context.displayRotationDegrees())
    }
    val remoteView = remember { SurfaceView(context) }
    // A `TextureView`, not a second `SurfaceView` — see the class comment. It is an
    // ordinary view, so it is drawn over the remote picture by being later in the tree and
    // cropped to its box by an ordinary parent; the `setZOrderMediaOverlay` dance a second
    // `SurfaceView` needed to be visible at all is gone with it.
    val previewView = remember { TextureView(context) }

    DisposableEffect(remoteView, previewView, call.showsLocalPreview) {
        // Held here so the two surfaces can be reported together: the stack takes both at
        // once, and one arriving without the other would detach the one already attached.
        var remote: Any? = null
        var preview: Surface? = null

        fun publish() = actions.onVideoSurfaces(remote, preview)

        val remoteCallback = surfaceCallback { surface ->
            remote = surface
            publish()
        }

        // The `Surface` wrapper is this side's to release. PJSIP takes its own reference
        // on the native window and the `SurfaceTexture` belongs to the view, but the Java
        // object in between holds a native handle that nobody else will let go of.
        fun attachPreview(texture: SurfaceTexture) {
            preview?.release()
            preview = Surface(texture)
            publish()
        }

        fun detachPreview() {
            val released = preview ?: return
            preview = null
            publish()
            released.release()
        }

        val previewListener = surfaceTextureListener(::attachPreview, ::detachPreview)

        remoteView.holder.addCallback(remoteCallback)
        if (call.showsLocalPreview) {
            previewView.surfaceTextureListener = previewListener
            // A texture that was already there when this effect (re)started is not
            // announced again, so it is picked up here rather than waited for.
            previewView.surfaceTexture?.let(::attachPreview)
        }

        onDispose {
            remoteView.holder.removeCallback(remoteCallback)
            if (previewView.surfaceTextureListener === previewListener) {
                previewView.surfaceTextureListener = null
            }
            preview?.release()
            preview = null
            actions.onReleaseVideoSurfaces()
        }
    }

    VideoSurfaces(
        remoteView = remoteView,
        previewView = previewView,
        showsPreview = call.showsLocalPreview,
        sizes = sizes,
        scaling = scaling,
        onPictureTap = onPictureTap,
        pictureTapLabel = pictureTapLabel,
        modifier = modifier,
    )
}

/**
 * The two surfaces, each laid out at the shape of the picture going into it.
 *
 * Separated from [CallVideo] because they answer different questions: that one owns the
 * *lifetime* of the surfaces — when the stack may write into them — and this one owns
 * their *bounds*, which is the whole aspect-ratio fix. Mixing the two made one function
 * that had to be read end to end to change either.
 */
@Composable
private fun VideoSurfaces(
    remoteView: SurfaceView,
    previewView: TextureView,
    showsPreview: Boolean,
    sizes: VideoSizes,
    scaling: RemoteVideoScaling,
    onPictureTap: (() -> Unit)?,
    pictureTapLabel: String?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize().testTag(TAG_VIDEO)) {
        // Measured, not assumed. `Constraints.Infinity` means nobody has decided how big
        // this is yet, and a view sized from it would be sized from nothing — so that
        // case falls back to filling, which is where this started.
        val available = VideoSize(
            width = constraints.maxWidth.takeIf { it != Constraints.Infinity } ?: 0,
            height = constraints.maxHeight.takeIf { it != Constraints.Infinity } ?: 0,
        )
        val density = LocalDensity.current
        val remoteBox = when (scaling) {
            RemoteVideoScaling.Cover -> VideoLayout.cover(sizes.remote, available)
            RemoteVideoScaling.Fit -> VideoLayout.fit(sizes.remote, available)
        }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { remoteView },
                modifier = Modifier.videoBounds(remoteBox, available, density).testTag(TAG_REMOTE),
            )
        }

        // Between the picture and the self-view, and that position is the point. Tapping
        // the picture hides the call controls; dragging the self-view moves it. Put this
        // above the preview and every drag becomes a tap; leave it out of this layer
        // altogether — which is where it used to live — and the remote `SurfaceView` takes
        // the pointer before it ever arrives.
        //
        // It carries a label because it is the only thing on screen once the controls
        // fade: an unlabelled Box leaves a screen-reader user a blank screen and no way
        // back.
        if (onPictureTap != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClickLabel = pictureTapLabel,
                        onClick = onPictureTap,
                    )
                    .testTag(TAG_PICTURE_TAP),
            )
        }

        if (showsPreview) {
            // The box is [SelfPreview]'s own, because it is now the *user's*: they drag it
            // between corners, resize it and minimise it, and none of that is this
            // composable's business. What is passed is the camera's frame shape, which is
            // scaled to cover whatever box they have chosen and cropped to it.
            //
            // The box deliberately does not take the camera's shape, and a camera's shape
            // changes — a rotation, a resolution drop, joining a conference. Pinned by only
            // two of its edges, the other two then moved and the self-view appeared to slide
            // about and resize itself mid-call. Covering a box the user controls keeps the
            // renderer from stretching a face sideways without letting the frame's shape
            // reach the layout.
            SelfPreview(previewView = previewView, localFrame = sizes.local)
        }
    }
}

/**
 * A holder callback that reports its surface, or null once it is gone.
 *
 * The three overrides collapse to one lambda because the stack wants the same thing from
 * all three: `surfaceChanged` matters as much as `surfaceCreated` — a format or size
 * change replaces the underlying buffer, so the renderer has to be pointed at the new one
 * rather than left holding the old — and `surfaceDestroyed` is the null that stops a
 * native write into freed memory.
 */
private fun surfaceCallback(onSurface: (Any?) -> Unit): SurfaceHolder.Callback =
    object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = onSurface(holder.surface)

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
            onSurface(holder.surface)

        override fun surfaceDestroyed(holder: SurfaceHolder) = onSurface(null)
    }

/**
 * The `TextureView` counterpart of [surfaceCallback]: the texture when it appears, and a
 * call when it goes.
 *
 * A size change is deliberately *not* reported. The `SurfaceTexture` is the same object at
 * every size — the view scales whatever the renderer produces to its bounds, and PJSIP
 * sizes its own buffers to the frame (`ANativeWindow_setBuffersGeometry`) — so the window
 * the stack holds stays valid through a resize, and re-handing it would rebuild the
 * preview window for a layout pass. That rebuild was the cost of every resize while the
 * preview was a `SurfaceView`, whose `surfaceChanged` replaces the buffer.
 */
private fun surfaceTextureListener(
    onAvailable: (SurfaceTexture) -> Unit,
    onDestroyed: () -> Unit,
): TextureView.SurfaceTextureListener =
    object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) =
            onAvailable(surface)

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            onDestroyed()
            // The stack has been told to let go, so the view may release the texture.
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

/**
 * Which way a picture that is not the screen's shape should lose the difference.
 *
 * Two values rather than a boolean because neither is a default the other is an exception
 * to: they are the right answer to two different questions, and the call site knows which
 * one it is asking. See [VideoLayout.fit] and [VideoLayout.cover].
 */
internal enum class RemoteVideoScaling {
    /** Fill the space and let the overflow fall outside it. A one-to-one call. */
    Cover,

    /** Show all of it and accept the bars. A composed conference grid. */
    Fit,
}

/**
 * Lays a video view out at [box], or fills [available] while [box] is not yet knowable.
 *
 * The fallback matters more than it looks: for the first frames of every call there is no
 * decoded size, and a view sized to zero then grown is a black screen that blinks. Filling
 * is what the screen did before any of this, and it is the right thing to do for exactly
 * as long as the shape is unknown.
 */
private fun Modifier.videoBounds(box: VideoSize, available: VideoSize, density: Density): Modifier =
    if (box.isKnown && available.isKnown) {
        with(density) { this@videoBounds.size(box.width.toDp(), box.height.toDp()) }
    } else {
        fillMaxSize()
    }

/**
 * The display's rotation as degrees clockwise from the device's natural orientation.
 *
 * `Context.display` is API 30; below that the window manager's default display is the
 * one an activity's context refers to. The getter throws rather than returning null for a
 * context with no display, which is what the `runCatching` is for.
 */
private fun Context.displayRotationDegrees(): Int {
    val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { display.rotation }.getOrNull()
    } else {
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation
    }
    return when (rotation) {
        Surface.ROTATION_90 -> QUARTER_TURN_DEGREES
        Surface.ROTATION_180 -> HALF_TURN_DEGREES
        Surface.ROTATION_270 -> THREE_QUARTER_TURN_DEGREES
        else -> 0
    }
}

private const val QUARTER_TURN_DEGREES = 90
private const val HALF_TURN_DEGREES = 180
private const val THREE_QUARTER_TURN_DEGREES = 270

internal const val TAG_PICTURE_TAP = "call-video-picture-tap"
internal const val TAG_VIDEO = "call-video"
internal const val TAG_REMOTE = "call-video-remote"
internal const val TAG_PREVIEW = "call-video-preview"
internal const val TAG_PREVIEW_PICTURE = "call-video-preview-picture"
