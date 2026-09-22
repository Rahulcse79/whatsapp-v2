package com.whatsappv2.feature.calls

import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import com.whatsappv2.core.designsystem.theme.AppTheme
import com.whatsappv2.domain.engine.VideoSize
import kotlin.math.roundToInt

/**
 * This device's own camera, as a floating picture the user can move, resize and put away.
 *
 * ## Why it floats rather than sitting in the grid
 *
 * The conference picture is composed by the bridge (ADR-003) and arrives as **one** video
 * stream, so the tiles in it are the server's arrangement and this app cannot add to them
 * — a four-way call is the `wa-4` layout's 2x2, decided by FreeSWITCH. That is not a
 * limitation worth fighting: the camera is already running locally, so drawing it here
 * costs no bandwidth, no decode and no round trip, and it stays *this* device's picture
 * rather than a tile in somebody else's composition.
 *
 * It is drawn over the remote surface, never into it. The camera view is a `TextureView`
 * — an ordinary view, drawn through the hierarchy above the hole the remote `SurfaceView`
 * punches, and cropped by its parent like any other view; [CallVideo] says why it cannot
 * be a `SurfaceView`. [CallScreen] lifts the whole video layer above its chrome-toggle
 * overlay, without which nothing here would receive a pointer at all.
 *
 * ## Dragging, and why it snaps
 *
 * It snaps to a corner rather than staying where it is dropped. A picture-in-picture left
 * in the middle of a conference covers a face; the four corners are the positions that do
 * not, and snapping makes "get this out of my way" one flick rather than a careful
 * placement. Only the corner is remembered — see [PreviewCorner] and
 * [SelfPreviewPlacement].
 *
 * The whole thing is laid out inside `systemBarsPadding`, and the two bottom corners are
 * inset by `Sizing.videoPreviewControlsInset` as well, so neither the status bar, the
 * navigation bar nor the End button can end up underneath it on any screen.
 *
 * ## Size changes are discrete
 *
 * Resizing shows an outline that follows the finger and commits **once**, on release;
 * minimising and restoring are likewise a single step. This was a media decision when the
 * camera was a `SurfaceView`: every layout pass that changed its bounds fired
 * `surfaceChanged`, [CallVideo] handed the surface back to the stack, and the stack posted
 * to the one PJSIP thread and rebuilt the preview window — sixty times a second for the
 * length of a drag. A `TextureView`'s texture is the same object at every size, so a
 * resize is now a layout and nothing else reaches the stack (see
 * `surfaceTextureListener` in [CallVideo]). The discrete commit stays because it is the
 * better gesture regardless: the picture holds still while the outline shows where it will
 * go, and the size the user gets is the one they let go at.
 *
 * ## A tap anywhere else puts a maximised preview away
 *
 * Maximised, the self-view is nine tenths of the screen's shorter edge — on a conference
 * it sits over somebody's tile. The way out used to be the small glyph in its corner, or
 * a double tap on the picture itself; a tap on the *rest* of the screen only hid the call
 * controls. It minimises the preview now (asked for on 2026-09-21), the way a tap outside
 * any sheet dismisses it, and does nothing else on that tap: one gesture, one effect. The
 * next tap is the chrome's again. The tap lands on `CallVideo`'s picture-tap target, which
 * is why [state] is hoisted there rather than kept here.
 *
 * ## It steps aside for the controls
 *
 * The bottom corners are inset by `Sizing.videoPreviewControlsInset`, which clears the
 * End button and nothing else — and the in-call chrome is three rows of controls above
 * that button. While the chrome was up, a preview parked in a bottom corner sat behind
 * Add and Transfer, with the Transfer button drawn across the middle of the camera
 * (TC15, 2026-09-22). The buttons win the touch, deliberately, so nothing was unreachable;
 * it just looked broken, and the picture it exists to show was covered. So the screen
 * tells the preview how tall its controls are ([bottomClearance]) while they are
 * visible, and the bottom corners park above them; when the chrome fades the clearance
 * goes back to the token and the card glides down. Only when it fits: a maximised card is
 * taller than the space above the controls, and pushing it up against the title would
 * cover the name of the person the call is with, so that one stays where it was.
 *
 * @param state the preview's placement, owned by the caller so a tap outside the preview
 *   can reach it. [rememberSelfPreviewState] is the one way to make one.
 * @param bottomClearance how much of the bottom edge, in pixels, the in-call controls
 *   currently occupy — zero while they are hidden. See above.
 */
