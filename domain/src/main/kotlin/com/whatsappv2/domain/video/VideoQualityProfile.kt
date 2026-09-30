package com.whatsappv2.domain.video

/**
 * One rung of the video quality ladder.
 *
 * A rung identity, not a resolution. What [HIGH] means in pixels is the ladder's business
 * and differs with the participant count — see [VideoQualityLadder] — so code that wants
 * numbers asks the ladder for a [VideoQualityProfile] rather than reading anything off the
 * tier.
 *
 * Ordered highest first, which is the order the ladder is indexed in and the order the
 * state machine steps through. `ordinal` is deliberately not used for comparison anywhere:
 * every ladder is free to choose what its rungs mean in pixels, and an ordinal comparison
 * would claim a relationship between two ladders' rungs that does not exist.
 */
enum class VideoQualityTier {
    /** The best this build will ask for: 540p30 one-to-one, 360p24 at three, 360p20 at four. */
    HIGH,

    /**
     * The middle rung. What the brief calls conference `NORMAL`, under the name this enum
     * already had for it.
     */
    MEDIUM,

    /** The floor: below this video stops being worth sending at all. */
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
 * A ladder per [CallShape], because a conference is not a one-to-one call with more windows.
 * One handset in a four-party mesh runs three encoders and three decoders against one camera
 * and one SoC, so the top of its ladder sits where the *aggregate* is affordable rather than
 * where a single stream is. Phase 3 measured what happens when that is ignored: three
 * simultaneous encodes at 720p30 do not degrade gracefully, they collapse to single-digit
 * frame rates.
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

/**
 * How many pictures this device is encoding and decoding at once.
 *
 * Named by participant count rather than by "call" and "conference", because the cost is not
 * a step between those two: a four-party mesh asks one SoC for three encodes and three
 * decodes against a single camera, which is half as much work again as a three-party one.
 * Ladders that differ only between one and many gave four parties the three-party budget and
 * the handsets spent every four-party call at the floor.
 *
 * The mesh caps at four participants (`SipConferenceController.MAX_VIDEO_CONFERENCE`), so
 * [FOUR_PARTY] is also the top: five legs would be an architecture change, not a rung.
 */
enum class CallShape {
    /** Two participants: one outgoing leg. */
    ONE_TO_ONE,

    /** Three participants: two outgoing legs, two incoming. */
    THREE_PARTY,

    /** Four participants: three outgoing legs, three incoming. The mesh ceiling. */
    FOUR_PARTY,

    ;

    companion object {
        /**
         * The shape for a device sending video on [outgoingLegs] legs.
         *
         * Legs, not roster size: what decides the encoder's workload is how many streams this
         * device is producing, and a participant whose video is off costs nothing. Anything
         * above three legs is treated as [FOUR_PARTY] rather than rejected — the mesh should
         * not be able to make this throw, and the busiest ladder is the right answer for a
         * device doing more work than the ceiling allows for.
         */
        fun forOutgoingLegs(outgoingLegs: Int): CallShape = when {
            outgoingLegs <= 1 -> ONE_TO_ONE
            outgoingLegs == 2 -> THREE_PARTY
            else -> FOUR_PARTY
        }
    }
}

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
     * ## One frame rate, and resolution is the only thing that moves
     *
     * Every rung of every ladder is 15 fps. That is not a coincidence of tuning, it is the
     * point, and there are two measured reasons for it.
     *
     * **The camera produces 30 fps and nothing changes that.** `capture 29.8` was true on all
     * four handsets while the policy was asking for 15, 20 and 24 on different legs, so the
     * encoder is fed 30 and drops what it does not want. 15 is the only rate that divides 30
     * evenly: every second frame, at a constant 66.7 ms. 20 fps is a 3:2 pattern — 33, 67,
     * 33, 67 — and 12 fps is 5:2. Those arrive as judder even when not a single frame is
     * lost, which is exactly the complaint this is answering, and no amount of bandwidth
     * fixes it.
     *
     * **An uneven rate *between* legs is the same fault one level up.** Measured on a
     * four-party mesh, 2026-09-28: one device's three outgoing legs were encoding at 13.0,
     * 26.1 and 26.4 fps simultaneously, because each stream was built at a different moment
     * against a different standing decision. Pairing each sender against its receiver, half
     * the frames that were encoded never arrived — 23.2 encoded against 11.2 decoded, 26.4
     * against 11.7, 15.1 against 0.0 — with receive loss running to 148 packets in a window.
     *
     * So the frame rate is fixed and **resolution is the only dial**. A busier call sends a
     * smaller picture at the same steady rate, and the bitrates below are roughly a third of
     * what they were, because a frame lost in transit is worth less than a smaller frame that
     * arrives. A 320x180 picture arriving fifteen times a second reads as a person talking; a
     * 640x360 picture arriving seven times reads as a fault.
     *
     * The ladders **overlap**: each shape's floor is at or below the next busier shape's top,
     * so a participant joining or leaving always has a rung to land on that is no richer than
     * the one being left — see [VideoQualityLadder.bestAffordableNotExceeding], which could
     * otherwise only fall back to a floor that was itself an upgrade.
     *
     * Bitrate bands are VP8 at these sizes with handheld motion: `targetBps` is what the
     * encoder is asked for, `maxBps` what it may peak to on a scene change, `minBps` the
     * point below which the picture stops being worth the resolution and the tier should give
     * way instead of the encoder quietly producing mush.
     */

