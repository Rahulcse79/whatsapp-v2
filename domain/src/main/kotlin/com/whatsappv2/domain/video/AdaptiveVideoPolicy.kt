package com.whatsappv2.domain.video

/**
 * Why a tier moved. Recorded on every transition so a log line says what it was reacting to.
 *
 * One reason is chosen for the record even when several apply, in the order this enum
 * declares them — the *worst* pressure first. The full set is kept in
 * [VideoQualityTransition.allReasons], so nothing is lost; what is avoided is a log line
 * that lists five things and lets the reader pick.
 */
enum class VideoQualityReason {
    /**
     * The platform is shedding clock, and it is read first because nothing else will fix it.
     *
     * Sufficient on its own only at [ThermalPressure.CRITICAL]. At SEVERE it corroborates a
     * measured problem rather than causing a downgrade by itself — see [reasonsToLeave], and
     * the SM-E236B measurement that forced the distinction.
     */
    THERMAL_PRESSURE,

    /** The encoder is not producing the frame rate it was configured for. */
    ENCODER_PRESSURE,

    /** The far end is not receiving what was sent: loss, or it keeps asking for keyframes. */
    NETWORK_LOSS,

    /** Round trip or jitter beyond what this tier's pacing can absorb. */
    NETWORK_LATENCY,

    /** Incoming pictures are incomplete or arriving below their negotiated rate. */
    DECODER_PRESSURE,

    /** Sustained process CPU load. Never sufficient on its own — see [DevicePressure.cpuLoad]. */
    CPU_PRESSURE,

    /** The aggregate outgoing allowance no longer covers this tier across every leg. */
    BUDGET_PRESSURE,

    /** The receiver's tile cannot use this many pixels. Caps upgrades; never forces a drop. */
    DISPLAY_CEILING,

    /** Everything has been comfortably within this tier's requirements long enough to climb. */
    SUSTAINED_HEALTH,

    /** The call shape changed — a participant joined or left — so the ladder changed under us. */
    CALL_SHAPE_CHANGED,
}

/** One tier change, with enough of its cause attached to be read months later. */
data class VideoQualityTransition(
    val from: VideoQualityProfile,
    val to: VideoQualityProfile,
    val reason: VideoQualityReason,
    val allReasons: Set<VideoQualityReason>,
    val atMillis: Long,
    /** How long the evidence was sustained before this fired. */
    val windowMillis: Long,
    /** The bitrate ceiling to apply, after the budget has had its say. */
    val maxBps: Int,
    /** The smoothed conditions that justified it, for the record. */
    val conditions: VideoConditions,
) {
    /** True when this is a step down the ladder. */
    val isDowngrade: Boolean get() = to.pixelRate < from.pixelRate || to.fps < from.fps

    /**
     * `540p30 -> 360p24 reason=ENCODER_PRESSURE target_fps=30 actual_fps=18.4 window=6000ms`
     *
     * One line, and only on a transition. The policy is sampled every couple of seconds and
     * a line per sample is how a log stops being read — see `videoPipelineTraceFragment`,
     * which learned the same thing.
     */
    override fun toString(): String = buildString {
        append(from).append(" -> ").append(to)
        append(" reason=").append(reason)
        if (allReasons.size > 1) {
            append(" also=").append(allReasons.minus(reason).joinToString(",") { it.name })
        }
        append(" target_fps=").append(from.fps)
        append(" actual_fps=").append(fpsFormat(from.fps * conditions.encoder.actualFpsRatio))
        append(" loss=").append(percentFormat(conditions.network.lossFraction))
        append(" rtt=").append(conditions.network.rttMillis.toLong()).append("ms")
        append(" thermal=").append(conditions.device.thermal)
        append(" legs=").append(conditions.budget.outgoingVideoLegs)
        append(" maxbps=").append(maxBps)
        append(" window=").append(windowMillis).append("ms")
    }

    private fun fpsFormat(value: Double) = ((value * 10).toLong() / 10.0).toString()
    private fun percentFormat(value: Double) = "${(value * 1000).toLong() / 10.0}%"
}