@Composable
internal fun SelfPreview(
    previewView: TextureView,
    localFrame: VideoSize,
    modifier: Modifier = Modifier,
    state: SelfPreviewState = rememberSelfPreviewState(),
    bottomClearance: Int = 0,
) {
    // Insets applied here rather than to the offsets, so the corner arithmetic below works
    // in a coordinate space that already excludes the status and navigation bars. A corner
    // computed against the raw display would park the preview under the notch.
    BoxWithConstraints(modifier = modifier.fillMaxSize().systemBarsPadding()) {
        val density = LocalDensity.current
        val area = VideoSize(
            width = constraints.maxWidth.takeIf { it != Constraints.Infinity } ?: 0,
            height = constraints.maxHeight.takeIf { it != Constraints.Infinity } ?: 0,
        )
        if (!area.isKnown) return@BoxWithConstraints

        val placement = state.placement

        val margin = with(density) { AppTheme.spacing.large.toPx() }
        val box = VideoLayout.previewBox(area, placement.scale, placement.isMinimised)
        val controlsInset = controlsInsetFor(
            box = box,
            area = area,
            margin = margin,
            token = with(density) { AppTheme.sizing.videoPreviewControlsInset.toPx() },
            clearance = bottomClearance.toFloat(),
        )
        val parked = placement.corner.offsetIn(area, box, margin, controlsInset)

        // Animated only when parked: following the finger through a spring would lag the
        // drag behind the touch, which reads as the preview being slow rather than smooth.
        val resting by animateIntOffsetAsState(parked, label = "self-preview-corner")
        val drag = state.drag
        val position = if (drag == Offset.Zero) resting else draggedPosition(parked, drag, area, box)

        Box(
            modifier = Modifier
                .offset { position }
                .previewSize(box, density)
                .previewSkin()
                .semantics { contentDescription = describe(placement) }
                .testTag(TAG_PREVIEW),
            contentAlignment = Alignment.Center,
        ) {
            PreviewContents(previewView, localFrame, box, state, area, density, parked)
        }

        ResizeOutline(state.proposedWidth, area, placement.corner, margin, controlsInset, density)
    }
}

/** The self-view's state, remembered across rotation and process death. */
@Composable
internal fun rememberSelfPreviewState(): SelfPreviewState =
    rememberSaveable(saver = SelfPreviewState.Saver) { SelfPreviewState() }

/**
 * The self-view's live state: the part that is saved, and the part that lasts one gesture.
 *
 * One holder rather than three `remember`s so the gesture helpers below take it instead of
 * a handful of getters and setters. It also puts the distinction in one place: [placement]
 * is what the user chose and survives a rotation, while [drag] and [proposedWidth] describe
 * a finger that is still down and are meaningless the moment it lifts — which is why a
 * cancelled gesture can simply clear them and leave the preview in a corner.
 *
 * Internal rather than private since the tap-outside rule: `CallVideo` owns the target
 * that tap lands on, and it has to be able to ask whether the preview is maximised and to
 * put it away.
 */
@Stable
internal class SelfPreviewState(placement: SelfPreviewPlacement = SelfPreviewPlacement()) {

    /** Corner, size and minimised state. Saved. */
    var placement: SelfPreviewPlacement by mutableStateOf(placement)

    /** True while the preview is at the size the user chose rather than the small card. */
    val isMaximised: Boolean get() = !placement.isMinimised

    /** Puts the preview away as the small card; the size the user chose is kept for restoring. */
    fun minimise() {
        if (isMaximised) placement = placement.toggledMinimised()
    }

    /** How far the finger has moved since it went down, and nothing more. */
    var drag: Offset by mutableStateOf(Offset.Zero)

    /** The width the resize grip is proposing, or null when nobody is resizing. */
    var proposedWidth: Int? by mutableStateOf(null)

    /** Takes the proposed size, or discards it when the gesture was cancelled. */
    fun endResize(commit: Boolean, area: VideoSize) {
        val width = proposedWidth
        proposedWidth = null
        if (commit && width != null) {
            placement = placement.resizedTo(VideoLayout.previewScaleForWidth(width, area))
        }
    }

