package com.whatsappv2.feature.calls

import com.whatsappv2.domain.engine.VideoSize
import kotlin.math.roundToInt

/**
 * Fitting a video stream into the space on screen (Task 52, DoD 9).
 *
 * ## The two bugs this prevents
 *
 * **Stretching.** A remote stream is whatever the far end sends, and it changes mid-call —
 * a peer that rotates, or that drops resolution when its network degrades, sends a
 * differently-shaped frame with no warning. A view sized to the *first* frame and left
 * there shows every subsequent one stretched. Task 52's third done-when is precisely this,
 * and the fix is that the size is recomputed from the current frame rather than stored.
 *
 * **Rotation.** Device rotation and stream rotation are different things and both happen.
 * The stream carries its own orientation; the phone has another. [fit] takes the
 * already-oriented frame so the two are combined in one place — [orient] — rather than at
 * each call site, which is where they get combined twice or not at all.
 *
 * Pure integer arithmetic, so both are asserted in a JVM test. An instrumented test can
 * tell you a view was laid out; it cannot easily tell you the picture inside it was the
 * wrong shape.
 */
object VideoLayout {

    /**
     * The largest box with [frame]'s aspect ratio that fits inside [available].
     *
     * Letterboxing: the whole picture is on screen and the leftover is bars. This is the
     * right answer for a **conference**, where the picture is a grid of people and
     * cropping it cuts somebody's tile in half. [cover] is the right answer for a 1:1
     * call. Neither is the right answer for "always", which is why there are two.
     *
     * Returns [available] unchanged when the frame size is not known yet: the view is
     * about to be filled by the first decoded frame, and shrinking it to nothing in the
     * meantime is a visible flicker at the start of every call.
     */
    fun fit(frame: VideoSize, available: VideoSize): VideoSize {
        if (!frame.isKnown || !available.isKnown) return available

        // Integer arithmetic, cross-multiplied rather than divided: the float version of
        // this rounds two ways and produces a one-pixel bar on one edge at some sizes.
        val fitToWidth = frame.height.toLong() * available.width <= frame.width.toLong() * available.height

        return if (fitToWidth) {
            // The frame is relatively wider, so width is the binding constraint.
            VideoSize(available.width, (frame.height.toLong() * available.width / frame.width).toInt())
        } else {
            VideoSize((frame.width.toLong() * available.height / frame.height).toInt(), available.height)
        }
    }

    /**
     * The smallest box with [frame]'s aspect ratio that **covers** [available].
     *
     * The other half of the pair: the picture fills the space edge to edge and whatever
     * does not fit is outside the bounds, to be clipped by the caller. No bars, no
     * distortion, and some of the frame is not on screen.
     *
     * This is what a one-to-one video call should do, and what WhatsApp does — a face
     * shot has its subject in the middle and its edges to spare, and a black frame
     * around it on a phone held upright reads as a fault. It is the wrong choice for a
     * composed conference grid, where every pixel is somebody.
     *
     * The returned size is at least as large as [available] in both axes, so a caller
     * must clip — and must be drawing into something that *can* be clipped. A `SurfaceView`
     * this size draws over its neighbours: it is on its own layer and no ancestor's bounds
     * reach it. See [previewPicture] for the day that mattered.
     */
    fun cover(frame: VideoSize, available: VideoSize): VideoSize {
        if (!frame.isKnown || !available.isKnown) return available

        // The mirror of [fit]: there, width binds when the frame is relatively wider.
        // Here it binds when the frame is relatively *narrower*, because the goal is the
        // larger scale factor rather than the smaller one.
        val fitToWidth = frame.height.toLong() * available.width >= frame.width.toLong() * available.height

        return if (fitToWidth) {
            VideoSize(available.width, (frame.height.toLong() * available.width / frame.width).toInt())
        } else {
            VideoSize((frame.width.toLong() * available.height / frame.height).toInt(), available.height)
        }
    }

    /**
     * [frame] as it should appear after [rotationDegrees] of display rotation.
     *
     * A quarter or three-quarter turn swaps the axes; a half turn does not. Anything that
     * is not a multiple of 90 is not something a display rotation produces, and is
     * returned unchanged rather than guessed at.
     */
    fun orient(frame: VideoSize, rotationDegrees: Int): VideoSize {
        val normalised = ((rotationDegrees % FULL_TURN) + FULL_TURN) % FULL_TURN
        return if (normalised == QUARTER_TURN || normalised == THREE_QUARTER_TURN) {
            frame.transposed()
        } else {
            frame
        }
    }

