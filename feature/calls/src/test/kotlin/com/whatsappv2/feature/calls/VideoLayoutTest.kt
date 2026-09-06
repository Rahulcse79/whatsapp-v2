package com.whatsappv2.feature.calls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 52's third done-when: a remote resolution change must not stretch or crash the view.
 *
 * Asserted here rather than on a device because a device cannot tell you a picture was the
 * wrong shape — it lays out happily either way. A pure function can be asked directly.
 */
class VideoLayoutTest {

    private val screen = VideoSize(1080, 1920)

    @Test
    fun `a landscape stream is letterboxed into a portrait screen`() {
        val fitted = VideoLayout.fit(VideoSize(1280, 720), screen)

        // Width is the binding constraint; the height follows the stream's own ratio.
        assertEquals(VideoSize(1080, 607), fitted)
    }

    @Test
    fun `a portrait stream is pillarboxed into a landscape screen`() {
        val fitted = VideoLayout.fit(VideoSize(720, 1280), VideoSize(1920, 1080))

        assertEquals(VideoSize(607, 1080), fitted)
    }

    @Test
    fun `a stream with the same ratio fills the space exactly`() {
        assertEquals(VideoSize(1080, 1920), VideoLayout.fit(VideoSize(540, 960), screen))
    }

    @Test
    fun `a remote resolution change produces a new shape, not a stretched one`() {
        // The bug this prevents: sizing to the first frame and leaving it there.
        val first = VideoLayout.fit(VideoSize(640, 480), screen)
        val afterChange = VideoLayout.fit(VideoSize(1280, 720), screen)

        assertTrue(first != afterChange)
        assertRatio(expected = 640f / 480f, actual = first)
        assertRatio(expected = 1280f / 720f, actual = afterChange)
    }

    @Test
    fun `an unknown frame fills the space rather than collapsing to nothing`() {
        // A collapsed view is a visible flicker at the start of every video call.
        assertEquals(screen, VideoLayout.fit(VideoSize.UNKNOWN, screen))
        assertFalse(VideoSize.UNKNOWN.isKnown)
        assertEquals(0f, VideoSize.UNKNOWN.aspectRatio)
    }

    @Test
    fun `an unknown viewport gives back the viewport rather than dividing by zero`() {
        assertEquals(VideoSize.UNKNOWN, VideoLayout.fit(VideoSize(1280, 720), VideoSize.UNKNOWN))
    }

    // ================================================================ rotation

    @Test
    fun `a quarter turn swaps the axes`() {
        assertEquals(VideoSize(720, 1280), VideoLayout.orient(VideoSize(1280, 720), 90))
        assertEquals(VideoSize(720, 1280), VideoLayout.orient(VideoSize(1280, 720), 270))
    }

    @Test
    fun `a half turn leaves the shape alone`() {
        assertEquals(VideoSize(1280, 720), VideoLayout.orient(VideoSize(1280, 720), 180))
        assertEquals(VideoSize(1280, 720), VideoLayout.orient(VideoSize(1280, 720), 0))
    }

    @Test
    fun `rotation is normalised, so a full turn and a negative one both work`() {
        assertEquals(VideoSize(720, 1280), VideoLayout.orient(VideoSize(1280, 720), 450))
        assertEquals(VideoSize(720, 1280), VideoLayout.orient(VideoSize(1280, 720), -90))
        assertEquals(VideoSize(1280, 720), VideoLayout.orient(VideoSize(1280, 720), 360))
    }

    @Test
    fun `a rotation that is not a quarter turn is left alone rather than guessed at`() {
        assertEquals(VideoSize(1280, 720), VideoLayout.orient(VideoSize(1280, 720), 45))
    }

    // ================================================================ preview

    @Test
    fun `the preview is a quarter of the short edge and keeps the frame's shape`() {
        val preview = VideoLayout.previewSize(VideoSize(1280, 720), screen)

        // 1080 short edge / 4 = 270 box; a 16:9 frame letterboxes to 270x151.
        assertEquals(VideoSize(270, 151), preview)
    }

    @Test
    fun `the preview takes the short edge on a landscape screen, not the long one`() {
        val preview = VideoLayout.previewSize(VideoSize(720, 720), VideoSize(1920, 1080))

        assertEquals(VideoSize(270, 270), preview)
    }

    @Test
    fun `an unknown viewport has no preview to size`() {
        assertEquals(VideoSize.UNKNOWN, VideoLayout.previewSize(VideoSize(1280, 720), VideoSize.UNKNOWN))
    }

    private fun assertRatio(expected: Float, actual: VideoSize) {
        val delta = kotlin.math.abs(actual.aspectRatio - expected)
        assertTrue(delta < RATIO_TOLERANCE, "expected ~$expected, got ${actual.aspectRatio} from $actual")
    }

    private companion object {
        /** Integer rounding moves the ratio by a fraction of a pixel's worth. */
        const val RATIO_TOLERANCE = 0.01f
    }
}