    companion object {
        /** Only [placement] is worth keeping; a half-finished gesture is not. */
        val Saver: Saver<SelfPreviewState, Any> = Saver(
            save = { with(SelfPreviewPlacement.Saver) { save(it.placement) } },
            restore = { saved ->
                SelfPreviewState(SelfPreviewPlacement.Saver.restore(saved) ?: SelfPreviewPlacement())
            },
        )
    }
}

/**
 * The camera, the minimise control and the resize grip, inside the preview's box.
 *
 * Split out so [SelfPreview] is the placement and this is the contents. They change for
 * entirely different reasons and neither is easier to read next to the other.
 */
@Composable
private fun BoxScope.PreviewContents(
    previewView: TextureView,
    localFrame: VideoSize,
    box: VideoSize,
    state: SelfPreviewState,
    area: VideoSize,
    density: Density,
    parked: IntOffset,
) {
    val placement = state.placement

    // The controls are sized from the box, not from a constant, and that is what lets the
    // box get small. A fixed 16dp glyph inside 8dp of padding is 32dp of control; in a
    // preview only a little taller than that, it covers the picture it is meant to sit on.
    // A share of the box instead, held between the two design-system sizes so it can
    // neither vanish on a minimised preview nor grow past the size it was drawn for.
    //
    // The box's **narrower** edge, which since the box became 9:16 portrait is its width.
    // It was the height, and against a portrait box that is the wrong edge: the height is
    // 1.78x the width, so a share of it puts a control on the picture wider than the space
    // beside it and two of them meet in the middle. The narrow edge is the one the
    // controls have to fit across.
    val control = with(density) { (minOf(box.width, box.height) * CONTROL_SHARE).toDp() }
        .coerceIn(AppTheme.sizing.videoPreviewControlMinimised, AppTheme.sizing.videoPreviewControl)
    // The camera, scaled to cover the 9:16 box and cropped to it by the container.
    //
    // The crop is real only because the camera is a `TextureView`. A `SurfaceView` in this
    // container drew straight across the screen (2026-09-17): it composites on its own
    // layer at its own bounds, and neither this `clipChildren` nor a Compose `clip` reaches
    // that layer. A `TextureView` draws into its parent's canvas like any other view, so
    // the `FrameLayout` cuts the overflow off where the box ends — and the box's own rounded
    // clip in `previewSkin` rounds the picture's corners with it.
    //
    // PJSIP's renderer stretches every frame to the bounds it is given (`opengl_dev.c`), so
    // the *view* is still sized in the camera's own proportion. It is only the visible
    // window onto it that is 9:16.
    val picture = VideoLayout.previewPicture(localFrame, box)
    //
    // The `TextureView` outlives this container. It is remembered by `CallVideo` so the
    // camera's texture survives the preview being resized, moved or put away, and this
    // composable — everything below the outer `BoxWithConstraints` — is thrown away and
    // built again whenever that layout re-subcomposes: a window that measures at zero for
    // a frame while the call screen is brought back from behind the launcher, a preview
    // hidden and shown again. Compose disposes the old `FrameLayout` without taking the
    // child out of it, so the next factory found a `TextureView` that still had a parent
    // and the process died in `addView` — "The specified child already has a parent",
    // TC15, 2026-09-22 14:03, the call with it. [adopt] takes the view from wherever it
    // was, and [onRelease] hands it back so the next container starts clean.
    AndroidView(
        factory = { context ->
            FrameLayout(context).apply {
                clipChildren = true
                clipToPadding = true
                adopt(previewView)
            }
        },
        update = { frame ->
            frame.adopt(previewView)
            val params = previewView.layoutParams as FrameLayout.LayoutParams
            // Centred, so a 16:9 frame loses the same amount from each side rather than
            // being cropped entirely off one of them.
            params.width = picture.width
            params.height = picture.height
            params.gravity = Gravity.CENTER
            previewView.layoutParams = params
            frame.requestLayout()
        },
        onRelease = { frame -> frame.removeView(previewView) },
        modifier = Modifier
            .previewSize(box, density)
            .testTag(TAG_PREVIEW_PICTURE),
    )

    // The gesture surface, and it has to be *here* — a sibling laid over the camera rather
    // than a modifier on the box behind it.
    //
    // The camera is an `AndroidView` wrapping a `TextureView`, and Compose's interop
    // dispatches the pointer into that view before the enclosing box sees it. While the
    // preview was a fixed 3:4 with a landscape frame letterboxed inside it, that did not
    // show: the black bars above and below were ordinary Compose surface, so a drag that
    // happened to start in one worked and the gesture looked fine. Matching the box to the
    // camera removed the bars — and with them the only part of the preview that was
    // draggable. On a handset the preview simply would not move (2026-09-17).
    //
    // `matchParentSize` rather than `fillMaxSize`, so this measures against the box the
    // camera already sized rather than joining in and growing it.
    Box(
        modifier = Modifier
            .matchParentSize()
            .previewTaps(placement.isMinimised) { state.placement = placement.toggledMinimised() }
            .previewDrag(state, area, box, parked)
            .testTag(TAG_PREVIEW_SURFACE),
    )

    PreviewGlyph(
        icon = if (placement.isMinimised) Icons.Filled.OpenInFull else Icons.Filled.CloseFullscreen,
        description = if (placement.isMinimised) {
            "Restore your camera to its full size"
        } else {
            "Minimise your camera"
        },
        size = control,
        onClick = { state.placement = placement.toggledMinimised() },
        modifier = Modifier.align(placement.corner.alignment).testTag(TAG_PREVIEW_TOGGLE),
    )

    // No grip while minimised: minimised *is* the floor the grip stops at, so a grip could
    // only make it bigger — and "bigger" is what the restore glyph above already does, back
    // to the size the user chose.
    if (!placement.isMinimised) {
        ResizeGrip(
            size = control,
            onStart = { state.proposedWidth = box.width },
            onResize = { delta ->
                state.proposedWidth = state.proposedWidth.resizedBy(delta, box, placement.corner, area)
            },
            onEnd = { commit -> state.endResize(commit, area) },
            modifier = Modifier.align(placement.corner.resizeGrip).testTag(TAG_PREVIEW_GRIP),
        )
    }
}

