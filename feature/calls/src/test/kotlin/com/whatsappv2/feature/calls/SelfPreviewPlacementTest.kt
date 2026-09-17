package com.whatsappv2.feature.calls

import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The self-view's placement rules, without a handset (§1.3).
 *
 * These are the decisions that are wrong in ways a device test would not catch: a resize
 * that shrinks, a minimise that forgets the size the user chose, a grip on an edge the box
 * cannot grow from. All of them are arithmetic on three fields, so all of them are here.
 */
class SelfPreviewPlacementTest {

    @Test
    fun `it starts bottom-right, full size, and visible`() {
        val placement = SelfPreviewPlacement()

        assertEquals(PreviewCorner.BottomEnd, placement.corner)
        assertEquals(1f, placement.scale)
        assertFalse(placement.isMinimised)
    }

    @Test
    fun `a resize past either bound stops at it rather than storing the overshoot`() {
        // Stored, the overshoot would make the next drag back spend its first inch undoing
        // a movement nobody saw — the box would sit still while the finger moved.
        assertEquals(
            VideoLayout.PREVIEW_MAX_SCALE,
            SelfPreviewPlacement().resizedTo(99f).scale,
        )
        assertEquals(
            VideoLayout.PREVIEW_MIN_SCALE,
            SelfPreviewPlacement().resizedTo(-5f).scale,
        )
    }

    @Test
    fun `minimising keeps the size the user chose, and restoring gives it back`() {
        val resized = SelfPreviewPlacement().resizedTo(1.1f)

        val minimised = resized.toggledMinimised()
        assertTrue(minimised.isMinimised)
        assertEquals(1.1f, minimised.scale, "minimising overwrote the chosen size")

        val restored = minimised.toggledMinimised()
        assertFalse(restored.isMinimised)
        assertEquals(resized, restored)
    }

    @Test
    fun `moving corners changes nothing else`() {
        val placed = SelfPreviewPlacement().resizedTo(1.1f).toggledMinimised()

        val moved = placed.movedTo(PreviewCorner.TopStart)

        assertEquals(PreviewCorner.TopStart, moved.corner)
        assertEquals(placed.scale, moved.scale)
        assertEquals(placed.isMinimised, moved.isMinimised)
    }

    @Test
    fun `the saver round-trips every field`() {
        val placement = SelfPreviewPlacement(PreviewCorner.TopEnd, scale = 1.25f, isMinimised = true)

        val saved = with(SelfPreviewPlacement.Saver) { allowingScope().save(placement) }
        val restored = SelfPreviewPlacement.Saver.restore(checkNotNull(saved))

        assertEquals(placement, restored)
    }

    @Test
    fun `a corner name that no longer exists restores the default rather than throwing`() {
        // A value removed in a later version arrives here from saved state, and the screen
        // being restored is the worst possible moment to raise.
        val restored = SelfPreviewPlacement.Saver.restore(listOf("Nowhere", 1f, false))

        assertEquals(PreviewCorner.BottomEnd, restored?.corner)
    }

    // ============================================================ corner geometry

    @Test
    fun `the resize grip is always the corner facing the middle of the screen`() {
        // The box is pinned by the two edges at its parked corner and can only grow from
        // the other two. A grip on a pinned edge is a drag that cannot do anything.
        assertEquals(Alignment.TopStart, PreviewCorner.BottomEnd.resizeGrip)
        assertEquals(Alignment.TopEnd, PreviewCorner.BottomStart.resizeGrip)
        assertEquals(Alignment.BottomStart, PreviewCorner.TopEnd.resizeGrip)
        assertEquals(Alignment.BottomEnd, PreviewCorner.TopStart.resizeGrip)
    }

    @Test
    fun `dragging the grip away from the parked corner always grows the box`() {
        // The defect this pins is the resize that shrinks: obvious in the hand, invisible
        // in a diff. "Away from the corner" is the sign, per axis.
        assertEquals(Offset(-1f, -1f), PreviewCorner.BottomEnd.growth)
        assertEquals(Offset(1f, 1f), PreviewCorner.TopStart.growth)
        assertEquals(Offset(-1f, 1f), PreviewCorner.TopEnd.growth)
        assertEquals(Offset(1f, -1f), PreviewCorner.BottomStart.growth)

        // And the grip is always on the side the growth points towards.
        PreviewCorner.entries.forEach { corner ->
            val towardsStart = corner.growth.x > 0
            val expected = if (towardsStart) {
                corner.resizeGrip in setOf(Alignment.BottomEnd, Alignment.TopEnd)
            } else {
                corner.resizeGrip in setOf(Alignment.BottomStart, Alignment.TopStart)
            }
            assertTrue(expected, "$corner grows one way and grips the other")
        }
    }

    @Test
    fun `a dropped preview goes to the corner it is nearest`() {
        val width = 1080f
        val height = 2400f

        assertEquals(PreviewCorner.TopStart, PreviewCorner.nearest(Offset(10f, 10f), width, height))
        assertEquals(PreviewCorner.TopEnd, PreviewCorner.nearest(Offset(1000f, 10f), width, height))
        assertEquals(PreviewCorner.BottomStart, PreviewCorner.nearest(Offset(10f, 2300f), width, height))
        assertEquals(PreviewCorner.BottomEnd, PreviewCorner.nearest(Offset(1000f, 2300f), width, height))
        // Dead centre resolves rather than hanging between two answers.
        assertEquals(PreviewCorner.BottomEnd, PreviewCorner.nearest(Offset(540f, 1200f), width, height))
    }

    /**
     * A `SaverScope` that permits anything.
     *
     * `Saver.save` needs one, and the real implementation asks whether a value can go into
     * a `Bundle`. Everything this saver writes is a String, a Float or a Boolean, so the
     * question has one answer.
     */
    private fun allowingScope(): androidx.compose.runtime.saveable.SaverScope =
        androidx.compose.runtime.saveable.SaverScope { true }
}