    /**
     * The self-view's box: a square, at the size the user chose.
     *
     * ## Square, and nothing from the camera
     *
     * [scale] and [minimised] set the width and the height is the same, whatever the
     * camera is producing. The picture is then scaled to **cover** the square and the
     * overflow cropped — see [previewPicture] — so the box is full of camera at every
     * frame and there is no black bar to explain away.
     *
     * The box used to take the frame's shape, and a camera's shape changes: a peer
     * rotating, a stream dropping resolution under load, or this handset joining a
     * conference all re-shape the local frame, and the self-view then grew, shrank and
     * shifted around the corner it was pinned to. Pinned by only two of its edges, a box
     * whose other two move reads as sliding about the screen. A square takes nothing from
     * the camera, so nothing the camera does can move it.
     *
     * It was a fixed 3:4 for a while, with the frame fitted inside — and on a handset whose
     * camera reports a landscape frame that meant a portrait box holding a wide strip of
     * picture with roughly half the self-view black. Covering a square gives up the edges
     * of a landscape frame instead, which for a self-view — a face in the middle, room to
     * spare either side — is the right thing to give up.
     *
     * [minimised] ignores [scale] rather than clamping it: minimising jumps to the smallest
     * size — half the largest, the same floor the grip stops at and the size the box opens
     * at — and the size the user had chosen is still there when they restore it.
     *
     * A fraction of the **shorter** edge rather than a dp constant, so the self-view is the
     * same relative size on a phone and a tablet and does not become a stripe down a
     * landscape screen — or a fraction of what [PREVIEW_MAX_HEIGHT_FRACTION] leaves of that
     * edge on a squat screen, where a legal *width* would be an illegal *height*. See
     * [previewBasis].
     */
    fun previewBox(
        available: VideoSize,
        scale: Float = 1f,
        minimised: Boolean = false,
    ): VideoSize {
        if (!available.isKnown) return VideoSize.UNKNOWN
        val fraction = if (minimised) PREVIEW_MINIMISED_FRACTION else fractionFor(scale)
        val side = (previewBasis(available) * fraction).roundToInt().coerceAtLeast(1)
        return VideoSize(side, side)
    }

    /**
     * The scale that gives the self-view a box [widthPx] wide, clamped to what is usable.
     *
     * The inverse of [previewBox], and the reason resizing is a pure function rather than
     * arithmetic in a gesture handler: "too small to use" and "big enough to cover the
     * people you are talking to" are the two ways a resizable preview goes wrong, and both
     * are decided here, once, where a JVM test can enumerate them.
     *
     * Clamping the *scale* rather than the pixel width is what keeps the bound meaningful
     * across displays. A 200 px floor is a third of a small phone and a tenth of a tablet;
     * a fraction of the shorter edge is the same preview on both, and survives a rotation
     * without the box changing size under the user.
     */
    fun previewScaleForWidth(widthPx: Int, available: VideoSize): Float {
        if (!available.isKnown) return 1f
        val wanted = widthPx / previewBasis(available) / PREVIEW_DEFAULT_FRACTION
        return wanted.coerceIn(PREVIEW_MIN_SCALE, PREVIEW_MAX_SCALE)
    }

    /**
     * The edge the self-view's fractions are taken of: the shorter one, or as much of it as
     * [PREVIEW_MAX_HEIGHT_FRACTION] leaves room for.
     *
     * The ceiling **scales the whole range** rather than capping its top. Capping is what it
     * used to do, and it broke the range in two ways. On a landscape screen it capped the
     * default and the largest size to the same box, leaving the resize gesture some fifty
     * pixels of travel — a ceiling that collapses the range it is meant to cap. And on an
     * ordinary 16:9 phone it capped only the largest, so the smallest — a fraction of the
     * width — was no longer half of it, which is the rule [PREVIEW_MIN_FRACTION] states.
     * Scaling keeps every size in the same proportion to every other, on every screen.
     */
    private fun previewBasis(available: VideoSize): Float {
        val short = minOf(available.width, available.height).toFloat()
        val tallest = available.height * PREVIEW_MAX_HEIGHT_FRACTION / PREVIEW_MAX_FRACTION
        return minOf(short, tallest).coerceAtLeast(1f)
    }

    /** [scale]'s share of the shorter edge, never outside the usable range. */
    private fun fractionFor(scale: Float): Float =
        (PREVIEW_DEFAULT_FRACTION * scale).coerceIn(PREVIEW_MIN_FRACTION, PREVIEW_MAX_FRACTION)

    /**
     * The camera's picture over [previewBox]: never distorted, never a bar, always cropped.
     *
     * [cover], so the square is full of camera whatever shape the frame is — a 16:9 frame
     * loses a strip off each side, a portrait one loses top and bottom, and a face in the
     * middle keeps everything that matters. The alternative, [fit], leaves a quarter of the
     * square black above and below a landscape frame, which is what this replaced.
     *
     * ## The crop is the caller's, and the view type is what makes it possible
     *
     * The returned size is larger than the box, and cutting off the excess is something only
     * a view that draws through the hierarchy can have done to it. This was briefly a
     * `SurfaceView` inside a `clipChildren` container, and on a handset the camera drew
     * straight across the whole screen (2026-09-17): a `SurfaceView` does not draw into its
     * parent's canvas — it punches a hole and SurfaceFlinger composites its surface at the
     * view's own bounds, so `clipChildren` had nothing to clip and a Compose `clip` did not
     * reach it either. The self-view is a `TextureView` now, for exactly this reason
     * ([CallVideo]), and `SelfPreview`'s container crops it like any other view.
     */
    fun previewPicture(frame: VideoSize, box: VideoSize): VideoSize = cover(frame, box)

