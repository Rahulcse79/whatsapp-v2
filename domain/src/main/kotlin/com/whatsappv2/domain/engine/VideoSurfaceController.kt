package com.whatsappv2.domain.engine

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
}

/** The controller for a context with no stack. Draws nothing and holds nothing. */
object NoVideoSurfaces : VideoSurfaceController {
    override fun attach(remoteView: Any?, localPreview: Any?) = Unit
    override fun detach() = Unit
}