/**
 * Thresholds for one direction of travel.
 *
 * Two instances, deliberately far apart: [AdaptiveVideoThresholds.downgrade] is what a
 * tier must fail to keep, [AdaptiveVideoThresholds.upgrade] is what the *next* tier up must
 * be comfortably able to keep. The gap between them is the hysteresis band — conditions in
 * between hold the current tier and move nothing, which is what stops 720 -> 540 -> 720.
 */
data class VideoQualityThresholds(
    /** Encoder output rate as a fraction of the configured rate. */
    val encoderFpsRatio: Double,
    /** Encode latency as a multiple of the frame budget (`1000/fps`). */
    val encodeLatencyFactor: Double,
    val lossFraction: Double,
    val rttMillis: Double,
    val jitterMillis: Double,
    val feedbackPerSecond: Double,
    val decoderFpsRatio: Double,
    val incompletePictureRatio: Double,
    val thermal: ThermalPressure,
    val cpuLoad: Double,
)

/**
 * The tuning, in one place, with every number's reasoning next to it.
 *
 * ## Windows, and why they are what they are
 *
 * The brief asks for a 3-5 second downgrade window and 20-30 for upgrades. The binding
 * constraint is the telemetry cadence, which is not ours to choose freely: the native frame
 * counters are emitted by `vid_stream.c` every `PJMEDIA_VID_STREAM_COUNTER_LOG_MSEC`, which
 * is 5000 ms. A window shorter than that can contain no new encoder evidence at all — it
 * would be deciding on a stale reading, repeatedly, and firing on the first one that
 * happened to be bad.
 *
 * So [downgradeWindowMillis] is 10000 — two consecutive counter readings at that cadence.
 * That is slower than the brief's 3-5 s and is the honest number rather than a flattering
 * one: a 4000 ms window would fire on a single reading dressed up as a sustained one,
 * which is precisely the "one noisy measurement" the brief forbids. Lowering the native
 * interval to 2000 ms was implemented and then reverted, because it made this phase depend
 * on a native rebuild the local toolchain cannot currently produce (swig-java is absent);
 * this constant and `RealPjsipCoreGateway.VIDEO_QUALITY_TICK_MILLIS` are the two places to
 * change together if that interval is ever lowered.
 *
 * Network evidence is different and better. RTCP is read in-process on every tick, so
 * loss, RTT and jitter refresh at the sample rate rather than at the counter rate — a
 * network collapse is *seen* within one tick and acted on at the end of the window. The
 * window bounds when we act, not when we notice.
 */
