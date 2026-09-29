package com.whatsappv2.domain.video

/**
 * One rung of the video quality ladder.
 *
 * A rung identity, not a resolution. What [HIGH] means in pixels is the ladder's business
 * and differs between a one-to-one call and a conference — see [VideoQualityLadder] — so
 * code that wants numbers asks the ladder for a [VideoQualityProfile] rather than reading
 * anything off the tier.
 *
 * Ordered highest first, which is the order the ladder is indexed in and the order the
 * state machine steps through. `ordinal` is deliberately not used for comparison anywhere:
 * a ladder may skip a rung (the conference one skips [MEDIUM_HIGH]) and an ordinal
 * comparison would then claim a step exists that the ladder does not offer.
 */
enum class VideoQualityTier {
    /** The best this build will ask for. 720p30 one-to-one, 540p24 in a conference. */
    HIGH,

    /**
     * 540p30, one-to-one only.
     *
     * A conference has no use for it: at three outgoing encodes the thing that runs out
     * is encode capacity, and 540p30 costs nearly what 720p30 does. The conference ladder
     * steps [HIGH] (540p24) straight to [MEDIUM] instead.
     */
    MEDIUM_HIGH,

    /**
     * 360p24. The conference default, and what the brief calls conference `NORMAL` — the
     * same resolution and frame rate under the name this enum already had for it.
     */
    MEDIUM,

    /** 360p15. The floor: below this video stops being worth sending at all. */
    LOW,
}

/**
 * Everything the media layer needs to configure one outgoing video stream.
 *
 * Bitrates are a band rather than a number because the encoder is given a ceiling and a
 * target and chooses between them; [minBps] is the point below which this resolution
 * stops being honest and the tier itself should give way, which is why it is stated here
 * next to the resolution it belongs to rather than derived at the call site.
 *
 * `maxBps` here is the profile's own ceiling. It is not necessarily what gets applied: a
 * full mesh divides an aggregate allowance between its legs and the smaller of the two
 * wins, see [VideoBudget.allowanceBpsPerLeg].
 */
data class VideoQualityProfile(
    val tier: VideoQualityTier,
    val width: Int,
    val height: Int,
    val fps: Int,
    val minBps: Int,
    val targetBps: Int,
    val maxBps: Int,
) {
    init {
        require(width > 0 && height > 0) { "resolution must be positive, was ${width}x$height" }
        require(fps > 0) { "fps must be positive, was $fps" }
        require(minBps in 1..targetBps) { "minBps $minBps must be in 1..$targetBps" }
        require(targetBps <= maxBps) { "targetBps $targetBps must not exceed maxBps $maxBps" }
    }

    /** Pixels per second, which is the thing an encoder actually spends its time on. */
    val pixelRate: Long get() = width.toLong() * height.toLong() * fps.toLong()

    /** The wall clock a frame gets if the encoder is to keep up with [fps]. */
    val frameBudgetMillis: Double get() = 1_000.0 / fps

    /** `640x360@24` — for a log line or a transition record, never parsed back. */
    override fun toString(): String = "${width}x$height@$fps"
}

/**
 * The rungs available for one call shape, best first.
 *
 * The two ladders exist because a conference is not a one-to-one call with more windows.
 * One handset in a four-party mesh runs three encoders and three decoders against one
 * camera and one SoC, so the top of the conference ladder sits where the *aggregate* is
 * affordable rather than where a single stream is — 540p24, not 720p30. Phase 3 measured
 * what happens when that is ignored: three simultaneous encodes at 720p30 do not degrade
 * gracefully, they collapse to single-digit frame rates.
 *
 * Indexed rather than compared. [stepDown] and [stepUp] are the only ways to move, so a
 * ladder that skips a rung cannot be stepped onto one it does not have.
 */
