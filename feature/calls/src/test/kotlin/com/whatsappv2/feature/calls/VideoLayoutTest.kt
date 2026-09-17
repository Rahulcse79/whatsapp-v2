package com.whatsappv2.feature.calls

import com.whatsappv2.domain.engine.VideoSize
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
    fun `cover fills a portrait screen with a landscape stream, overflowing the sides`() {
        // The one-to-one case. 1280x720 into 1080x1920: height binds, so the picture is
        // 1920 tall and 3413 wide, and the caller clips 1166px off each side. That is the
        // trade -- edges lost, nothing squashed.
        val covered = VideoLayout.cover(VideoSize(1280, 720), screen)

        assertEquals(screen.height, covered.height, "the short axis must be filled exactly")
        assertTrue(covered.width >= screen.width, "and the other must overflow, never fall short")
        assertRatio(1280f / 720f, covered)
    }

    @Test
    fun `cover and fit agree when the stream is already the screen's shape`() {
        // No bars and no crop: the two answers are the same box, which is the property
        // that says neither is secretly scaling by a different factor.
        val frame = VideoSize(540, 960)

        assertEquals(VideoLayout.fit(frame, screen), VideoLayout.cover(frame, screen))
        assertEquals(screen, VideoLayout.cover(frame, screen))
    }

    @Test
    fun `cover never leaves a gap, for either orientation of stream`() {
        // The invariant that matters at the call site: whatever arrives, the view covers
        // the screen, so there is no black edge for the user to read as a broken camera.
        listOf(
            VideoSize(352, 288),
            VideoSize(1280, 720),
            VideoSize(720, 1280),
            VideoSize(1080, 1080),
            VideoSize(720, 1600),
        ).forEach { frame ->
            val covered = VideoLayout.cover(frame, screen)
            assertTrue(covered.width >= screen.width, "$frame left a gap across")
            assertTrue(covered.height >= screen.height, "$frame left a gap down")
        }
    }

    @Test
    fun `cover leaves the view alone until a frame has been decoded`() {
        assertEquals(screen, VideoLayout.cover(VideoSize.UNKNOWN, screen))
        assertEquals(VideoSize.UNKNOWN, VideoLayout.cover(VideoSize(1280, 720), VideoSize.UNKNOWN))
    }

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

    /** The camera the reference handset actually reports. */
    private val camera = VideoSize(1280, 720)

    @Test
    fun `the box is square at every size the user can reach`() {
        val sizes = listOf(
            VideoLayout.previewBox(screen, VideoLayout.PREVIEW_MIN_SCALE),
            VideoLayout.previewBox(screen, 0.9f),
            VideoLayout.previewBox(screen),
            VideoLayout.previewBox(screen, 1.1f),
            VideoLayout.previewBox(screen, VideoLayout.PREVIEW_MAX_SCALE),
            VideoLayout.previewBox(screen, minimised = true),
        )

        sizes.forEach { box ->
            assertEquals(box.width, box.height, "not square: $box")
        }
    }

    @Test
    fun `the box does not depend on the camera at all any more`() {
        // It used to take the frame's shape. A square takes nothing from the camera, which
        // is why `previewBox` no longer has a frame parameter to get wrong. 480 rather than
        // 0.45 x 1080 = 486: on a 16:9 screen the half-height ceiling leaves 1066.7 of the
        // 1080 short edge for the range, and the whole range scales to it.
        assertEquals(VideoSize(480, 480), VideoLayout.previewBox(screen))
    }

    @Test
    fun `the default size is the floor, half the largest`() {
        // The box opens as small as it goes and the grip only grows it (2026-09-17).
        listOf(screen, VideoSize(720, 1450), VideoSize(2400, 1080)).forEach { display ->
            assertEquals(
                VideoLayout.previewBox(display, VideoLayout.PREVIEW_MIN_SCALE),
                VideoLayout.previewBox(display),
                "the default is not the smallest size on $display",
            )
            assertEquals(
                VideoLayout.previewBox(display, VideoLayout.PREVIEW_MAX_SCALE).width / 2,
                VideoLayout.previewBox(display).width,
                "the default is not half the largest on $display",
            )
        }
    }

    @Test
    fun `an unknown viewport has no preview to size`() {
        assertEquals(VideoSize.UNKNOWN, VideoLayout.previewBox(VideoSize.UNKNOWN))
    }

    @Test
    fun `the camera covers the square, so there is no black space in it`() {
        // The defect this pins: fitting a 16:9 frame inside a square leaves a quarter of
        // the box black, top and bottom. Covering fills it and the surplus is cropped by
        // the Android-level clip `SelfPreview` wraps the surface in.
        val box = VideoLayout.previewBox(screen)

        listOf(camera, VideoSize(720, 1280), VideoSize(720, 720)).forEach { frame ->
            val picture = VideoLayout.previewPicture(frame, box)

            assertTrue(
                picture.width >= box.width && picture.height >= box.height,
                "leaves black space: $picture in $box for $frame",
            )
            // And undistorted: covering scales, it does not stretch.
            assertShape(frame, picture)
        }
    }

    @Test
    fun `the square never exceeds the screen, in either direction`() {
        // Width is bounded by the fraction; height by PREVIEW_MAX_HEIGHT_FRACTION, which for
        // a square is the binding one on a tall screen and the only one on a short screen.
        listOf(screen, VideoSize(1920, 1080), VideoSize(1080, 2400), VideoSize(2560, 1600))
            .forEach { display ->
                val box = VideoLayout.previewBox(display, VideoLayout.PREVIEW_MAX_SCALE)

                assertTrue(
                    box.width <= display.width * USABLE_WIDTH_PERCENT / 100,
                    "wider than the screen allows: $box on $display",
                )
                assertTrue(box.height < display.height, "taller than the screen: $box on $display")
                assertEquals(box.width, box.height, "not square on $display")
            }
    }

    @Test
    fun `the smallest size is half the largest`() {
        // The rule the range is built on (2026-09-17). Asserted in pixels on the box the
        // user actually gets, not on the constants, so a rounding step in `previewBox`
        // cannot quietly make it "about half".
        listOf(screen, VideoSize(720, 1450), VideoSize(1600, 2560), VideoSize(1920, 1080)).forEach { display ->
            val smallest = VideoLayout.previewBox(display, VideoLayout.PREVIEW_MIN_SCALE)
            val largest = VideoLayout.previewBox(display, VideoLayout.PREVIEW_MAX_SCALE)

            assertTrue(
                kotlin.math.abs(largest.width - 2 * smallest.width) <= 1,
                "not half on $display: smallest $smallest, largest $largest",
            )
        }
    }

    @Test
    fun `a resize past either bound stops at it`() {
        val smallest = VideoLayout.previewBox(screen, VideoLayout.PREVIEW_MIN_SCALE)
        val largest = VideoLayout.previewBox(screen, VideoLayout.PREVIEW_MAX_SCALE)

        assertEquals(smallest, VideoLayout.previewBox(screen, 0f))
        assertEquals(largest, VideoLayout.previewBox(screen, 99f))
        assertTrue(smallest.width < largest.width, "the range is empty")
    }

    @Test
    fun `a width asks for the scale that produces it, within the bounds`() {
        // The inverse the resize grip uses: finger position in, clamped scale out.
        val wanted = VideoLayout.previewBox(screen, 1.1f)

        assertEquals(wanted, VideoLayout.previewBox(screen, VideoLayout.previewScaleForWidth(wanted.width, screen)))
        assertEquals(VideoLayout.PREVIEW_MIN_SCALE, VideoLayout.previewScaleForWidth(1, screen))
        assertEquals(VideoLayout.PREVIEW_MAX_SCALE, VideoLayout.previewScaleForWidth(screen.width, screen))
    }

    @Test
    fun `minimising ignores the chosen size and goes to the floor, half the largest`() {
        // Minimised is a state the user comes back from, so the size they picked has to
        // still be there when they do — which it cannot be if minimising overwrote it. And
        // where it goes is the same floor the grip stops at: half the largest (2026-09-17).
        val fromLargest = VideoLayout.previewBox(screen, VideoLayout.PREVIEW_MAX_SCALE, minimised = true)
        val fromSmallest = VideoLayout.previewBox(screen, VideoLayout.PREVIEW_MIN_SCALE, minimised = true)

        assertEquals(fromLargest, fromSmallest)
        assertEquals(
            VideoLayout.previewBox(screen, VideoLayout.PREVIEW_MIN_SCALE),
            fromLargest,
            "minimised is not the smallest ordinary size",
        )
        assertEquals(
            VideoLayout.previewBox(screen, VideoLayout.PREVIEW_MAX_SCALE).width / 2,
            fromLargest.width,
            "minimised is not half the largest",
        )
        assertEquals(fromLargest.width, fromLargest.height, "the minimised box is not square")
    }

    @Test
    fun `the preview is the same share of its range on a phone, a tablet and in landscape`() {
        // A dp floor would be a third of one and a tenth of the other. The bound is a
        // fraction of the shorter edge — or of what the height ceiling leaves of it — so
        // the default sits at the same point of the resize range on every screen, and a
        // rotation does not move it under the user's finger. The tablet and the landscape
        // phone are the two where the ceiling binds; the phone is one where it does not.
        val phone = VideoSize(1080, 2400)
        val share = VideoLayout.previewBox(phone).width.toFloat() /
            VideoLayout.previewBox(phone, VideoLayout.PREVIEW_MAX_SCALE).width
        assertTrue(kotlin.math.abs(share - 0.5f) < 0.005f, "the phone is not at the default point: $share")

        listOf(VideoSize(1600, 2560), VideoSize(2400, 1080)).forEach { display ->
            val default = VideoLayout.previewBox(display)
            val largest = VideoLayout.previewBox(display, VideoLayout.PREVIEW_MAX_SCALE)
            val here = default.width.toFloat() / largest.width

            assertTrue(
                kotlin.math.abs(here - share) < 0.005f,
                "a different point of the range on $display: $default of $largest, phone is $share",
            )
        }
    }

    @Test
    fun `the largest preview still fits on the screen with its corner margins`() {
        // The bound that is physics rather than policy: the preview parks in a corner with a
        // margin on each side, so it can never be wider than the screen less two margins.
        // On the reference handset that is 656px of a 720px screen — 0.911 of the short edge.
        val largest = VideoLayout.previewBox(screen, VideoLayout.PREVIEW_MAX_SCALE)

        assertTrue(
            largest.width <= (screen.width * USABLE_WIDTH_PERCENT / 100),
            "the largest preview does not fit beside its own margins: $largest on $screen",
        )
    }

    @Test
    fun `the largest preview does not take over the screen`() {
        // A square is a lot more box than a 16:9 one of the same width, so this is worth
        // asserting rather than assuming: on a four-way call the picture behind the
        // self-view is the other three people.
        val largest = VideoLayout.previewBox(screen, VideoLayout.PREVIEW_MAX_SCALE)
        val share = largest.width.toLong() * largest.height * 100 / (screen.width.toLong() * screen.height)

        assertTrue(share <= MAX_SCREEN_SHARE_PERCENT, "covers the conference: $largest is $share% of $screen")
    }

    private fun assertShape(frame: VideoSize, box: VideoSize) {
        val exact = box.width.toDouble() * frame.height / frame.width
        assertTrue(
            kotlin.math.abs(box.height - exact) <= 1.0,
            "expected ${frame.width}x${frame.height}'s shape, got $box (height should be ~$exact)",
        )
    }

    private fun assertRatio(expected: Float, actual: VideoSize) {
        val delta = kotlin.math.abs(actual.aspectRatio - expected)
        assertTrue(delta < RATIO_TOLERANCE, "expected ~$expected, got ${actual.aspectRatio} from $actual")
    }

    private companion object {

        /** The short edge less two corner margins, as a percentage — what a box can occupy. */
        const val USABLE_WIDTH_PERCENT = 91

        /**
         * How much of the screen the largest self-view may take.
         *
         * Large, and deliberately so — a square at 90 % of the shorter edge is a great deal
         * more box than a 16:9 one of the same width, and that is what was asked for. This
         * is the assertion to tighten if the self-view should stop competing with the grid.
         */
        const val MAX_SCREEN_SHARE_PERCENT = 45L

        /** Integer rounding moves the ratio by a fraction of a pixel's worth. */
        const val RATIO_TOLERANCE = 0.01f
    }
}