data class AdaptiveVideoThresholds(
    val downgrade: VideoQualityThresholds = VideoQualityThresholds(
        // 0.75: a tier that cannot deliver three quarters of its frame rate is the wrong
        // tier. Not lower -- Phase 3's healthy baseline itself sat at 22.9/30 = 0.76 on one
        // handset, so a threshold at 0.8 would have called a working call broken.
        encoderFpsRatio = 0.75,
        // 1.5x the frame budget. At 30 fps that is 50 ms a frame, which caps the achievable
        // rate at 20 -- measurably not 30, whatever the rate controller reports.
        encodeLatencyFactor = 1.5,
        // 5%. VP8 without temporal layers loses a whole picture per lost packet run; past
        // this the far end spends more time asking for keyframes than showing pictures.
        lossFraction = 0.05,
        rttMillis = 400.0,
        jitterMillis = 120.0,
        // Two keyframe requests a second means the far end's reference chain is not holding.
        feedbackPerSecond = 2.0,
        decoderFpsRatio = 0.60,
        // 2%. Above this the remote picture visibly stutters.
        incompletePictureRatio = 0.02,
        thermal = ThermalPressure.SEVERE,
        cpuLoad = 0.85,
    ),
    val upgrade: VideoQualityThresholds = VideoQualityThresholds(
        // Comfortably, not just barely: the next rung costs more than this one, so keeping
        // the current rung at 0.95 is the evidence that there is headroom to spend.
        encoderFpsRatio = 0.95,
        encodeLatencyFactor = 0.80,
        lossFraction = 0.01,
        rttMillis = 200.0,
        jitterMillis = 50.0,
        // 1.0/s, not 0.2. Measured: a *healthy* leg on this hardware carries 4-12 keyframe
        // requests per 15 s trace interval -- 0.27 to 0.8/s -- in runs whose VP8 validity
        // counters were clean and whose frame rates were 30 both ways. A gate at 0.2 is
        // therefore below the healthy background and blocks recovery for ever; it was
        // measured doing exactly that, `blocked by fb=0.39>0.2` on every sample of a
        // 200-second window in which loss was 0 and round trip 15 ms.
        //
        // Requests also spike immediately after a tier change, because the far end's decoder
        // is newly built and asks for its first reference -- so a gate this tight is at its
        // tightest precisely when recovery is being attempted. 1.0 sits above the healthy
        // background and well below the downgrade threshold of 2.0, leaving the hysteresis
        // band intact.
        feedbackPerSecond = 1.0,
        decoderFpsRatio = 0.90,
        incompletePictureRatio = 0.002,
        // SEVERE, so only CRITICAL bars a climb -- and that is a measurement, not leniency.
        // Both test handsets report SEVERE for the whole of any video call: an SM-E236B at
        // skin 42.6 C / AP 51.7 C while capturing 30.0 fps at 0% loss, an SM-M146B likewise.
        // A gate at LIGHT or MODERATE is therefore unreachable on this hardware, and the
        // policy becomes a one-way ratchet: quality falls once and never returns, which
        // fails "recover quality conservatively" outright.
        //
        // The symmetry is the argument. A downgrade does not act on SEVERE alone because the
        // media may be fine; for the same reason an upgrade is not *blocked* by SEVERE alone
        // when the media is comfortably fine. Device signals corroborate, measurements
        // decide, in both directions.
        //
        // Climbing does raise load, so a climb at SEVERE can reach CRITICAL and be undone.
        // That is what [oscillationMemoryMillis] and the upgrade penalty are for: a rung
        // that keeps failing is retried on a window up to four times longer, so this settles
        // instead of flickering. Verified by `a tier that keeps failing is retried
        // progressively less often`.
        thermal = ThermalPressure.SEVERE,
        cpuLoad = 0.65,
    ),
    /** See the class doc: two consecutive native counter readings at their 5 s cadence. */
    val downgradeWindowMillis: Long = 10_000,
    /**
     * 25 s, inside the brief's 20-30 s band and five native readings.
     *
     * Two and a half times the downgrade window. Recovery being slower than degradation is the whole
     * asymmetry the brief asks for: dropping early costs a little sharpness, climbing early
     * costs a visible stall and then a drop back.
     */
    val upgradeWindowMillis: Long = 25_000,
    /**
     * Minimum dwell after any change, in either direction.
     *
     * 10 s. A tier change re-configures the encoder and, where the resolution moved, costs
     * a keyframe; doing that twice inside ten seconds is worse for the picture than staying
     * one rung too low. This is what bounds the *rate* of change; the windows bound the
     * evidence.
     */
    val cooldownMillis: Long = 10_000,
    /**
     * A tier that failed shortly after being climbed to is not climbed to again as readily.
     *
     * Cooldown alone does not stop oscillation, it only slows it: conditions that sit on the
     * boundary will still produce 720 -> 540 -> 720 -> 540 for ever, one change per
     * cooldown. So a rung that is left within [oscillationMemoryMillis] of being reached
     * earns a penalty, and its upgrade window is multiplied by `1 + penalty` up to
     * [maxUpgradePenalty]. The third attempt at a rung that keeps failing needs 75 s of
     * health rather than 25, and the fourth 100 -- which is the point at which a boundary
     * condition produces one change every minute and a half instead of one every ten
     * seconds, and stops being visible as flicker.
     */
    val oscillationMemoryMillis: Long = 60_000,
    val maxUpgradePenalty: Int = 3,
)

