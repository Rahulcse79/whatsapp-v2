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

    private val ladders = listOf(
        VideoQualityProfiles.oneToOne,
        VideoQualityProfiles.threeParty,
        VideoQualityProfiles.fourParty,
    )

    @Test
    fun `every ladder is ordered richest first with no duplicate rungs`() {
        ladders.forEach { ladder ->
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
    fun `every ladder offers the same three rungs, so a step always has somewhere to go`() {
        // The ladders differ in what a rung *means*, never in which rungs exist. A ladder
        // missing a rung made `stepDown` skip a step on one shape and not another, and the
        // shape-crossing code then had to special-case which rungs were real.
        ladders.forEach { ladder ->
            assertEquals(
                listOf(VideoQualityTier.HIGH, VideoQualityTier.MEDIUM, VideoQualityTier.LOW),
                ladder.profiles.map { it.tier },
                "${ladder.shape} does not offer all three rungs",
            )
        }
    }

    @Test
    fun `the brief's exact tiers are what the ladders contain`() {
        fun VideoQualityLadder.at(tier: VideoQualityTier) = profile(tier)?.toString()

        // Two participants.
        assertEquals("640x360@15", VideoQualityProfiles.oneToOne.at(VideoQualityTier.HIGH))
        assertEquals("480x270@15", VideoQualityProfiles.oneToOne.at(VideoQualityTier.MEDIUM))
        assertEquals("320x180@15", VideoQualityProfiles.oneToOne.at(VideoQualityTier.LOW))

        // Three. The brief calls the middle rung NORMAL; it is MEDIUM here, which is the
        // name this enum already had for the rung between the top and the floor.
        assertEquals("480x270@15", VideoQualityProfiles.threeParty.at(VideoQualityTier.HIGH))
        assertEquals("320x180@15", VideoQualityProfiles.threeParty.at(VideoQualityTier.MEDIUM))
        assertEquals("192x108@15", VideoQualityProfiles.threeParty.at(VideoQualityTier.LOW))

        // Four.
        assertEquals("320x180@15", VideoQualityProfiles.fourParty.at(VideoQualityTier.HIGH))
        assertEquals("256x144@15", VideoQualityProfiles.fourParty.at(VideoQualityTier.MEDIUM))
        assertEquals("192x108@15", VideoQualityProfiles.fourParty.at(VideoQualityTier.LOW))
    }

    @Test
    fun `a busier call never asks for more work than a quieter one at the same rung`() {
        // The property the numbers exist to express, stated as a property rather than as
        // twelve literals: adding a participant adds an encode and a decode, so no rung of a
        // busier ladder may cost more pixels per second than the same rung of a quieter one.
        VideoQualityTier.entries.forEach { tier ->
            val solo = VideoQualityProfiles.oneToOne.profile(tier)!!
            val three = VideoQualityProfiles.threeParty.profile(tier)!!
            val four = VideoQualityProfiles.fourParty.profile(tier)!!
            assertTrue(
                three.pixelRate <= solo.pixelRate,
                "$tier costs more at three parties ($three) than at two ($solo)",
            )
            assertTrue(
                four.pixelRate <= three.pixelRate,
                "$tier costs more at four parties ($four) than at three ($three)",
            )
        }
    }

    @Test
    fun `smoothness is protected before sharpness at every participant count`() {
        // The rule, as one assertion: there is exactly one frame rate, and it is 15.
        //
        // Two reasons, both measured. The camera produces 30 fps on every one of these
        // handsets whatever the encoder is asked for, and 15 is the only rate that divides 30
        // evenly -- 20 fps is a 3:2 pattern and 12 fps is 5:2, and both arrive as judder with
        // no frames lost at all. And a rate that differs *between* a device's legs is the
        // same fault one level up: 13.0, 26.1 and 26.4 fps on one device's three outgoing
        // legs simultaneously (2026-09-28) is what "not smooth" looked like.
        //
        // So resolution is the only dial. If a rung ever needs a different rate again, this
        // is the assertion to argue with first.
        val rates = ladders.flatMap { it.profiles }.map { it.fps }.toSet()
        assertEquals(
            setOf(15), rates,
            "every rung must ask for the one rate that divides a 30 fps capture evenly",
        )
    }

    @Test
    fun `the shape for a call is decided by how many streams this device sends`() {
        assertEquals(CallShape.ONE_TO_ONE, CallShape.forOutgoingLegs(0))
        assertEquals(CallShape.ONE_TO_ONE, CallShape.forOutgoingLegs(1))
        assertEquals(CallShape.THREE_PARTY, CallShape.forOutgoingLegs(2))
        assertEquals(CallShape.FOUR_PARTY, CallShape.forOutgoingLegs(3))
        // Above the mesh ceiling is not an error: the busiest ladder is the right answer for
        // a device doing more work than the ceiling allows for.
        assertEquals(CallShape.FOUR_PARTY, CallShape.forOutgoingLegs(7))
    }

    @Test
    fun `every shape has a ladder and it is the one named for that shape`() {
        CallShape.entries.forEach { shape ->
            assertEquals(shape, VideoQualityProfiles.forShape(shape).shape)
        }
    }

    @Test
    fun `the ladders overlap, so a shape change always has a rung to land on`() {
        // `bestAffordableNotExceeding` caps a shape change at the picture already being sent.
        // If a busier ladder's top were below a quieter ladder's floor, that cap could not be
        // honoured -- the only rung available would be an upgrade granted for a hangup. So
        // each shape's floor must reach down to at least the next busier shape's top.
        assertTrue(
            VideoQualityProfiles.oneToOne.bottom.pixelRate <= VideoQualityProfiles.threeParty.top.pixelRate,
            "a three-party call cannot collapse to two without an upgrade: " +
                "${VideoQualityProfiles.oneToOne.bottom} vs ${VideoQualityProfiles.threeParty.top}",
        )
        assertTrue(
            VideoQualityProfiles.threeParty.bottom.pixelRate <= VideoQualityProfiles.fourParty.top.pixelRate,
            "a four-party call cannot collapse to three without an upgrade: " +
                "${VideoQualityProfiles.threeParty.bottom} vs ${VideoQualityProfiles.fourParty.top}",
        )
    }

    @Test
    fun `every ladder ends at a defined floor`() {
        assertEquals(VideoQualityTier.LOW, VideoQualityProfiles.oneToOne.bottom.tier)
        assertEquals(VideoQualityTier.LOW, VideoQualityProfiles.threeParty.bottom.tier)
        assertEquals(VideoQualityTier.LOW, VideoQualityProfiles.fourParty.bottom.tier)
        assertEquals(
            VideoQualityProfiles.threeParty.bottom, VideoQualityProfiles.fourParty.bottom,
            "three and four parties share a floor, so collapsing between them changes nothing",
        )
    }

    @Test
    fun `stepping stops at both ends rather than wrapping`() {
        val ladder = VideoQualityProfiles.oneToOne
        assertNull(ladder.stepUp(VideoQualityTier.HIGH), "nothing above the top")
        assertNull(ladder.stepDown(VideoQualityTier.LOW), "nothing below the floor")
        assertEquals(VideoQualityTier.LOW, ladder.stepDown(VideoQualityTier.MEDIUM)?.tier)
        assertEquals(VideoQualityTier.HIGH, ladder.stepUp(VideoQualityTier.MEDIUM)?.tier)
    }

    @Test
    fun `bitrate bands rise with the picture and never cross the hard ceiling`() {
        ladders.flatMap { it.profiles }.forEach { profile ->
            assertTrue(
                profile.maxBps <= VideoBudget.HARD_CEILING_BPS,
                "$profile peaks above the hard ceiling",
            )
            assertTrue(profile.minBps < profile.targetBps, "$profile has no band to move in")
        }

        ladders.forEach { ladder ->
            ladder.profiles.zipWithNext { richer, poorer ->
                assertTrue(
                    richer.targetBps >= poorer.targetBps,
                    "${ladder.shape}: $richer should not cost less than $poorer",
                )
            }
        }
    }

    @Test
    fun `bitrate follows the picture, so a smaller rung is proportionally cheaper`() {
        // Not an exact ratio -- coding efficiency is not linear -- but a rung half the pixel
        // rate of another must not ask for anything like the same bitrate, or "reduce the
        // resolution" would buy nothing on a constrained link.
        ladders.flatMap { it.profiles }.forEach { profile ->
            val bitsPerPixel = profile.targetBps.toDouble() / profile.pixelRate
            assertTrue(
                bitsPerPixel in 0.05..0.20,
                "$profile asks ${"%.3f".format(bitsPerPixel)} bits per pixel, which is off the curve",
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
    fun `a four-party mesh affords its top rung with the aggregate to spare`() {
        val mesh = VideoBudget(outgoingVideoLegs = 3)
        val top = VideoQualityProfiles.fourParty.top

        assertTrue(mesh.affords(top), "180p15 across three legs must fit in the aggregate")
        assertEquals(
            390_000, mesh.aggregateTargetBps(top),
            "three legs at 130k is what this device would actually ask for -- an eighth of the ceiling",
        )
        assertEquals(
            200_000, mesh.maxBpsFor(top),
            "the profile peaks to 200k and the leg's share allows 1M, so the profile wins",
        )
    }

    @Test
    fun `a one-to-one call is not capped below what the profile asks for`() {
        val solo = VideoBudget(outgoingVideoLegs = 1)
        val top = VideoQualityProfiles.oneToOne.top
        assertTrue(solo.affords(top))
        assertEquals(top.maxBps, solo.maxBpsFor(top), "540p30 alone should get its full band")
    }

    @Test
    fun `a leg's ceiling never falls below the profile's own minimum`() {
        // A tiny aggregate would otherwise hand the encoder a ceiling under the bitrate the
        // resolution needs. The answer to that is a lower tier, which the policy decides --
        // clamping here keeps the encoder honest instead of hiding it.
        val starved = VideoBudget(outgoingVideoLegs = 3, aggregateCeilingBps = 90_000)
        val top = VideoQualityProfiles.fourParty.top
        assertFalse(starved.affords(top))
        assertEquals(top.minBps, starved.maxBpsFor(top))
    }

    @Test
    fun `no legs is not a division by zero`() {
        assertEquals(VideoBudget.HARD_CEILING_BPS, VideoBudget(outgoingVideoLegs = 0).allowanceBpsPerLeg)
    }

    @Test
    fun `the affordable rung falls as the aggregate is squeezed`() {
        val ladder = VideoQualityProfiles.fourParty
        val ceiling = DisplayCeiling.UNCONSTRAINED
        fun best(legs: Int, aggregate: Int) = ladder.bestAffordable(
            VideoBudget(legs, aggregate).allowanceBpsPerLeg, ceiling,
        ).tier

        assertEquals(VideoQualityTier.HIGH, best(legs = 1, aggregate = 3_000_000))
        assertEquals(VideoQualityTier.HIGH, best(legs = 3, aggregate = 3_000_000))
        // Squeeze the aggregate and the same three legs can no longer hold the top rung. The
        // numbers are small because the rungs are: three legs at the top ask 390k in total.
        assertEquals(VideoQualityTier.MEDIUM, best(legs = 3, aggregate = 360_000))
        assertEquals(VideoQualityTier.LOW, best(legs = 3, aggregate = 240_000))
    }

    @Test
    fun `crossing ladders never lands on a richer rung than the one being left`() {
        val leaving = VideoQualityProfiles.fourParty.top           // 640x360@20
        val landed = VideoQualityProfiles.oneToOne.bestAffordableNotExceeding(
            pixelRateCeiling = leaving.pixelRate,
            allowanceBps = VideoBudget.HARD_CEILING_BPS,
            displayCeiling = DisplayCeiling.UNCONSTRAINED,
        )
        assertTrue(
            landed.pixelRate <= leaving.pixelRate,
            "a conference ending must not hand out a free upgrade: landed on $landed",
        )
        assertEquals(VideoQualityTier.LOW, landed.tier)
    }

    @Test
    fun `a four-party call collapsing to three does not gain a rung for the shape change`() {
        // The case the mesh actually produces: somebody leaves, the ladder changes underneath
        // a call that was coping, and the landing rung must be earned rather than granted.
        val leaving = VideoQualityProfiles.fourParty.bottom        // 480x270@15
        val landed = VideoQualityProfiles.threeParty.bestAffordableNotExceeding(
            pixelRateCeiling = leaving.pixelRate,
            allowanceBps = VideoBudget.HARD_CEILING_BPS,
            displayCeiling = DisplayCeiling.UNCONSTRAINED,
        )
        assertTrue(
            landed.pixelRate <= leaving.pixelRate,
            "losing a participant handed out an upgrade: landed on $landed",
        )
    }

    // ------------------------------------------------------------ display ceiling

    @Test
    fun `a tile admits a picture up to half again its own height`() {
        val tile = DisplayCeiling(height = 360)
        assertTrue(tile.admits(VideoQualityProfiles.threeParty.top), "360 into a 360 tile")
        assertTrue(
            tile.admits(VideoQualityProfiles.oneToOne.top),
            "540 into a 360 tile is exactly the 1.5x slack, so it is admitted",
        )
        assertFalse(
            tile.admits(
                VideoQualityProfile(
                    tier = VideoQualityTier.HIGH,
                    width = 1280, height = 720, fps = 30,
                    minBps = 800_000, targetBps = 1_600_000, maxBps = 2_500_000,
                ),
            ),
            "720 into a 360 tile is waste -- twice the tile, past the slack",
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
    fun `a full-screen remote on a tall phone can use the richest rung there is`() {
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
            shape = CallShape.FOUR_PARTY,
            budget = VideoBudget(outgoingVideoLegs = 3),
            displayCeiling = DisplayCeiling(360),
        )
        val after = smoother.accept(conference)

        // There is no such thing as the mean of "how many people are on this call".
        assertEquals(CallShape.FOUR_PARTY, after.shape)
        assertEquals(3, after.budget.outgoingVideoLegs)
        assertEquals(DisplayCeiling(360), after.displayCeiling)
    }
}
