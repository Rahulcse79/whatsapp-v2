package com.whatsappv2.feature.calls

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/**
 * Where the self-view sits, how big it is, and whether it is minimised.
 *
 * ## Why this is a value and not three `remember`s in the composable
 *
 * Because the three are one decision and they constrain each other. A scale is only
 * meaningful against a corner — the box grows away from the edges it is pinned to — and
 * minimising has to leave the scale alone so the size the user chose is still there when
 * they come back. Three separate pieces of state get out of step in exactly those places,
 * and none of it is visible to a test while it lives inside a composable.
 *
 * Pulled out here, the rules are four one-line functions and a JVM test can enumerate
 * them, which is what §1.3 asks of anything that can be decided without a handset.
 *
 * ## What survives, and what deliberately does not
 *
 * The corner, the scale and the minimised flag survive a rotation and process death; the
 * *pixel offset* does not exist to be saved. See [PreviewCorner] — a position restored
 * into a display that has since changed shape puts the preview off the edge of the screen,
 * whereas a corner means the same thing at any size. The scale is a fraction of the
 * shorter edge for the same reason.
 */
internal data class SelfPreviewPlacement(
    val corner: PreviewCorner = PreviewCorner.BottomEnd,

    /** A multiplier on the default box, within [VideoLayout.PREVIEW_MIN_SCALE]..MAX. */
    val scale: Float = 1f,

    /** Compact, and still on screen. Never a reason to stop the camera — see [SelfPreview]. */
    val isMinimised: Boolean = false,
) {

    /** Parked in [corner], which is where a drag leaves it. */
    fun movedTo(corner: PreviewCorner): SelfPreviewPlacement = copy(corner = corner)

    /**
     * Resized to [scale], clamped.
     *
     * Clamping here rather than at the gesture means a drag that runs off the end of the
     * range stops growing the box instead of storing a number the layout will silently
     * ignore — so letting go and dragging back in again moves it immediately, rather than
     * first having to undo the overshoot.
     */
    fun resizedTo(scale: Float): SelfPreviewPlacement =
        copy(scale = scale.coerceIn(VideoLayout.PREVIEW_MIN_SCALE, VideoLayout.PREVIEW_MAX_SCALE))

    /** Minimised, or restored to the size it had. */
    fun toggledMinimised(): SelfPreviewPlacement = copy(isMinimised = !isMinimised)

    companion object {
        /**
         * Three primitives, because that is all there is.
         *
         * A `listSaver` rather than an autosaver: the enum is not parcelable, and storing
         * its `name` is both stable across a rebuild and readable in a saved-state dump.
         * An unknown name — a value removed in a later version — falls back to the default
         * corner rather than throwing while the screen is being restored.
         */
        val Saver: Saver<SelfPreviewPlacement, Any> = listSaver(
            save = { listOf(it.corner.name, it.scale, it.isMinimised) },
            restore = { saved ->
                SelfPreviewPlacement(
                    corner = PreviewCorner.entries.firstOrNull { it.name == saved[0] }
                        ?: PreviewCorner.BottomEnd,
                    scale = saved[1] as Float,
                    isMinimised = saved[2] as Boolean,
                )
            },
        )
    }
}