    private const val FULL_TURN = 360
    private const val QUARTER_TURN = 90
    private const val THREE_QUARTER_TURN = 270

    /**
     * The largest, likewise.
     *
     * The physical ceiling, and it is worth writing down why it is not larger.
     *
     * The preview parks in a corner with [com.whatsappv2.core.designsystem.theme.Spacing]'s
     * large margin on each side. On a 720px-wide handset that leaves **656px**, or 0.911 of
     * the short edge, for the box itself — so 0.90 is as wide as a corner-parked preview can
     * be drawn at all. Asking for twice this would be 1296px on a 720px screen: not a
     * policy this file can relax, a rectangle that does not fit on the display.
     *
     * A self-view genuinely larger than this is not a bigger preview, it is a different
     * feature — swapping the self-view and the remote picture, so the camera is the
     * full-screen layer and the conference becomes the floating one. That is a change to
     * [CallVideo]'s two surfaces, not to this constant.
     *
     * What also has to be prevented is the self-view covering the composed grid — on a
     * four-way call the grid is the other three people — and that is an **area** question,
     * not a width one. [PREVIEW_MAX_HEIGHT_FRACTION] binds alongside this, and the test
     * asserts against area across four camera shapes rather than against width.
     */
    private const val PREVIEW_MAX_FRACTION = 0.90f

    /**
     * The smallest the self-view may be resized to: **half the largest**.
     *
     * A ratio rather than a share of its own, because that is the rule (2026-09-17): the
     * resize gesture runs from the ceiling down to half of it, whatever the ceiling is. Tied
     * to [PREVIEW_MAX_FRACTION] in code so the two cannot drift apart when one is tuned —
     * the range walked upwards over several passes before this, and a floor that had been
     * set by hand was left where the previous ceiling had been.
     *
     * It is also what minimising gives in one tap — see [PREVIEW_MINIMISED_FRACTION].
     */
    private const val PREVIEW_MIN_FRACTION = PREVIEW_MAX_FRACTION / 2

    /**
     * Minimised is the smallest size — half the largest — reached in one tap.
     *
     * It was an eleventh of the short edge, a thumbnail, until 2026-09-17: the rule now is
     * that nothing about the self-view goes below half the largest, minimised included.
     * The same constant as the grip's floor, so the button and the gesture cannot disagree
     * about what "as small as it goes" means.
     */
    private const val PREVIEW_MINIMISED_FRACTION = PREVIEW_MIN_FRACTION

    /**
     * The size a fresh self-view opens at: the floor. The grip only grows from it.
     *
     * The whole range now hangs off one number, [PREVIEW_MAX_FRACTION]: the floor is half
     * of it, and the default and the minimised size are both the floor (2026-09-17). It
     * used to sit in the upper half of the range, after several passes in which every
     * default was too small to check you are in frame — but "too small" was a floor of an
     * eleventh of the screen, and a floor of half the largest is not a thumbnail.
     *
     * The controls on it are sized from the box rather than from a constant
     * ([SelfPreview]'s `CONTROL_SHARE`), so this can move without them going out of
     * proportion in either direction.
     */
    private const val PREVIEW_DEFAULT_FRACTION = PREVIEW_MIN_FRACTION

    /**
     * The tallest the self-view may be, as a share of the available height.
     *
     * For a square box this is the bound that stops a legal *width* from being an illegal
     * *height* — on a short screen, or in landscape, a box as wide as the width rule allows
     * would be taller than the display.
     *
     * Half the height rather than the 0.40 it was: at 0.40 the ceiling bound the box before
     * the width fraction did on an ordinary 16:9 phone, so the default and the maximum came
     * out the same size and the top half of the resize gesture did nothing. A ceiling that
     * silently collapses the range it is meant to cap is worse than no ceiling — which is
     * also why it no longer caps at all but scales the range down whole; see
     * [previewBasis].
     */
    private const val PREVIEW_MAX_HEIGHT_FRACTION = 0.50f

    /** The resize bounds as the multiplier the UI actually carries. See [previewBox]. */
    const val PREVIEW_MIN_SCALE = PREVIEW_MIN_FRACTION / PREVIEW_DEFAULT_FRACTION
    const val PREVIEW_MAX_SCALE = PREVIEW_MAX_FRACTION / PREVIEW_DEFAULT_FRACTION
}
