package com.whatsappv2.domain.video

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ladders, the budget arithmetic and the smoother — the parts the policy is built on.
 *
 * Separate from `AdaptiveVideoPolicyTest` because these are value types with no clock and
 * no state machine: when one of these fails the arithmetic is wrong, and when a policy test
 * fails the rule is. Keeping them apart is what makes that distinction readable from the
 * failure alone.
 */
class VideoQualityProfilesTest {

    // ------------------------------------------------------------------ ladders

    @Test
    fun `both ladders are ordered richest first with no duplicate rungs`() {
        listOf(VideoQualityProfiles.oneToOne, VideoQualityProfiles.conference).forEach { ladder ->
            val rates = ladder.profiles.map { it.pixelRate }
            assertEquals(
                rates.sortedDescending(), rates,
                "${ladder.shape} is not ordered richest first: ${ladder.profiles}",
            )
            assertEquals(
                ladder.profiles.map { it.tier }.distinct().size, ladder.profiles.size,
                "${ladder.shape} repeats a rung",
            )
        }
    }

    @Test
    fun `every ladder ends at the same floor, so degradation always has a defined bottom`() {
        assertEquals(VideoQualityTier.LOW, VideoQualityProfiles.oneToOne.bottom.tier)
        assertEquals(VideoQualityTier.LOW, VideoQualityProfiles.conference.bottom.tier)
        assertEquals(640, VideoQualityProfiles.oneToOne.bottom.width)
        assertEquals(15, VideoQualityProfiles.oneToOne.bottom.fps)
        assertEquals(
            VideoQualityProfiles.oneToOne.bottom, VideoQualityProfiles.conference.bottom,
            "the floor must be one profile, not two that happen to match",
        )
    }

    @Test
    fun `the brief's exact tiers are what the ladders contain`() {
        fun VideoQualityLadder.at(tier: VideoQualityTier) = profile(tier)?.toString()

        assertEquals("1280x720@30", VideoQualityProfiles.oneToOne.at(VideoQualityTier.HIGH))
        assertEquals("960x540@30", VideoQualityProfiles.oneToOne.at(VideoQualityTier.MEDIUM_HIGH))
        assertEquals("640x360@24", VideoQualityProfiles.oneToOne.at(VideoQualityTier.MEDIUM))
        assertEquals("640x360@15", VideoQualityProfiles.oneToOne.at(VideoQualityTier.LOW))

        assertEquals("960x540@24", VideoQualityProfiles.conference.at(VideoQualityTier.HIGH))
        // The brief calls this conference rung NORMAL; it is the same picture as one-to-one
        // MEDIUM, so it is that rung rather than a fifth name for an existing resolution.
        assertEquals("640x360@24", VideoQualityProfiles.conference.at(VideoQualityTier.MEDIUM))
        assertEquals("640x360@15", VideoQualityProfiles.conference.at(VideoQualityTier.LOW))
    }

    @Test
    fun `stepping stops at both ends rather than wrapping`() {
        val ladder = VideoQualityProfiles.oneToOne
        assertNull(ladder.stepUp(VideoQualityTier.HIGH), "nothing above the top")
        assertNull(ladder.stepDown(VideoQualityTier.LOW), "nothing below the floor")
        assertEquals(
            VideoQualityTier.MEDIUM, ladder.stepDown(VideoQualityTier.MEDIUM_HIGH)?.tier,
        )
        assertEquals(
            VideoQualityTier.MEDIUM_HIGH, ladder.stepUp(VideoQualityTier.MEDIUM)?.tier,
        )
    }

    @Test
    fun `the conference ladder steps over the rung it does not have`() {
        val ladder = VideoQualityProfiles.conference
        // HIGH -> MEDIUM directly: MEDIUM_HIGH is 540p30 and a conference has no use for it.
        assertEquals(VideoQualityTier.MEDIUM, ladder.stepDown(VideoQualityTier.HIGH)?.tier)
        assertEquals(VideoQualityTier.HIGH, ladder.stepUp(VideoQualityTier.MEDIUM)?.tier)
    }

    @Test
    fun `bitrate bands rise with the picture and never cross the hard ceiling`() {
        (VideoQualityProfiles.oneToOne.profiles + VideoQualityProfiles.conference.profiles)
            .forEach { profile ->
                assertTrue(
                    profile.maxBps <= VideoBudget.HARD_CEILING_BPS,
                    "$profile peaks above the hard ceiling",
                )
                assertTrue(profile.minBps < profile.targetBps, "$profile has no band to move in")
            }

        VideoQualityProfiles.oneToOne.profiles.zipWithNext { richer, poorer ->
            assertTrue(
                richer.targetBps > poorer.targetBps,
                "$richer should cost more than $poorer",
            )
        }
    }

