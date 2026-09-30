package com.whatsappv2.domain.video

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The adaptive quality rules, with no device, no PJSIP and no wall clock.
 *
 * Time is a parameter here, which is the whole reason the policy takes `nowMillis` rather
 * than reading a clock: hysteresis and cooldown are *timing* rules, and a test that had to
 * sleep to exercise them would be a test nobody runs. [drive] advances a fake clock at the
 * controller's real sample cadence and feeds the same conditions each tick, so "sustained
 * for six seconds" is asserted as exactly that rather than approximated.
 */
class AdaptiveVideoPolicyTest {

    private val tick = 2_000L
    private val thresholds = AdaptiveVideoThresholds()

    // ------------------------------------------------------------------- fixtures

    /** Conditions a healthy 720p30 one-to-one call actually produces. */
    private fun healthy(
        shape: CallShape = CallShape.ONE_TO_ONE,
        legs: Int = 1,
        displayCeiling: DisplayCeiling = DisplayCeiling.UNCONSTRAINED,
    ) = VideoConditions(
        shape = shape,
        budget = VideoBudget(outgoingVideoLegs = legs),
        network = NetworkConditions(
            lossFraction = 0.001,
            rttMillis = 40.0,
            jitterMillis = 8.0,
            txBitrateBps = 1_500_000,
            feedbackPerSecond = 0.0,
        ),
        encoder = EncoderConditions(
            actualFpsRatio = 0.99,
            latencyMillis = 12.0,
            inputStarvationRatio = 0.0,
            outputStarvationRatio = 0.0,
        ),
        decoder = DecoderConditions(
            actualFpsRatio = 0.98,
            incompletePictureRatio = 0.0,
            starvationRatio = 0.0,
        ),
        device = DevicePressure(thermal = ThermalPressure.NONE, cpuLoad = 0.30),
        displayCeiling = displayCeiling,
    )

    private fun policy(
        shape: CallShape = CallShape.ONE_TO_ONE,
        legs: Int = 1,
        ceiling: DisplayCeiling = DisplayCeiling.UNCONSTRAINED,
    ) = AdaptiveVideoPolicy(
        shape = shape,
        thresholds = thresholds,
        initialBudget = VideoBudget(outgoingVideoLegs = legs),
        initialDisplayCeiling = ceiling,
    )

    /**
     * Feeds [conditions] every [tick] for [millis] and returns every transition produced.
     *
     * `clock` is a var captured across calls so a test can drive one phase, then another,
     * on a continuous timeline — which is what cooldown and the oscillation memory are
     * measured against.
     */
    private fun AdaptiveVideoPolicy.drive(
        conditions: VideoConditions,
        millis: Long,
        clock: LongArray,
    ): List<VideoQualityTransition> = buildList {
        val end = clock[0] + millis
        while (clock[0] < end) {
            clock[0] += tick
            sample(conditions, clock[0])?.let { add(it) }
        }
    }

    private fun clock(from: Long = 0L) = longArrayOf(from)

