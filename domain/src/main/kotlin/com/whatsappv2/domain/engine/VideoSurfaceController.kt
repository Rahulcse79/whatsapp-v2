package com.whatsappv2.domain.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where video is drawn (Task 52, §5.2).
 *
 * ## `Any?`, and why that is not laziness
 *
 * `:domain` may not import Android (DoD 2), and the thing a SIP stack draws into is an
 * Android `TextureView` or `SurfaceView`. A port typed on the view class cannot live here;
 * one typed on a bespoke wrapper would be a wrapper whose only content is the view.
 *
 * So the surface crosses this boundary opaque. Nothing in `:domain` or `:feature:calls`
 * does anything with it except pass it along, which is exactly what an opaque handle is
 * for — and `:data:sip`, the one module allowed to know what the stack wants, casts it
 * back. PJSIP's own API takes `Object` here for the same reason.
 *
 * ## Detaching is the part that matters
 *
 * A surface handed to a native stack and never taken back is a texture it keeps writing
 * into after the screen has gone — a leak of every frame of the call, and on some devices
 * a crash. [detach] exists so a composable can give the view back in its `onDispose`,
 * which is the one place that reliably runs.
 */
interface VideoSurfaceController {

    /**
     * Draws remote video into [remoteView] and the local preview into [localPreview].
     *
     * Either may be null — a call showing only the far end passes null for the preview.
     * Both are set together because they are released together.
     */
    fun attach(remoteView: Any?, localPreview: Any?)

    /** Gives both surfaces back. Safe to call when nothing was ever attached. */
    fun detach()

    /**
     * Which way up the screen is, as the display's rotation in degrees clockwise from the
     * device's natural orientation: 0, 90, 180 or 270.
     *
     * The camera delivers frames in its own fixed orientation — landscape on every phone —
     * and the stack sends and previews exactly what it is given. A portrait call therefore
     * showed both pictures lying on their side (TC15, 2026-09-11). The stack can rotate
     * captured frames before encoding, and this is the one input it needs to know by how
     * much; it belongs here with the surfaces because it comes from the same screen.
     */
    fun setDisplayRotation(degrees: Int)

    /**
     * The shapes of the two pictures on screen, or [VideoSizes.UNKNOWN].
     *
     * ## Why the renderer cannot be trusted to do this
     *
     * PJSIP draws a frame onto a full-screen quad with fixed texture coordinates
     * (`opengl_dev.c:271`) and sets the viewport to the whole surface. There is no
     * aspect-ratio correction anywhere in that path: whatever arrives is *stretched* to
     * the bounds of the view it is given. A 352x288 CIF frame in a portrait
     * `SurfaceView` is a face half again as tall as it should be, which is exactly what
     * three TC15s showed on 2026-09-14.
     *
     * The fix is to give the renderer bounds of the right shape, and that needs this
     * number. It is a property of the stream and not of any call: it changes when the
     * peer rotates, when a conference bridge reflows its canvas, or when either end
     * drops resolution under load, none of which are call-state transitions.
     *
     * The local half is the same problem seen in the corner: the self-view is a
     * preview window drawn by the same renderer, so a square sensor frame in a 16:9
     * thumbnail is a squashed face there too.
     *
     * [VideoSizes.UNKNOWN] until the first frame is decoded, and again once the stream
     * goes away — a caller must draw something sensible for it rather than dividing by
     * a zero axis.
     */
    val videoSizes: StateFlow<VideoSizes>
}

/** The controller for a context with no stack. Draws nothing and holds nothing. */
object NoVideoSurfaces : VideoSurfaceController {
    override fun attach(remoteView: Any?, localPreview: Any?) = Unit
    override fun detach() = Unit
    override fun setDisplayRotation(degrees: Int) = Unit
    override val videoSizes: StateFlow<VideoSizes> = MutableStateFlow(VideoSizes.UNKNOWN)
}
