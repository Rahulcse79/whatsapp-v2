package com.whatsappv2.feature.calls

import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset

/**
 * Which corner the self-view is parked in.
 *
 * An enum rather than a raw offset because that is what actually survives: a pixel offset
 * saved across a rotation describes a screen that no longer exists, and restoring it puts
 * the preview off the edge of a landscape display. A corner is meaningful at any size, so
 * the position is recomputed from the corner on every layout and the corner is the only
 * thing worth remembering.
 */
internal enum class PreviewCorner {
    TopStart,
    TopEnd,
    BottomStart,

    /** The default, and where WhatsApp puts it. */
    BottomEnd,
    ;

    /** This corner, as the alignment that puts a child in it. */
    val alignment: Alignment
        get() = when (this) {
            TopStart -> Alignment.TopStart
            TopEnd -> Alignment.TopEnd
            BottomStart -> Alignment.BottomStart
            BottomEnd -> Alignment.BottomEnd
        }

    /**
     * Where the resize grip sits: the corner of the box facing the middle of the screen.
     *
     * Always the diagonal opposite of the parked corner, and that is the whole reason it
     * moves. The box grows away from the corner it is pinned to — the two pinned edges
     * cannot move — so the grip has to be on the edges that can, or dragging it would ask
     * the preview to grow in a direction it is not free to grow in.
     */
    val resizeGrip: Alignment
        get() = when (this) {
            TopStart -> Alignment.BottomEnd
            TopEnd -> Alignment.BottomStart
            BottomStart -> Alignment.TopEnd
            BottomEnd -> Alignment.TopStart
        }

    /**
     * Which way a drag on [resizeGrip] has to go to make the box bigger, per axis.
     *
     * `+1` means "a positive delta grows it", `-1` the opposite. Parked bottom-right the
     * grip is top-left, so growing means dragging up and to the left and both are `-1`.
     * Getting this backwards is the resize that shrinks, which is the sort of thing that
     * is obvious on a handset and invisible in a code review — hence a value, and a test.
     */
    val growth: Offset
        get() = when (this) {
            TopStart -> Offset(1f, 1f)
            TopEnd -> Offset(-1f, 1f)
            BottomStart -> Offset(1f, -1f)
            BottomEnd -> Offset(-1f, -1f)
        }

    companion object {
        /** The corner nearest to [centre] within a [width] x [height] area. */
        fun nearest(centre: Offset, width: Float, height: Float): PreviewCorner {
            val left = centre.x < width / 2
            val top = centre.y < height / 2
            return when {
                top && left -> TopStart
                top -> TopEnd
                left -> BottomStart
                else -> BottomEnd
            }
        }
    }
}