/**
 * The adaptive video quality state machine.
 *
 * ## What it is
 *
 * A pure function of (smoothed conditions, elapsed time) onto a tier, with a clock passed
 * in rather than read. No Android, no PJSIP, no coroutines, no logging — so every rule in
 * it is a unit test rather than a device session, and the device sessions are spent on
 * whether the *media layer* applied the decision correctly.
 *
 * ## How a decision is reached
 *
 * Each [sample] folds the raw conditions into the smoother, then asks two questions of the
 * result: can the current tier be kept, and is there headroom for the next one up. The
 * answers accumulate against a clock — [AdaptiveVideoThresholds.downgradeWindowMillis] of
 * sustained failure to move down, four times that of sustained headroom to move up — and a
 * sample that answers neither resets both accumulators. A change starts a cooldown during
 * which nothing moves at all.
 *
 * Downgrades skip rungs when the evidence justifies it: thermal CRITICAL or a budget that
 * has just been cut by a joining participant does not step politely down one rung at a
 * time. Upgrades never skip.
 *
 * ## What it deliberately does not do
 *
 * It does not touch media. It returns a [VideoQualityTransition] and the caller applies it;
 * that separation is what lets the apply path be rate-limited, serialized onto PJSIP's
 * executor, and made keyframe-safe without any of that leaking in here.
 *
 * Not thread safe, by the same argument as [VideoConditionsSmoother]: one caller, one tick.
 */