/**
 * The size a resize drag is currently asking for, clamped.
 *
 * Clamped every step rather than at the end: a drag that runs past the limit and comes back
 * should move the edge immediately, not spend the return journey undoing an overshoot
 * nobody saw. The vertical component is converted to width through the box's own fixed
 * proportion, so a diagonal drag moves the edge at about the rate the finger is moving
 * rather than half of it.
 */
private fun Int?.resizedBy(
    delta: Offset,
    box: VideoSize,
    corner: PreviewCorner,
    area: VideoSize,
): Int {
    val ratio = box.width.toFloat() / box.height.coerceAtLeast(1)
    val growth = corner.growth
    val by = (growth.x * delta.x + growth.y * delta.y * ratio) / 2f
    val next = ((this ?: box.width) + by).roundToInt()
    return VideoLayout.previewBox(area, VideoLayout.previewScaleForWidth(next, area)).width
}

/**
 * The size being proposed while the grip is held.
 *
 * A sibling of the preview rather than a child, so the preview's own clip does not cut it
 * off, and parked in the same corner so it grows the way the box will.
 */
@Composable
private fun ResizeOutline(
    proposedWidth: Int?,
    area: VideoSize,
    corner: PreviewCorner,
    margin: Float,
    controlsInset: Float,
    density: Density,
) {
    val width = proposedWidth ?: return
    val outline = VideoLayout.previewBox(area, VideoLayout.previewScaleForWidth(width, area))
    Box(
        modifier = Modifier
            .offset { corner.offsetIn(area, outline, margin, controlsInset) }
            .previewSize(outline, density)
            .border(
                width = AppTheme.sizing.videoPreviewBorder,
                color = Color.White,
                shape = RoundedCornerShape(AppTheme.radius.medium),
            )
            .testTag(TAG_PREVIEW_OUTLINE),
    )
}

/**
 * The minimise / restore control.
 *
 * A real target rather than a bare icon: the drawn glyph is padded out on both sides of its
 * background, so the control is findable on a preview that may be a sixth of the screen's
 * width. It sits in the box's own outward corner — the one against the screen edge, least
 * likely to have a face in it — and the diagonal opposite of the resize grip, so the two
 * can never be hit for one another.
 */