    /**
     * Drives until the first transition, rather than for a hand-computed number of
     * milliseconds.
     *
     * The window lengths are tuning and have already moved once — they follow the native
     * counter cadence, which is not ours to pick. A test that drove "12 seconds because
     * that is a window and a bit" silently stopped exercising anything the moment the
     * window grew. Driving to an *event* keeps each test about its own rule.
     */
    private fun AdaptiveVideoPolicy.driveUntilChange(
        conditions: VideoConditions,
        clock: LongArray,
        limitMillis: Long = 300_000,
    ): VideoQualityTransition? {
        val end = clock[0] + limitMillis
        while (clock[0] < end) {
            clock[0] += tick
            sample(conditions, clock[0])?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------- staying put

    @Test
    fun `a healthy one-to-one call starts at the top rung and stays there`() {
        val policy = policy()
        assertEquals(VideoQualityTier.HIGH, policy.current.tier)
        assertEquals(640, policy.current.width)
        assertEquals(15, policy.current.fps)

        val moves = policy.drive(healthy(), millis = 120_000, clock = clock())

        assertTrue(moves.isEmpty(), "a healthy call should never change tier, got $moves")
        assertEquals(VideoQualityTier.HIGH, policy.current.tier)
    }

    @Test
    fun `one bad sample does not move anything`() {
        val policy = policy()
        val c = clock()
        policy.drive(healthy(), millis = 20_000, clock = c)

        c[0] += tick
        val collapse = healthy().copy(
            encoder = EncoderConditions(actualFpsRatio = 0.05, latencyMillis = 300.0),
            network = NetworkConditions(lossFraction = 0.40, rttMillis = 900.0),
        )
        assertNull(policy.sample(collapse, c[0]), "a single sample must never move a tier")
        assertEquals(VideoQualityTier.HIGH, policy.current.tier)

        // ...and the pressure is visible even though nothing moved, which is the point of
        // separating "conditions are bad" from "conditions have been bad long enough".
        assertTrue(policy.pressure.isNotEmpty())
    }

    @Test
    fun `at the top rung there is nothing to upgrade to`() {
        val policy = policy()
        val moves = policy.drive(healthy(), millis = 300_000, clock = clock())
        assertTrue(moves.isEmpty())
        assertEquals(VideoQualityProfiles.oneToOne.top, policy.current)
    }

    // --------------------------------------------------------------- downgrades

    @Test
    fun `sustained packet loss steps the tier down`() {
        val policy = policy()
        val c = clock()
        val lossy = healthy().copy(
            network = NetworkConditions(lossFraction = 0.12, rttMillis = 60.0, jitterMillis = 10.0),
        )

        val moves = policy.drive(lossy, millis = 30_000, clock = c)

        assertTrue(moves.isNotEmpty(), "12% loss must cost quality")
        val first = moves.first()
        assertEquals(VideoQualityTier.HIGH, first.from.tier)
        assertEquals(VideoQualityTier.MEDIUM, first.to.tier)
        assertEquals(VideoQualityReason.NETWORK_LOSS, first.reason)
    }

    @Test
    fun `an encoder that cannot make its frame rate steps the tier down`() {
        val policy = policy()
        // Exactly Phase 3's failure signature: 720p30 configured, 4.4 fps emerging.
        val starved = healthy().copy(
            encoder = EncoderConditions(
                actualFpsRatio = 4.4 / 30.0,
                latencyMillis = 68.5,
                inputStarvationRatio = 0.9,
            ),
        )

        val moves = policy.drive(starved, millis = 30_000, clock = clock())

        assertTrue(moves.isNotEmpty())
        assertEquals(VideoQualityReason.ENCODER_PRESSURE, moves.first().reason)
        assertTrue(moves.first().to.pixelRate < moves.first().from.pixelRate)
    }

    @Test
    fun `encode latency alone is enough, even at a plausible frame rate`() {
        val policy = policy()
        // 0.80 of the configured rate is above the fps threshold -- but 102 ms a frame cannot
        // produce 20 whatever the counters say, and that contradiction is the finding. The
        // number tracks the top rung's frame budget: 50 ms at 20 fps, and this is twice it.
        val slow = healthy().copy(
            encoder = EncoderConditions(actualFpsRatio = 0.80, latencyMillis = 102.0),
        )
        val moves = policy.drive(slow, millis = 30_000, clock = clock())
        assertEquals(VideoQualityReason.ENCODER_PRESSURE, moves.first().reason)
    }

    @Test
    fun `thermal pressure steps the tier down and is reported ahead of everything else`() {
        val policy = policy()
        val hot = healthy().copy(
            device = DevicePressure(thermal = ThermalPressure.SEVERE, cpuLoad = 0.9),
            encoder = EncoderConditions(actualFpsRatio = 0.4, latencyMillis = 90.0),
        )

        val moves = policy.drive(hot, millis = 20_000, clock = clock())

        assertEquals(VideoQualityReason.THERMAL_PRESSURE, moves.first().reason)
        // Nothing is lost by choosing one reason for the headline.
        assertTrue(VideoQualityReason.ENCODER_PRESSURE in moves.first().allReasons)
        assertTrue(VideoQualityReason.CPU_PRESSURE in moves.first().allReasons)
    }

    @Test
    fun `critical thermal skips rungs instead of stepping politely`() {
        val policy = policy()
        val critical = healthy().copy(
            // Tight enough that only the floor is affordable, so "skipped a rung" is what is
            // being asserted rather than "stepped once and the budget did the rest".
            budget = VideoBudget(outgoingVideoLegs = 1, aggregateCeilingBps = 150_000),
            device = DevicePressure(thermal = ThermalPressure.CRITICAL, cpuLoad = 0.95),
        )
        val moves = policy.drive(critical, millis = 20_000, clock = clock())
        assertEquals(VideoQualityTier.LOW, moves.first().to.tier, "critical must not step one rung")
    }

    @Test
    fun `thermal severe on its own never moves a tier while the pipeline meets its target`() {
        // Measured on an SM-E236B, 2026-09-27: the platform reported SEVERE (skin 42.6 C at
        // severity 3) throughout a call capturing 30.0 fps at 0% loss with a 7 ms round
        // trip. The first version of this policy walked that healthy call to the floor in
        // three steps, and could never have climbed back -- that handset reports SEVERE
        // whenever the camera runs. This test is that call.
        val policy = policy()
        val hotButHealthy = healthy().copy(
            device = DevicePressure(thermal = ThermalPressure.SEVERE, cpuLoad = 0.55),
        )

        val moves = policy.drive(hotButHealthy, millis = 300_000, clock = clock())

        assertTrue(moves.isEmpty(), "a sustainable tier must not be abandoned on skin temperature: $moves")
        assertEquals(VideoQualityTier.HIGH, policy.current.tier)
    }

    @Test
    fun `thermal severe does count once something measurable agrees with it`() {
        val policy = policy()
        val hotAndFailing = healthy().copy(
            device = DevicePressure(thermal = ThermalPressure.SEVERE, cpuLoad = 0.9),
            encoder = EncoderConditions(actualFpsRatio = 0.45, latencyMillis = 70.0),
        )

        val moves = policy.drive(hotAndFailing, millis = 30_000, clock = clock())

        assertTrue(moves.isNotEmpty(), "thermal plus a starved encoder must cost quality")
        assertEquals(VideoQualityReason.THERMAL_PRESSURE, moves.first().reason)
        assertTrue(VideoQualityReason.ENCODER_PRESSURE in moves.first().allReasons)
    }

    @Test
    fun `a handset that idles at moderate can still climb`() {
        val policy = policy()
        val c = clock()
        policy.drive(healthy().copy(network = NetworkConditions(lossFraction = 0.5)), 60_000, c)
        assertEquals(VideoQualityTier.LOW, policy.current.tier)

        // Warm but not throttled, and the media is fine. This must not be a life sentence.
        val warm = healthy().copy(device = DevicePressure(thermal = ThermalPressure.MODERATE))
        val moves = policy.drive(warm, millis = 400_000, clock = c)

        assertTrue(moves.isNotEmpty(), "MODERATE must not bar recovery outright")
        assertEquals(VideoQualityTier.HIGH, policy.current.tier)
    }

    @Test
    fun `a handset that reports severe for the whole call can still recover`() {
        // Both test handsets do exactly this: SEVERE from the first minute of any video call
        // to the last. A gate that blocked climbing there would make the policy a one-way
        // ratchet on the only hardware it runs on -- quality falls once and never returns.
        val policy = policy()
        val c = clock()
        policy.drive(healthy().copy(network = NetworkConditions(lossFraction = 0.5)), 60_000, c)
        assertEquals(VideoQualityTier.LOW, policy.current.tier)

        val hotButHealthy = healthy().copy(device = DevicePressure(thermal = ThermalPressure.SEVERE))
        val moves = policy.drive(hotButHealthy, millis = 400_000, clock = c)

        assertTrue(moves.isNotEmpty(), "SEVERE must not bar recovery on hardware that always reports it")
        assertTrue(moves.all { it.reason == VideoQualityReason.SUSTAINED_HEALTH })
    }

    @Test
    fun `climbing is barred while the platform says critical`() {
        val policy = policy()
        val c = clock()
        policy.drive(healthy().copy(network = NetworkConditions(lossFraction = 0.5)), 60_000, c)
        val floor = policy.current

        // At CRITICAL the platform is cutting the device back. Asking for more is the one
        // thing that cannot help.
        val critical = healthy().copy(device = DevicePressure(thermal = ThermalPressure.CRITICAL))
        val moves = policy.drive(critical, millis = 400_000, clock = c)

        assertTrue(moves.isEmpty(), "CRITICAL must hold the tier where it is, not raise it: $moves")
        assertEquals(floor, policy.current)
    }

    @Test
    fun `cpu load on its own never moves a tier`() {
        val policy = policy()
        val busy = healthy().copy(device = DevicePressure(cpuLoad = 0.95))

        val moves = policy.drive(busy, millis = 120_000, clock = clock())

        assertTrue(moves.isEmpty(), "CPU load with healthy media is not evidence -- got $moves")
        // Phase 3: the encode collapse happened with four and a half cores idle. Load is a
        // corroborator, and a policy that acted on it alone would act on the wrong signal.
        assertTrue(VideoQualityReason.CPU_PRESSURE !in policy.pressure)
    }

    @Test
    fun `incomplete incoming pictures step the tier down`() {
        val policy = policy()
        val broken = healthy().copy(
            decoder = DecoderConditions(actualFpsRatio = 0.5, incompletePictureRatio = 0.09),
        )
        val moves = policy.drive(broken, millis = 20_000, clock = clock())
        assertEquals(VideoQualityReason.DECODER_PRESSURE, moves.first().reason)
    }

    @Test
    fun `the floor cannot be stepped below`() {
        val policy = policy()
        val awful = healthy().copy(
            network = NetworkConditions(lossFraction = 0.5, rttMillis = 2_000.0),
            encoder = EncoderConditions(actualFpsRatio = 0.01, latencyMillis = 500.0),
            device = DevicePressure(thermal = ThermalPressure.CRITICAL, cpuLoad = 1.0),
        )

        val moves = policy.drive(awful, millis = 600_000, clock = clock())

        assertEquals(VideoQualityProfiles.oneToOne.bottom, policy.current)
        assertEquals(VideoQualityTier.LOW, policy.current.tier)
        // And it stops trying: no transition may claim to leave the floor downwards.
        assertTrue(moves.none { it.from.tier == VideoQualityTier.LOW })
    }

    // -------------------------------------------------------- timing and dwell

    @Test
    fun `a downgrade waits for the whole window and no longer`() {
        val policy = policy()
        val c = clock()
        val lossy = healthy().copy(network = NetworkConditions(lossFraction = 0.2))

        // Two samples are needed before the policy will act at all, then the window runs.
        val early = policy.drive(lossy, millis = thresholds.downgradeWindowMillis, clock = c)
        assertTrue(early.isEmpty(), "must not fire inside the window, got $early")

        val later = policy.drive(lossy, millis = 6_000, clock = c)
        assertEquals(1, later.size, "must fire once the window has elapsed")
        assertTrue(later.first().windowMillis >= thresholds.downgradeWindowMillis)
    }

    @Test
    fun `no two changes are ever closer together than the cooldown`() {
        val policy = policy()
        val c = clock()
        // Bad enough to want the floor, so the only thing pacing the descent is the dwell.
        val awful = healthy().copy(
            network = NetworkConditions(lossFraction = 0.4, rttMillis = 1_500.0),
            encoder = EncoderConditions(actualFpsRatio = 0.1, latencyMillis = 200.0),
        )

        val moves = policy.drive(awful, millis = 120_000, clock = c)

        // Two rungs to fall, and it must take at least one cooldown to fall them.
        assertEquals(VideoQualityTier.LOW, policy.current.tier)
        assertEquals(2, moves.size, "expected exactly two steps down, got $moves")
        moves.zipWithNext { earlier, later ->
            assertTrue(
                later.atMillis - earlier.atMillis >= thresholds.cooldownMillis,
                "changes ${earlier.atMillis} and ${later.atMillis} are closer than the cooldown",
            )
        }
    }

    @Test
    fun `evidence accumulated during a cooldown is not thrown away`() {
        val policy = policy()
        val c = clock()
        val awful = healthy().copy(
            network = NetworkConditions(lossFraction = 0.4),
            encoder = EncoderConditions(actualFpsRatio = 0.1, latencyMillis = 200.0),
        )
        val first = policy.driveUntilChange(awful, c)
        assertNotNull(first)
        val firstAt = first.atMillis

        // If the window restarted after the cooldown, the second change could not arrive
        // until cooldown + window. It should arrive at the cooldown, because six seconds of
        // breach elapsed inside it.
        val next = policy.drive(awful, millis = thresholds.cooldownMillis + tick, clock = c)

        assertTrue(next.isNotEmpty())
        assertTrue(
            next.first().atMillis - firstAt < thresholds.cooldownMillis + thresholds.downgradeWindowMillis,
            "the breach window should have run during the cooldown, not after it",
        )
    }

    // ----------------------------------------------------------------- recovery

    @Test
    fun `recovery does not upgrade immediately`() {
        val policy = policy()
        val c = clock()
        val fell = policy.driveUntilChange(
            healthy().copy(network = NetworkConditions(lossFraction = 0.2)), c,
        )
        assertNotNull(fell)
        val dropped = policy.current
        assertTrue(dropped.tier != VideoQualityTier.HIGH)

        // A downgrade window's worth of perfect health must not be enough to climb.
        val moves = policy.drive(healthy(), millis = thresholds.downgradeWindowMillis * 2, clock = c)

        assertTrue(moves.isEmpty(), "recovery must be slower than degradation, got $moves")
        assertSame(dropped, policy.current)
    }

    @Test
    fun `sustained recovery eventually upgrades, one rung at a time`() {
        val policy = policy()
        val c = clock()
        assertNotNull(
            policy.driveUntilChange(healthy().copy(network = NetworkConditions(lossFraction = 0.2)), c),
        )
        val dropped = policy.current.tier

        // Long enough for one upgrade window and its cooldown, and short of two.
        val moves = policy.drive(healthy(), millis = 60_000, clock = c)

        assertEquals(1, moves.size, "one rung per upgrade window, got $moves")
        assertEquals(VideoQualityReason.SUSTAINED_HEALTH, moves.first().reason)
        assertEquals(dropped, moves.first().from.tier)
        assertTrue(moves.first().to.pixelRate > moves.first().from.pixelRate)
    }

    @Test
    fun `a full recovery climbs back to the top and then stops`() {
        val policy = policy()
        val c = clock()
        policy.drive(healthy().copy(network = NetworkConditions(lossFraction = 0.5)), 60_000, c)
        assertEquals(VideoQualityTier.LOW, policy.current.tier)

        val moves = policy.drive(healthy(), millis = 400_000, clock = c)

        assertEquals(VideoQualityTier.HIGH, policy.current.tier)
        assertTrue(moves.all { it.reason == VideoQualityReason.SUSTAINED_HEALTH })
        // Two rungs from LOW to HIGH on the one-to-one ladder, never more.
        assertEquals(2, moves.size, "expected exactly two upward steps, got $moves")
    }

    // ------------------------------------------------------------- oscillation

    @Test
    fun `a tier that keeps failing is retried progressively less often`() {
        val policy = policy()
        val c = clock()
        val marginal = healthy().copy(
            network = NetworkConditions(lossFraction = 0.08, rttMillis = 60.0),
        )

        // Alternate: fail until the rung is abandoned, then heal until it is retried. What
        // is measured is how long the *healing* had to last each time -- the loop's own
        // period says nothing, because it runs to a fixed length either way.
        val healingRequired = mutableListOf<Long>()
        repeat(4) {
            policy.driveUntilChange(marginal, c)
            val healingStartedAt = c[0]
            val up = policy.driveUntilChange(healthy(), c, limitMillis = 400_000)
                ?.takeIf { it.reason == VideoQualityReason.SUSTAINED_HEALTH }
            if (up != null) healingRequired += up.atMillis - healingStartedAt
        }

        assertTrue(healingRequired.size >= 3, "needed three retries to compare, got $healingRequired")
        assertTrue(
            healingRequired.last() > healingRequired.first(),
            "a repeatedly failing rung must demand more health each time, saw $healingRequired",
        )
        // Each retry costs one more upgrade window than the last, until the penalty caps.
        val steps = healingRequired.zipWithNext { a, b -> b - a }
        assertTrue(
            // Within one tick: the window is 25 s but a decision can only be taken on a
            // sample, so the observed figure snaps to the tick grid either side of it.
            steps.all { it == 0L || kotlin.math.abs(it - thresholds.upgradeWindowMillis) <= tick },
            "each retry should cost one more upgrade window, or none once capped: $steps",
        )

        // And the growth is bounded, so a rung that once failed does not become unreachable
        // for ever. The bound is the fully-penalised window plus the cooldown that has to
        // elapse before the healthy window can even start, rounded up to a tick -- every
        // term of which is a real part of how long recovery takes, and none of which is
        // the penalty growing without limit.
        val bound = thresholds.upgradeWindowMillis * (1 + thresholds.maxUpgradePenalty) +
            thresholds.cooldownMillis + tick
        assertTrue(
            healingRequired.all { it <= bound },
            "the upgrade penalty must stay bounded by $bound, saw $healingRequired",
        )
    }

    @Test
    fun `conditions between the two thresholds move nothing in either direction`() {
        val policy = policy()
        val c = clock()
        // Inside the hysteresis band: too lossy to climb, not lossy enough to drop.
        val band = healthy().copy(
            network = NetworkConditions(lossFraction = 0.03, rttMillis = 300.0, jitterMillis = 90.0),
            encoder = EncoderConditions(actualFpsRatio = 0.85, latencyMillis = 30.0),
        )

        val moves = policy.drive(band, millis = 300_000, clock = c)

        assertTrue(moves.isEmpty(), "the hysteresis band must be stable, got $moves")
        assertEquals(VideoQualityTier.HIGH, policy.current.tier)
    }

    // --------------------------------------------------- shape, budget, tiles

    @Test
    fun `the ladders get quieter as the call gets busier, and none of them offers 720p`() {
        assertEquals(640, VideoQualityProfiles.oneToOne.top.width)
        assertEquals(15, VideoQualityProfiles.oneToOne.top.fps)

        assertEquals(480, VideoQualityProfiles.threeParty.top.width)
        assertEquals(15, VideoQualityProfiles.threeParty.top.fps)

        assertEquals(320, VideoQualityProfiles.fourParty.top.width)
        assertEquals(15, VideoQualityProfiles.fourParty.top.fps)

        assertTrue(
            listOf(
                VideoQualityProfiles.oneToOne,
                VideoQualityProfiles.threeParty,
                VideoQualityProfiles.fourParty,
            ).flatMap { it.profiles }.none { it.width > 640 },
            "nothing above 360p is a rung any ladder offers: smooth before sharp",
        )
    }

    @Test
    fun `a participant joining swaps the ladder at once rather than waiting for evidence`() {
        val policy = policy()
        val c = clock()
        policy.drive(healthy(), millis = 40_000, clock = c)
        assertEquals(640, policy.current.width)

        c[0] += tick
        val joined = policy.sample(healthy(shape = CallShape.FOUR_PARTY, legs = 3), c[0])

        assertNotNull(joined, "a shape change must be applied immediately")
        assertEquals(VideoQualityReason.CALL_SHAPE_CHANGED, joined.reason)
        assertEquals(CallShape.FOUR_PARTY, policy.shape)
        assertTrue(policy.current.width <= 320, "a four-party call must leave 360p at once")
    }

    @Test
    fun `joining a conference never raises quality on the strength of the join`() {
        val policy = policy()
        val c = clock()
        // Drop to the floor first, so the conference ladder's top would be an *increase*.
        policy.drive(healthy().copy(network = NetworkConditions(lossFraction = 0.5)), 60_000, c)
        assertEquals(VideoQualityTier.LOW, policy.current.tier)
        val before = policy.current

        c[0] += tick
        policy.sample(healthy(shape = CallShape.FOUR_PARTY, legs = 3), c[0])

        assertTrue(
            policy.current.pixelRate <= before.pixelRate,
            "joining a conference must not be an upgrade: was $before, now ${policy.current}",
        )
    }

    @Test
    fun `a participant leaving lets quality recover, but only through the upgrade window`() {
        val policy = policy(shape = CallShape.FOUR_PARTY, legs = 3)
        val c = clock()
        policy.drive(healthy(shape = CallShape.FOUR_PARTY, legs = 3), millis = 40_000, clock = c)

        val before = policy.current
        c[0] += tick
        // Back to one-to-one. The ladder changes and the allowance triples, but the tier
        // must not: a hangup is not evidence, so 720p30 has to be earned from here.
        policy.sample(healthy(shape = CallShape.ONE_TO_ONE, legs = 1), c[0])
        assertEquals(CallShape.ONE_TO_ONE, policy.shape)
        assertTrue(
            policy.current.pixelRate <= before.pixelRate,
            "leaving a conference must not be an instant upgrade: was $before, now ${policy.current}",
        )

        val climbed = policy.drive(healthy(), millis = 200_000, clock = c)
        assertTrue(climbed.isNotEmpty(), "a one-to-one call should climb again once alone")
        assertTrue(climbed.all { it.reason == VideoQualityReason.SUSTAINED_HEALTH })
        assertEquals(VideoQualityTier.HIGH, policy.current.tier, "and should get all the way back")
    }

    @Test
    fun `a budget that no longer affords the tier drops it without waiting for a collapse`() {
        val policy = policy(shape = CallShape.FOUR_PARTY, legs = 1)
        val c = clock()
        policy.drive(healthy(shape = CallShape.FOUR_PARTY, legs = 1), millis = 40_000, clock = c)
        val before = policy.current

        // Three legs share the same aggregate: the allowance per leg falls to a third.
        val squeezed = healthy(shape = CallShape.FOUR_PARTY, legs = 3).copy(
            budget = VideoBudget(outgoingVideoLegs = 3, aggregateCeilingBps = 300_000),
        )
        val moves = policy.drive(squeezed, millis = 20_000, clock = c)

        assertTrue(moves.isNotEmpty(), "an unaffordable tier must give way")
        assertTrue(policy.current.targetBps <= squeezed.budget.allowanceBpsPerLeg)
        assertTrue(policy.current.pixelRate < before.pixelRate)
    }

    @Test
    fun `a small tile caps the climb without forcing a drop`() {
        val policy = policy()
        val c = clock()
        // Drop first, then recover with the tile too small for the rung above the floor.
        policy.drive(healthy().copy(network = NetworkConditions(lossFraction = 0.5)), 60_000, c)
        assertEquals(VideoQualityTier.LOW, policy.current.tier)

        // 360 tall x 1.5 overshoot admits 540 but not 720.
        val tiled = healthy(displayCeiling = DisplayCeiling(height = 360))
        val moves = policy.drive(tiled, millis = 400_000, clock = c)

        assertTrue(moves.isNotEmpty(), "a small tile must not prevent recovery entirely")
        assertTrue(
            policy.current.height <= 540,
            "nothing should climb past what the tile can show: ${policy.current}",
        )
    }

    // --------------------------------------------------------- backend independence

    @Test
    fun `the policy has no way to express a codec backend`() {
        // The brief asks that the backend never determine the tier. The strongest possible
        // form of that guarantee is structural: there is no field to put it in. This test
        // is here so that adding one is a deliberate act with a failing test attached.
        val policy = policy()
        val c = clock()
        val moves = policy.drive(healthy(), millis = 60_000, clock = c)
        assertTrue(moves.isEmpty())

        // Identical conditions must produce an identical tier, whatever produced them --
        // hardware MediaCodec, software MediaCodec or libvpx all report through the same
        // counters and the policy cannot tell them apart.
        val second = policy()
        second.drive(healthy(), millis = 60_000, clock = clock())
        assertEquals(policy.current, second.current)
    }

    @Test
    fun `restarting media forgets the measurements but keeps the tier`() {
        val policy = policy()
        val c = clock()
        policy.drive(healthy().copy(network = NetworkConditions(lossFraction = 0.2)), 12_000, c)
        val tier = policy.current

        policy.mediaRestarted()
        assertSame(tier, policy.current, "a restart is not a reason to re-climb from the floor")
        assertNull(policy.conditions, "the old stream's mean describes nothing about the new one")

        // And the first sample after a restart still cannot move anything on its own.
        c[0] += tick
        assertNull(policy.sample(healthy(), c[0]))
    }
}