class AdaptiveVideoPolicy(
    shape: CallShape,
    private val thresholds: AdaptiveVideoThresholds = AdaptiveVideoThresholds(),
    private val smoother: VideoConditionsSmoother = VideoConditionsSmoother(),
    initialBudget: VideoBudget = VideoBudget(outgoingVideoLegs = 1),
    initialDisplayCeiling: DisplayCeiling = DisplayCeiling.UNCONSTRAINED,
) {
    private var ladder: VideoQualityLadder = VideoQualityProfiles.forShape(shape)

    /** The rung in force. Starts at the best the budget and the tile can actually use. */
    var current: VideoQualityProfile = ladder.bestAffordable(
        allowanceBps = initialBudget.allowanceBpsPerLeg,
        displayCeiling = initialDisplayCeiling,
    )
        private set

    /** The shape of the call this policy is adapting. Changes when a participant joins. */
    var shape: CallShape = shape
        private set

    private var breachSinceMillis: Long? = null
    private var healthySinceMillis: Long? = null
    private var lastChangeAtMillis: Long? = null
    private var lastReasons: Set<VideoQualityReason> = emptySet()

    /** When each rung was last arrived at, so a rung that fails quickly can be penalised. */
    private val arrivedAtMillis = mutableMapOf<VideoQualityTier, Long>()

    /** How many times each rung has been abandoned soon after being reached. */
    private val upgradePenalty = mutableMapOf<VideoQualityTier, Int>()

    /** The smoothed conditions as they were at the last [sample], or null before the first. */
    val conditions: VideoConditions? get() = smoother.current

    /** The reasons the current tier is under pressure right now, whether or not it has moved. */
    val pressure: Set<VideoQualityReason> get() = lastReasons

    /**
     * Folds one raw sample in and returns a transition, or null to stay where we are.
     *
     * Null is the overwhelmingly common answer and means nothing needs to happen. The
     * caller applies a non-null result and must not re-ask until it has: the policy has
     * already moved [current] and started the cooldown.
     */
    fun sample(raw: VideoConditions, nowMillis: Long): VideoQualityTransition? {
        val shapeChange = reshapeIfNeeded(raw, nowMillis)
        if (shapeChange != null) return shapeChange

        val now = smoother.accept(raw)
        if (smoother.samples < VideoConditionsSmoother.MIN_SAMPLES) {
            lastReasons = emptySet()
            return null
        }

        val failing = reasonsToLeave(now)
        lastReasons = failing

        if (inCooldown(nowMillis)) {
            // Evidence still accumulates through a cooldown -- what is suppressed is acting
            // on it. Otherwise a ten-second cooldown would be followed by a fresh six-second
            // window on a call that has been visibly broken for sixteen seconds.
            track(failing.isNotEmpty(), canClimb(now), nowMillis)
            return null
        }

        track(failing.isNotEmpty(), canClimb(now), nowMillis)

        downgradeIfDue(now, failing, nowMillis)?.let { return it }
        return upgradeIfDue(now, nowMillis)
    }

    /**
     * Forgets the smoothed history and the accumulated windows, keeping the tier.
     *
     * For the moments when the media underneath is replaced but the call is not: video off
     * and on, hold and unhold, a camera switch, foreground and background. The stream's
     * counters restart at zero, so the mean across that boundary describes nothing, and a
     * breach window half-elapsed against the old stream is not evidence about the new one.
     *
     * The tier is kept deliberately. It was reached by evidence and the device has not
     * changed; re-climbing the ladder from the floor after every hold would be the
     * oscillation this policy exists to prevent, arrived at from the other direction.
     */
    fun mediaRestarted() {
        smoother.reset()
        breachSinceMillis = null
        healthySinceMillis = null
        lastReasons = emptySet()
    }

    /**
     * True when [profile] is what the media layer should currently be configured for.
     *
     * For the apply path to check itself against, rather than tracking a second copy of
     * the answer and risking the two disagreeing.
     */
    fun isCurrent(profile: VideoQualityProfile): Boolean = profile == current

    /** The bitrate ceiling for the current rung under [budget]. */
    fun maxBpsFor(budget: VideoBudget): Int = budget.maxBpsFor(current)

    // --------------------------------------------------------------- shape changes

    /**
     * Swaps the ladder when the call becomes a conference, or stops being one.
     *
     * Immediate and outside the windows, because it is not a measurement: a third
     * participant joining means three encodes are about to start whether or not the
     * evidence has caught up, and waiting six seconds to find out guarantees the collapse
     * the conference ladder exists to avoid. The same applies in reverse for a participant
     * leaving, except that *climbing* after a shape change still goes through the windows —
     * the new shape's allowance is applied at once, the new shape's headroom is earned.
     */
    private fun reshapeIfNeeded(raw: VideoConditions, nowMillis: Long): VideoQualityTransition? {
        if (raw.shape == shape) return null
        val from = current
        shape = raw.shape
        ladder = VideoQualityProfiles.forShape(raw.shape)
        smoother.reset()
        breachSinceMillis = null
        healthySinceMillis = null

        // The tier is re-derived rather than carried across: the rung named HIGH means a
        // different picture on every ladder -- 540p30 at two participants, 360p24 at three,
        // 360p20 at four -- so the name alone says nothing about the picture.
        //
        // Capped at the picture we were already sending, in BOTH directions. Joining a
        // conference obviously must not raise quality; leaving one must not either, and
        // that half is the easier one to get wrong. A four-party call falling to two has a
        // larger allowance and a taller ladder available the instant the leg closes, and
        // taking the top of it there would be an upgrade granted for a hangup rather than
        // earned by evidence -- the brief's "quality may conservatively recover" is a climb
        // through the upgrade window, not a jump. So the shape change lands on a rung no
        // richer than the current one, and the window does the rest. This matters at every
        // membership step now that four, three and two are three different ladders.
        val capped = ladder.bestAffordableNotExceeding(
            pixelRateCeiling = from.pixelRate,
            allowanceBps = raw.budget.allowanceBpsPerLeg,
            displayCeiling = raw.displayCeiling,
        )
        current = capped
        lastChangeAtMillis = nowMillis
        arrivedAtMillis[capped.tier] = nowMillis
        smoother.accept(raw)

        if (capped == from) return null
        return VideoQualityTransition(
            from = from,
            to = capped,
            reason = VideoQualityReason.CALL_SHAPE_CHANGED,
            allReasons = setOf(VideoQualityReason.CALL_SHAPE_CHANGED),
            atMillis = nowMillis,
            windowMillis = 0,
            maxBps = raw.budget.maxBpsFor(capped),
            conditions = raw,
        )
    }

    // ------------------------------------------------------------------- the rules

    /**
     * Every reason the current rung cannot be kept. Empty means it can.
     *
     * ## Measured pressure, and pressure that only corroborates
     *
     * The signals divide into two kinds, and conflating them was a real fault in the first
     * version of this policy.
     *
     * *Measured* pressure is something a counter proves about the media: the encoder is not
     * producing its frame rate, the far end is not receiving what was sent, pictures are
     * arriving incomplete. Any one of them is sufficient on its own, because each one *is*
     * the video being worse.
     *
     * *Device* pressure — thermal and CPU — is a statement about the handset, not about the
     * stream, and it can be true while the stream is perfectly healthy. Measured on an
     * SM-E236B, 2026-09-27: the platform reported `THERMAL_STATUS_SEVERE` (skin 42.6 C at
     * severity 3, AP 51.7 C) throughout a call that was capturing 30.0 fps, encoding 30.1,
     * losing 0% and running a 7 ms round trip. Thermal alone walked that call from its top rung
     * to the 360p15 floor in three steps while every measurement said the tier was
     * sustainable — and because that handset's skin status sits at 3 whenever the camera
     * runs, it could never have climbed back. "Best sustainable quality" became "the floor,
     * for ever", on the strength of a signal that was not about the video at all.
     *
     * So [ThermalPressure.SEVERE] and high CPU load now *corroborate* and are recorded when
     * they do, but neither moves a tier while the pipeline is demonstrably meeting its
     * target. This is exactly the treatment Phase 3's evidence forced on CPU load — an
     * encode collapse with four and a half cores idle — applied to the other device signal
     * for the same reason.
     *
     * [ThermalPressure.CRITICAL] remains sufficient alone. At that point the platform is
     * actively cutting the device back, so a stream that is healthy now will not be, and
     * waiting for a counter to prove it is waiting for the picture to break first.
     */
    private fun reasonsToLeave(now: VideoConditions): Set<VideoQualityReason> {
        val limit = thresholds.downgrade

        // What a counter proves about the media. Any one of these is enough on its own.
        val measured = buildSet {
            if (now.encoder.actualFpsRatio < limit.encoderFpsRatio ||
                now.encoder.latencyMillis > current.frameBudgetMillis * limit.encodeLatencyFactor
            ) {
                add(VideoQualityReason.ENCODER_PRESSURE)
            }
            if (now.network.lossFraction > limit.lossFraction ||
                now.network.feedbackPerSecond > limit.feedbackPerSecond
            ) {
                add(VideoQualityReason.NETWORK_LOSS)
            }
            if (now.network.rttMillis > limit.rttMillis ||
                now.network.jitterMillis > limit.jitterMillis
            ) {
                add(VideoQualityReason.NETWORK_LATENCY)
            }
            if (now.decoder.actualFpsRatio < limit.decoderFpsRatio ||
                now.decoder.incompletePictureRatio > limit.incompletePictureRatio
            ) {
                add(VideoQualityReason.DECODER_PRESSURE)
            }
        }

        val thermalCritical = now.device.thermal >= ThermalPressure.CRITICAL
        val thermalCorroborates = now.device.thermal >= limit.thermal && measured.isNotEmpty()
        val cpuCorroborates = now.device.cpuLoad > limit.cpuLoad && measured.isNotEmpty()

        // Ordered worst-first for the headline reason; see [VideoQualityReason].
        return buildSet {
            if (thermalCritical || thermalCorroborates) add(VideoQualityReason.THERMAL_PRESSURE)
            addAll(measured)
            if (cpuCorroborates) add(VideoQualityReason.CPU_PRESSURE)
            if (!now.budget.affords(current)) add(VideoQualityReason.BUDGET_PRESSURE)
        }
    }

    /** True when every requirement for the rung above is comfortably met. */
    private fun canClimb(now: VideoConditions): Boolean = blockingUpgrade(now).isEmpty()

    /**
     * Which upgrade requirements are not met, named, or empty when the rung above is ready.
     *
     * Named rather than reduced to a boolean because "quality did not come back" is otherwise
     * unanswerable in the field: a downgrade logs its reason and a climb that never happens
     * logs nothing at all, so the one thing a user would complain about was the one thing the
     * telemetry could not explain. Each entry is a short `field=value vs limit` so a single
     * held line says which gate to look at.
     *
     * Every gate is checked, not short-circuited on the first failure, because knowing that
     * two conditions are marginal is different from knowing one is — and the cost is a
     * handful of comparisons once per sample.
     */
    fun blockingUpgrade(now: VideoConditions): List<String> {
        val next = ladder.stepUp(current.tier) ?: return listOf("at-top")
        val limit = thresholds.upgrade
        fun fmt(value: Double) = ((value * 100).toLong() / 100.0).toString()

        return buildList {
            if (!now.budget.affords(next)) {
                add("budget=${next.targetBps}>${now.budget.allowanceBpsPerLeg}")
            }
            if (!now.displayCeiling.admits(next)) add("tile=${next.height}>${now.displayCeiling.height}")
            if (now.device.thermal > limit.thermal) add("thermal=${now.device.thermal}")
            if (now.device.cpuLoad > limit.cpuLoad) {
                add("cpu=${fmt(now.device.cpuLoad)}>${limit.cpuLoad}")
            }
            if (now.encoder.actualFpsRatio < limit.encoderFpsRatio) {
                add("encfps=${fmt(now.encoder.actualFpsRatio)}<${limit.encoderFpsRatio}")
            }
            val latencyCeiling = current.frameBudgetMillis * limit.encodeLatencyFactor
            if (now.encoder.latencyMillis > latencyCeiling) {
                add("enclat=${fmt(now.encoder.latencyMillis)}>${fmt(latencyCeiling)}")
            }
            if (now.network.lossFraction > limit.lossFraction) {
                add("loss=${fmt(now.network.lossFraction)}>${limit.lossFraction}")
            }
            if (now.network.feedbackPerSecond > limit.feedbackPerSecond) {
                add("fb=${fmt(now.network.feedbackPerSecond)}>${limit.feedbackPerSecond}")
            }
            if (now.network.rttMillis > limit.rttMillis) {
                add("rtt=${fmt(now.network.rttMillis)}>${limit.rttMillis}")
            }
            if (now.network.jitterMillis > limit.jitterMillis) {
                add("jitter=${fmt(now.network.jitterMillis)}>${limit.jitterMillis}")
            }
            if (now.decoder.actualFpsRatio < limit.decoderFpsRatio) {
                add("decfps=${fmt(now.decoder.actualFpsRatio)}<${limit.decoderFpsRatio}")
            }
            if (now.decoder.incompletePictureRatio > limit.incompletePictureRatio) {
                add("incomplete=${fmt(now.decoder.incompletePictureRatio)}>${limit.incompletePictureRatio}")
            }
        }
    }

    /** How long the current healthy run has lasted, or null when it is not healthy. */
    fun healthyForMillis(nowMillis: Long): Long? = healthySinceMillis?.let { nowMillis - it }

    /** The window this rung must currently earn to climb, penalty included. */
    fun upgradeWindowMillis(): Long {
        val next = ladder.stepUp(current.tier) ?: return 0
        return thresholds.upgradeWindowMillis * (1 + (upgradePenalty[next.tier] ?: 0)).toLong()
    }

    private fun track(breaching: Boolean, climbable: Boolean, nowMillis: Long) {
        if (breaching) {
            if (breachSinceMillis == null) breachSinceMillis = nowMillis
            healthySinceMillis = null
        } else {
            breachSinceMillis = null
            if (climbable) {
                if (healthySinceMillis == null) healthySinceMillis = nowMillis
            } else {
                healthySinceMillis = null
            }
        }
    }

    private fun inCooldown(nowMillis: Long): Boolean {
        val last = lastChangeAtMillis ?: return false
        return nowMillis - last < thresholds.cooldownMillis
    }

    private fun downgradeIfDue(
        now: VideoConditions,
        failing: Set<VideoQualityReason>,
        nowMillis: Long,
    ): VideoQualityTransition? {
        if (failing.isEmpty()) return null
        val since = breachSinceMillis ?: return null
        val held = nowMillis - since
        if (held < thresholds.downgradeWindowMillis) return null

        val target = downgradeTarget(now, failing) ?: return null
        return change(
            to = target,
            reason = failing.first(),
            allReasons = failing,
            windowMillis = held,
            now = now,
            nowMillis = nowMillis,
        )
    }

    /**
     * Where to land. One rung down normally; straight to what is affordable when the
     * pressure is not something a single step can answer.
     *
     * A budget that no longer affords the current rung has already told us where the
     * ceiling is, so stepping one rung towards it and waiting another six seconds is six
     * seconds of sending more than the allowance. Thermal CRITICAL is the same argument
     * about a device that is being actively cut back.
     */
    private fun downgradeTarget(
        now: VideoConditions,
        failing: Set<VideoQualityReason>,
    ): VideoQualityProfile? {
        val stepped = ladder.stepDown(current.tier)
        val jumpJustified = VideoQualityReason.BUDGET_PRESSURE in failing ||
            now.device.thermal >= ThermalPressure.CRITICAL
        if (!jumpJustified) return stepped

        val affordable = ladder.bestAffordable(now.budget.allowanceBpsPerLeg, now.displayCeiling)
        // Never *up* on a downgrade, and never past the floor.
        val candidate = when {
            affordable.pixelRate < current.pixelRate -> affordable
            else -> stepped
        }
        return candidate?.takeIf { it != current }
    }

    private fun upgradeIfDue(now: VideoConditions, nowMillis: Long): VideoQualityTransition? {
        val since = healthySinceMillis ?: return null
        val next = ladder.stepUp(current.tier) ?: return null
        val window = thresholds.upgradeWindowMillis *
            (1 + (upgradePenalty[next.tier] ?: 0)).toLong()
        val held = nowMillis - since
        if (held < window) return null

        return change(
            to = next,
            reason = VideoQualityReason.SUSTAINED_HEALTH,
            allReasons = setOf(VideoQualityReason.SUSTAINED_HEALTH),
            windowMillis = held,
            now = now,
            nowMillis = nowMillis,
        )
    }

    private fun change(
        to: VideoQualityProfile,
        reason: VideoQualityReason,
        allReasons: Set<VideoQualityReason>,
        windowMillis: Long,
        now: VideoConditions,
        nowMillis: Long,
    ): VideoQualityTransition {
        val from = current
        penaliseIfItDidNotHold(from.tier, nowMillis, leavingDownwards = to.pixelRate < from.pixelRate)

        current = to
        lastChangeAtMillis = nowMillis
        arrivedAtMillis[to.tier] = nowMillis
        breachSinceMillis = null
        healthySinceMillis = null

        return VideoQualityTransition(
            from = from,
            to = to,
            reason = reason,
            allReasons = allReasons,
            atMillis = nowMillis,
            windowMillis = windowMillis,
            maxBps = now.budget.maxBpsFor(to),
            conditions = now,
        )
    }

    /**
     * A rung abandoned downwards soon after being reached earns a longer wait next time.
     *
     * This is the part that actually stops oscillation. Cooldown limits how *often* a tier
     * can change; this limits how often the same *failed* climb is retried, by remembering
     * that it failed. Only downward departures count — leaving a rung by climbing past it
     * is a success, not a failure of that rung.
     */
    private fun penaliseIfItDidNotHold(
        tier: VideoQualityTier,
        nowMillis: Long,
        leavingDownwards: Boolean,
    ) {
        if (!leavingDownwards) return
        val arrived = arrivedAtMillis[tier] ?: return
        if (nowMillis - arrived > thresholds.oscillationMemoryMillis) return
        val penalty = (upgradePenalty[tier] ?: 0) + 1
        upgradePenalty[tier] = penalty.coerceAtMost(thresholds.maxUpgradePenalty)
    }
}