    // ------------------------------------------------------------------- budget

    @Test
    fun `one leg gets the hard ceiling, three legs get a third of the aggregate`() {
        assertEquals(
            VideoBudget.HARD_CEILING_BPS,
            VideoBudget(outgoingVideoLegs = 1).allowanceBpsPerLeg,
            "a one-to-one call is bounded by the per-stream ceiling, not the aggregate",
        )
        assertEquals(1_000_000, VideoBudget(outgoingVideoLegs = 3).allowanceBpsPerLeg)
        assertEquals(1_500_000, VideoBudget(outgoingVideoLegs = 2).allowanceBpsPerLeg)
    }

    @Test
    fun `a four-party mesh can afford the conference top rung but not its peak`() {
        val mesh = VideoBudget(outgoingVideoLegs = 3)
        val top = VideoQualityProfiles.conference.top

        assertTrue(mesh.affords(top), "540p24 across three legs must fit in the aggregate")
        assertEquals(
            2_550_000, mesh.aggregateTargetBps(top),
            "three legs at 850k is what this device would actually ask for",
        )
        assertEquals(
            1_000_000, mesh.maxBpsFor(top),
            "the profile would peak to 1.3M; the leg's share caps it at 1M",
        )
    }

    @Test
    fun `a one-to-one call is not capped below what the profile asks for`() {
        val solo = VideoBudget(outgoingVideoLegs = 1)
        val top = VideoQualityProfiles.oneToOne.top
        assertTrue(solo.affords(top))
        assertEquals(top.maxBps, solo.maxBpsFor(top), "720p30 alone should get its full band")
    }

    @Test
    fun `a leg's ceiling never falls below the profile's own minimum`() {
        // A tiny aggregate would otherwise hand the encoder a ceiling under the bitrate the
        // resolution needs. The answer to that is a lower tier, which the policy decides --
        // clamping here keeps the encoder honest instead of hiding it.
        val starved = VideoBudget(outgoingVideoLegs = 3, aggregateCeilingBps = 90_000)
        val top = VideoQualityProfiles.conference.top
        assertFalse(starved.affords(top))
        assertEquals(top.minBps, starved.maxBpsFor(top))
    }

    @Test
    fun `no legs is not a division by zero`() {
        assertEquals(VideoBudget.HARD_CEILING_BPS, VideoBudget(outgoingVideoLegs = 0).allowanceBpsPerLeg)
    }

    @Test
    fun `the affordable rung falls as legs are added`() {
        val ladder = VideoQualityProfiles.conference
        val ceiling = DisplayCeiling.UNCONSTRAINED
        fun best(legs: Int, aggregate: Int) = ladder.bestAffordable(
            VideoBudget(legs, aggregate).allowanceBpsPerLeg, ceiling,
        ).tier

        assertEquals(VideoQualityTier.HIGH, best(legs = 1, aggregate = 3_000_000))
        assertEquals(VideoQualityTier.HIGH, best(legs = 3, aggregate = 3_000_000))
        // Squeeze the aggregate and the same three legs can no longer hold the top rung.
        assertEquals(VideoQualityTier.MEDIUM, best(legs = 3, aggregate = 2_000_000))
        assertEquals(VideoQualityTier.LOW, best(legs = 3, aggregate = 1_400_000))
    }

    @Test
    fun `crossing ladders never lands on a richer rung than the one being left`() {
        val leaving = VideoQualityProfiles.conference.top          // 960x540@24
        val landed = VideoQualityProfiles.oneToOne.bestAffordableNotExceeding(
            pixelRateCeiling = leaving.pixelRate,
            allowanceBps = VideoBudget.HARD_CEILING_BPS,
            displayCeiling = DisplayCeiling.UNCONSTRAINED,
        )
        assertTrue(
            landed.pixelRate <= leaving.pixelRate,
            "a conference ending must not hand out 720p30: landed on $landed",
        )
        assertEquals(VideoQualityTier.MEDIUM, landed.tier)
    }

    // ------------------------------------------------------------ display ceiling

    @Test
    fun `a tile admits a picture up to half again its own height`() {
        val tile = DisplayCeiling(height = 360)
        assertTrue(tile.admits(VideoQualityProfiles.oneToOne.bottom), "360 into a 360 tile")
        assertTrue(
            tile.admits(VideoQualityProfiles.conference.top),
            "540 into a 360 tile is inside the 1.5x slack",
        )
        assertFalse(
            tile.admits(VideoQualityProfiles.oneToOne.top),
            "720 into a 360 tile is waste",
        )
    }