class VideoQualityLadder internal constructor(
    val shape: CallShape,
    private val rungs: List<VideoQualityProfile>,
) {
    init {
        require(rungs.isNotEmpty()) { "a ladder needs at least one rung" }
    }

    /** The best rung. Where a call starts, subject to the budget and the display ceiling. */
    val top: VideoQualityProfile get() = rungs.first()

    /** The floor. [stepDown] returns null here rather than inventing a lower rung. */
    val bottom: VideoQualityProfile get() = rungs.last()

    /** Every rung, best first. */
    val profiles: List<VideoQualityProfile> get() = rungs

    /** The profile for [tier], or null when this ladder does not offer that rung. */
    fun profile(tier: VideoQualityTier): VideoQualityProfile? = rungs.firstOrNull { it.tier == tier }

    /** The next rung down, or null at [bottom]. */
    fun stepDown(from: VideoQualityTier): VideoQualityProfile? = rungs.getOrNull(indexOf(from) + 1)

    /** The next rung up, or null at [top]. */
    fun stepUp(from: VideoQualityTier): VideoQualityProfile? {
        val index = indexOf(from)
        return if (index <= 0) null else rungs[index - 1]
    }

    /**
     * The best rung whose [VideoQualityProfile.targetBps] fits [allowanceBps] and whose
     * picture is not larger than [displayCeiling] needs.
     *
     * Never null: at worst it is [bottom]. A budget too small even for the floor is a
     * decision about whether to send video at all, which is not this type's to make —
     * `AdaptiveVideoPolicy` reports it as pressure and the floor keeps the call usable.
     */
    fun bestAffordable(allowanceBps: Int, displayCeiling: DisplayCeiling): VideoQualityProfile =
        rungs.firstOrNull { it.targetBps <= allowanceBps && displayCeiling.admits(it) } ?: bottom

    /**
     * As [bestAffordable], but never richer than [pixelRateCeiling].
     *
     * For crossing between ladders. The rung that is affordable on the ladder being joined
     * may be a *better* picture than the one being left — a four-party call dropping to
     * three, or a conference ending — and taking it would be an upgrade granted for the
     * shape change rather than earned by evidence. The brief is explicit that recovery is
     * conservative in every direction, so the climb still goes through the upgrade window;
     * this only picks the landing rung.
     */
    fun bestAffordableNotExceeding(
        pixelRateCeiling: Long,
        allowanceBps: Int,
        displayCeiling: DisplayCeiling,
    ): VideoQualityProfile =
        rungs.firstOrNull {
            it.targetBps <= allowanceBps &&
                displayCeiling.admits(it) &&
                it.pixelRate <= pixelRateCeiling
        } ?: rungs.lastOrNull { it.pixelRate <= pixelRateCeiling } ?: bottom

    private fun indexOf(tier: VideoQualityTier): Int =
        rungs.indexOfFirst { it.tier == tier }.takeIf { it >= 0 }
            ?: error("tier $tier is not a rung of the $shape ladder")
}

/** Whether this call is a one-to-one or a full-mesh conference. */
enum class CallShape { ONE_TO_ONE, CONFERENCE }

/**
 * The two ladders, and the only place a resolution, frame rate or bitrate is written.
 *
 * Bitrate bands are VP8 at these sizes with motion typical of a handheld camera. They are
 * bands and not points because the encoder is rate-controlled: `targetBps` is what it is
 * asked for, `maxBps` what it may peak to on a scene change, `minBps` the point below
 * which the picture stops being worth the resolution and the tier should give way instead
 * of the encoder quietly producing mush.
 */
object VideoQualityProfiles {

    /**
     * One-to-one: one encode, one decode, and the whole SoC to do it in.
     *
     * 720p30 at the top because Phase 3 proved a single stream sustains it — 30.0 fps
     * captured, 29.5 decoded at the far end — and anything less on a modern handset in a
     * two-party call reads as a broken camera rather than a bandwidth choice.
     */
    val oneToOne: VideoQualityLadder = VideoQualityLadder(
        shape = CallShape.ONE_TO_ONE,
        rungs = listOf(
            VideoQualityProfile(
                tier = VideoQualityTier.HIGH,
                width = 1280, height = 720, fps = 30,
                minBps = 800_000, targetBps = 1_600_000, maxBps = 2_500_000,
            ),
            VideoQualityProfile(
                tier = VideoQualityTier.MEDIUM_HIGH,
                width = 960, height = 540, fps = 30,
                minBps = 500_000, targetBps = 1_000_000, maxBps = 1_600_000,
            ),
            VideoQualityProfile(
                tier = VideoQualityTier.MEDIUM,
                width = 640, height = 360, fps = 24,
                minBps = 300_000, targetBps = 600_000, maxBps = 900_000,
            ),
            VideoQualityProfile(
                tier = VideoQualityTier.LOW,
                width = 640, height = 360, fps = 15,
                minBps = 200_000, targetBps = 400_000, maxBps = 600_000,
            ),
        ),
    )

    /**
     * Conference: up to three encodes and three decodes on one device.
     *
     * The top rung drops the frame rate before the resolution — 540p24 rather than
     * 540p30 — because three encoders contend for the same component and frame rate is
     * the cheaper thing to give up for a picture that still has to be readable in a tile
     * a quarter of the screen. [VideoQualityTier.MEDIUM_HIGH] is absent: see its doc.
     */
    val conference: VideoQualityLadder = VideoQualityLadder(
        shape = CallShape.CONFERENCE,
        rungs = listOf(
            VideoQualityProfile(
                tier = VideoQualityTier.HIGH,
                width = 960, height = 540, fps = 24,
                minBps = 450_000, targetBps = 850_000, maxBps = 1_300_000,
            ),
            VideoQualityProfile(
                tier = VideoQualityTier.MEDIUM,
                width = 640, height = 360, fps = 24,
                minBps = 300_000, targetBps = 600_000, maxBps = 900_000,
            ),
            VideoQualityProfile(
                tier = VideoQualityTier.LOW,
                width = 640, height = 360, fps = 15,
                minBps = 200_000, targetBps = 400_000, maxBps = 600_000,
            ),
        ),
    )

    /** The ladder for [shape]. */
    fun forShape(shape: CallShape): VideoQualityLadder = when (shape) {
        CallShape.ONE_TO_ONE -> oneToOne
        CallShape.CONFERENCE -> conference
    }
}