@Composable
private fun PreviewGlyph(
    icon: ImageVector,
    description: String,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(AppTheme.spacing.extraSmall)
            .clip(RoundedCornerShape(AppTheme.radius.full))
            .background(Color.Black.copy(alpha = CONTROL_SCRIM))
            .clickable(onClick = onClick)
            .padding(AppTheme.spacing.extraSmall),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(size))
    }
}

/**
 * The corner grip that resizes the preview.
 *
 * Its own `pointerInput` rather than a mode on the parent's, because the two gestures are
 * told apart by *where the finger went down* and nothing else. A child consumes the down
 * before the parent sees it, so a drag that starts here resizes and a drag that starts
 * anywhere else moves — with no slop threshold, no long press and nothing to learn.
 */
@Composable
private fun ResizeGrip(
    size: Dp,
    onStart: () -> Unit,
    onResize: (Offset) -> Unit,
    onEnd: (commit: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(AppTheme.spacing.extraSmall)
            .clip(RoundedCornerShape(AppTheme.radius.full))
            .background(Color.Black.copy(alpha = CONTROL_SCRIM))
            .padding(AppTheme.spacing.extraSmall)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onStart() },
                    onDragEnd = { onEnd(true) },
                    onDragCancel = { onEnd(false) },
                    onDrag = { change, delta ->
                        change.consume()
                        onResize(delta)
                    },
                )
            }
            .semantics { contentDescription = "Drag to resize your camera" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.OpenInFull,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size),
        )
    }
}

/** The rounded, bordered black plate the camera is drawn on. */
@Composable
private fun Modifier.previewSkin(): Modifier = this
    .clip(RoundedCornerShape(AppTheme.radius.medium))
    .background(Color.Black)
    .border(
        width = AppTheme.sizing.videoPreviewBorder,
        color = Color.White.copy(alpha = BORDER_ALPHA),
        shape = RoundedCornerShape(AppTheme.radius.medium),
    )

/**
 * Tapping a minimised preview restores it, and a double tap toggles at any size.
 *
 * Shortcuts for the button, which is the control that is actually visible. It must come
 * **before** [previewDrag] in the chain, and that ordering is load-bearing: pointer events
 * reach the innermost `pointerInput` of a chain first, and `detectTapGestures` consumes the
 * down as soon as it gets it. With the two the other way round the tap detector swallowed
 * every gesture and the preview could not be dragged at all. In this order the drag sees
 * the down first, does not consume it until the touch slop is passed, and the tap is
 * cancelled by that movement — so a drag drags and a tap taps.
 */
private fun Modifier.previewTaps(isMinimised: Boolean, onToggle: () -> Unit): Modifier =
    pointerInput(isMinimised) {
        detectTapGestures(
            onDoubleTap = { onToggle() },
            onTap = { if (isMinimised) onToggle() },
        )
    }

/**
 * Moving the preview, and snapping it to the nearest corner when it is let go.
 *
 * The offset is read from [state] rather than passed in, because a gesture callback does not
 * see composition values change: it was built before the finger moved. Reading a captured
 * `position` here made every drop land on the corner it started from, so the preview sprang
 * back and could not be moved at all.
 *
 * Keyed on [corner] as well as the geometry for the same reason. [parked] *is* a captured
 * composition value, and without the corner in the keys this block would outlive the move
 * it just performed and measure the next drag from where the preview used to be.
 */
private fun Modifier.previewDrag(
    state: SelfPreviewState,
    area: VideoSize,
    box: VideoSize,
    parked: IntOffset,
): Modifier = pointerInput(area, box, state.placement.corner) {
    detectDragGestures(
        onDragEnd = {
            val dropped = draggedPosition(parked, state.drag, area, box)
            val centre = Offset(
                x = dropped.x + box.width / 2f,
                y = dropped.y + box.height / 2f,
            )
            val corner = PreviewCorner.nearest(centre, area.width.toFloat(), area.height.toFloat())
            state.placement = state.placement.movedTo(corner)
            state.drag = Offset.Zero
        },
        // A cancelled gesture is not a move. Without this a drag interrupted by an incoming
        // call would leave the offset non-zero and the preview stuck off-corner for the
        // rest of the call.
        onDragCancel = { state.drag = Offset.Zero },
        onDrag = { change, delta ->
            // Consumed, so the gesture cannot also reach the call controls underneath and
            // end the call on the way past.
            change.consume()
            state.drag += delta
        },
    )
}