    /** Two participants: one encode, one decode, and the whole SoC to do it in. */
    val oneToOne: VideoQualityLadder = VideoQualityLadder(
        shape = CallShape.ONE_TO_ONE,
        rungs = listOf(
            VideoQualityProfile(
                tier = VideoQualityTier.HIGH,
                width = 640, height = 360, fps = 15,
                minBps = 160_000, targetBps = 350_000, maxBps = 550_000,
            ),
            VideoQualityProfile(
                tier = VideoQualityTier.MEDIUM,
                width = 480, height = 270, fps = 15,
                minBps = 100_000, targetBps = 220_000, maxBps = 350_000,
            ),
            VideoQualityProfile(
                tier = VideoQualityTier.LOW,
                width = 320, height = 180, fps = 15,
                minBps = 60_000, targetBps = 130_000, maxBps = 200_000,
            ),
        ),
    )

    /**
     * Three participants: two encodes and two decodes on one device.
     *
     * Its top is the one-to-one middle rung, so joining a third participant costs a step of
     * resolution and nothing else — the picture gets smaller, the motion does not change.
     */
    val threeParty: VideoQualityLadder = VideoQualityLadder(
        shape = CallShape.THREE_PARTY,
        rungs = listOf(
            VideoQualityProfile(
                tier = VideoQualityTier.HIGH,
                width = 480, height = 270, fps = 15,
                minBps = 100_000, targetBps = 220_000, maxBps = 350_000,
            ),
            VideoQualityProfile(
                tier = VideoQualityTier.MEDIUM,
                width = 320, height = 180, fps = 15,
                minBps = 60_000, targetBps = 130_000, maxBps = 200_000,
            ),
            // The same floor the four-party ladder ends on, so a participant leaving a
            // four-party call at the bottom has a rung to land on that is not an upgrade.
            VideoQualityProfile(
                tier = VideoQualityTier.LOW,
                width = 192, height = 108, fps = 15,
                minBps = 30_000, targetBps = 55_000, maxBps = 90_000,
            ),
        ),
    )

    /**
     * Four participants: three encodes and three decodes, which is the mesh's ceiling.
     *
     * Three legs at the top rung ask 390 kbit/s in total, an eighth of the aggregate budget.
     * That is deliberate rather than timid: the measurement that prompted these numbers was
     * taken at roughly three times this bitrate and half the frames did not arrive. A tile in
     * a four-party grid is a quarter of the screen, so the pixels given up are ones nobody
     * could see; the frames bought are ones everybody sees.
     */
    val fourParty: VideoQualityLadder = VideoQualityLadder(
        shape = CallShape.FOUR_PARTY,
        rungs = listOf(
            VideoQualityProfile(
                tier = VideoQualityTier.HIGH,
                width = 320, height = 180, fps = 15,
                minBps = 60_000, targetBps = 130_000, maxBps = 200_000,
            ),
            VideoQualityProfile(
                tier = VideoQualityTier.MEDIUM,
                width = 256, height = 144, fps = 15,
                minBps = 45_000, targetBps = 90_000, maxBps = 140_000,
            ),
            VideoQualityProfile(
                tier = VideoQualityTier.LOW,
                width = 192, height = 108, fps = 15,
                minBps = 30_000, targetBps = 55_000, maxBps = 90_000,
            ),
        ),
    )

    /** The ladder for [shape]. */
    fun forShape(shape: CallShape): VideoQualityLadder = when (shape) {
        CallShape.ONE_TO_ONE -> oneToOne
        CallShape.THREE_PARTY -> threeParty
        CallShape.FOUR_PARTY -> fourParty
    }
}