    @Test
    fun `an unconstrained ceiling admits everything, including the top rung`() {
        assertTrue(DisplayCeiling.UNCONSTRAINED.admits(VideoQualityProfiles.oneToOne.top))
    }

    @Test
    fun `a grid divides the viewport by rows, and nonsense input constrains nothing`() {
        // Three remotes are two rows on this layout, so each tile is half the viewport.
        assertEquals(DisplayCeiling(960), DisplayCeiling.forGrid(viewportHeight = 1920, rows = 2))
        assertEquals(DisplayCeiling(640), DisplayCeiling.forGrid(viewportHeight = 1920, rows = 3))
        // Before layout has happened there is no tile size, and guessing one would cap the
        // first seconds of every call at whatever zero divided by zero came to.
        assertEquals(DisplayCeiling.UNCONSTRAINED, DisplayCeiling.forGrid(0, 2))
        assertEquals(DisplayCeiling.UNCONSTRAINED, DisplayCeiling.forGrid(1920, 0))
    }

    @Test
    fun `a full-screen remote on a tall phone can still use 720p`() {
        val fullScreen = DisplayCeiling.forGrid(viewportHeight = 2340, rows = 1)
        assertTrue(fullScreen.admits(VideoQualityProfiles.oneToOne.top))
    }

    // ----------------------------------------------------------------- smoother

    private fun sample(loss: Double) = VideoConditions(
        shape = CallShape.ONE_TO_ONE,
        budget = VideoBudget(outgoingVideoLegs = 1),
        network = NetworkConditions(lossFraction = loss),
    )

    @Test
    fun `the first sample is taken whole rather than blended towards zero`() {
        val smoother = VideoConditionsSmoother()
        val first = smoother.accept(sample(loss = 0.20))
        assertEquals(0.20, first.network.lossFraction, 1e-9)
        assertEquals(1, smoother.samples)
    }

    @Test
    fun `a single spike is damped, and a sustained change is eventually expressed`() {
        val smoother = VideoConditionsSmoother()
        smoother.accept(sample(loss = 0.0))

        val spike = smoother.accept(sample(loss = 1.0)).network.lossFraction
        assertEquals(VideoConditionsSmoother.ALPHA, spike, 1e-9, "one sample moves it by alpha only")

        // Four samples of a step change express ~87% of it -- the figure the windows are
        // chosen against, asserted here so a change to alpha shows up as this failing.
        repeat(3) { smoother.accept(sample(loss = 1.0)) }
        assertTrue(smoother.current!!.network.lossFraction > 0.87)
    }

    @Test
    fun `thermal status is not smoothed, because it is a verdict and not a measurement`() {
        val smoother = VideoConditionsSmoother()
        smoother.accept(sample(loss = 0.0))
        val hot = sample(loss = 0.0).copy(
            device = DevicePressure(thermal = ThermalPressure.CRITICAL, cpuLoad = 1.0),
        )
        val after = smoother.accept(hot)

        assertEquals(ThermalPressure.CRITICAL, after.device.thermal, "SEVERE must not arrive late")
        // CPU load is a measurement and is smoothed, which is the contrast being drawn.
        assertTrue(after.device.cpuLoad < 1.0)
    }

    @Test
    fun `a reset forgets everything, so a restarted stream is not judged on the old one`() {
        val smoother = VideoConditionsSmoother()
        repeat(5) { smoother.accept(sample(loss = 0.5)) }
        smoother.reset()

        assertEquals(0, smoother.samples)
        assertNull(smoother.current)
        assertEquals(0.01, smoother.accept(sample(loss = 0.01)).network.lossFraction, 1e-9)
    }

    @Test
    fun `call facts are taken from the newest sample, never averaged`() {
        val smoother = VideoConditionsSmoother()
        smoother.accept(sample(loss = 0.0))
        val conference = sample(loss = 0.0).copy(
            shape = CallShape.CONFERENCE,
            budget = VideoBudget(outgoingVideoLegs = 3),
            displayCeiling = DisplayCeiling(360),
        )
        val after = smoother.accept(conference)

        // There is no such thing as the mean of "is this a conference".
        assertEquals(CallShape.CONFERENCE, after.shape)
        assertEquals(3, after.budget.outgoingVideoLegs)
        assertEquals(DisplayCeiling(360), after.displayCeiling)
    }
}