/** What a screen reader is told the preview is, and what can be done with it. */
private fun describe(placement: SelfPreviewPlacement): String = if (placement.isMinimised) {
    "Your own camera, minimised. Double tap to restore it, or drag to move it."
} else {
    "Your own camera. Drag to move it to another corner, or drag the corner handle to resize it."
}

/**
 * Where a [box] parked at [parked] sits after being dragged by [drag], kept on screen.
 *
 * A function rather than an expression in the composable because two callers need the same
 * answer and one of them is a gesture callback — see [previewDrag]. Clamped to [area] so a
 * fast flick cannot throw the preview off the edge and leave nothing to drag back.
 */
private fun draggedPosition(parked: IntOffset, drag: Offset, area: VideoSize, box: VideoSize): IntOffset =
    IntOffset(
        x = (parked.x + drag.x).roundToInt().coerceIn(0, (area.width - box.width).coerceAtLeast(0)),
        y = (parked.y + drag.y).roundToInt().coerceIn(0, (area.height - box.height).coerceAtLeast(0)),
    )

/** Where this corner puts a [box] inside an [area]. */
private fun PreviewCorner.offsetIn(
    area: VideoSize,
    box: VideoSize,
    margin: Float,
    controlsInset: Float,
): IntOffset {
    val start = margin.roundToInt()
    val end = (area.width - box.width - margin).roundToInt().coerceAtLeast(start)
    val top = margin.roundToInt()

    // The bottom corners clear the call controls; the top ones only need the margin. Both
    // are clamped so a short screen cannot push the preview above its own top edge.
    val floor = (area.height - box.height).coerceAtLeast(top)
    val bottom = (area.height - box.height - controlsInset).roundToInt().coerceIn(top, floor)

    return when (this) {
        PreviewCorner.TopStart -> IntOffset(start, top)
        PreviewCorner.TopEnd -> IntOffset(end, top)
        PreviewCorner.BottomStart -> IntOffset(start, bottom)
        PreviewCorner.BottomEnd -> IntOffset(end, bottom)
    }
}

/**
 * Makes [child] this group's, taking it from whatever group had it.
 *
 * A view can have one parent, and `addView` on one that already has a parent is a crash
 * rather than a move. The preview's `TextureView` is shared across every container this
 * file ever builds for it — see the comment at the `AndroidView` — so the container that
 * wants it detaches it first. A no-op when it is already here, which is the ordinary
 * `update` pass.
 */
internal fun ViewGroup.adopt(child: View) {
    if (child.parent === this) return
    (child.parent as? ViewGroup)?.removeView(child)
    addView(child)
}

/**
 * How far above the bottom edge the lower corners park: the controls' height while the
 * chrome is up, else the design token that clears the End button alone.
 *
 * The clearance is used only when a card of this [box] still fits between it and the top
 * [margin]; otherwise the token, so a maximised preview is not shoved up over the
 * caller's name to make room for buttons it is going to sit behind anyway. Pure, so the
 * rule has a test rather than a screenshot.
 */
internal fun controlsInsetFor(box: VideoSize, area: VideoSize, margin: Float, token: Float, clearance: Float): Float =
    if (clearance > token && area.height - box.height - clearance >= margin) clearance else token

/** [size] in dp, or nothing at all while the shape is unknown. */
private fun Modifier.previewSize(box: VideoSize, density: Density): Modifier =
    if (box.isKnown) {
        with(density) { this@previewSize.size(box.width.toDp(), box.height.toDp()) }
    } else {
        this
    }

private const val BORDER_ALPHA = 0.8f

/** Dark enough for a white glyph to read over any frame the camera might be pointed at. */
private const val CONTROL_SCRIM = 0.45f

/**
 * How much of the preview's height one control glyph may take.
 *
 * Under a third, so the two of them — diagonally opposite — leave the middle of the picture
 * clear at every size the resize gesture can reach.
 */
private const val CONTROL_SHARE = 0.30f

internal const val TAG_PREVIEW_SURFACE = "call-video-preview-surface"
internal const val TAG_PREVIEW_TOGGLE = "call-video-preview-toggle"
internal const val TAG_PREVIEW_GRIP = "call-video-preview-grip"
internal const val TAG_PREVIEW_OUTLINE = "call-video-preview-outline"
