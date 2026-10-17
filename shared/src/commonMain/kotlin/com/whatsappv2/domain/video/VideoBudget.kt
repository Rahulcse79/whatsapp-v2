package com.whatsappv2.domain.video

/**
 * What this device is allowed to spend on *outgoing* video, in total.
 *
 * A full mesh multiplies. Every peer gets its own encode and its own stream, so a tier
 * chosen per-leg without an aggregate view is a tier chosen three times over: three legs
 * at the conference top rung's own ceiling would ask for 3.9 Mbit/s from one radio and
 * three simultaneous encodes from one component. Phase 3 measured what that does — two
 * hardware VP8 components running at once fell to 4.4 fps encode and 0.5 fps decode with
 * four and a half cores idle, because the contention is inside Codec2 and not on the CPU.
 *
 * So the budget is stated once for the device and divided, rather than assumed per stream.
 *
 * This bounds bitrate and, through [VideoQualityLadder.bestAffordable], the tier. It does
 * not bound the *number* of legs: that is `VideoMix.MAX_PARTICIPANTS`, a product ceiling
 * enforced where a leg is admitted, and a budget is not the place to silently drop a
 * participant who has already joined.
 */
data class VideoBudget(
    /** Legs currently sending video. Self-preview is not one — it never leaves the device. */
    val outgoingVideoLegs: Int,
    /** Total outgoing video bitrate this device may ask for across every leg. */
    val aggregateCeilingBps: Int = AGGREGATE_CEILING_BPS,
) {
    init {
        require(outgoingVideoLegs >= 0) { "legs cannot be negative, was $outgoingVideoLegs" }
        require(aggregateCeilingBps > 0) { "aggregate ceiling must be positive" }
    }

    /**
     * What one leg may ask for, after sharing the aggregate and applying the hard ceiling.
     *
     * Divided evenly. Weighting by who is on screen larger was considered and left out:
     * every peer in this mesh renders every other peer, so the tile a stream lands in
     * differs per receiver and there is no single answer to weight by. An even split is
     * the only division that is right from every receiver's point of view.
     *
     * Zero legs returns the hard ceiling rather than dividing by zero — nothing is being
     * sent, and the next leg to open should see a real allowance rather than infinity.
     */
    val allowanceBpsPerLeg: Int
        get() = when (outgoingVideoLegs) {
            0 -> HARD_CEILING_BPS
            else -> minOf(aggregateCeilingBps / outgoingVideoLegs, HARD_CEILING_BPS)
        }

    /** True when every leg at [profile]'s target would fit inside [aggregateCeilingBps]. */
    fun affords(profile: VideoQualityProfile): Boolean = profile.targetBps <= allowanceBpsPerLeg

    /**
     * The ceiling to hand the encoder for one leg at [profile].
     *
     * The smaller of what the profile would peak to and what this leg's share allows, and
     * never below the profile's own [VideoQualityProfile.minBps] — a ceiling under the
     * minimum is a resolution that cannot be encoded honestly, and the answer to that is
     * a lower tier, which the policy decides. Clamping here instead would hide it.
     */
    fun maxBpsFor(profile: VideoQualityProfile): Int =
        minOf(profile.maxBps, allowanceBpsPerLeg).coerceAtLeast(profile.minBps)

    /** The aggregate this device would actually ask for at [profile]. For the log line. */
    fun aggregateTargetBps(profile: VideoQualityProfile): Int = profile.targetBps * outgoingVideoLegs

    companion object {
        /**
         * Total outgoing video across every leg, 3 Mbit/s.
         *
         * Sized from the mesh rather than from the link, and left where it was when the
         * ladders were lowered on 2026-09-27: three legs at the four-party top rung's target
         * is now 1.5 Mbit/s, half the ceiling, which is deliberate headroom rather than slack
         * to be reclaimed. It divides to 1 Mbit/s a leg, so the aggregate stops being the
         * binding constraint and the tier is chosen by what the encoder can actually deliver
         * — which is the thing that was limiting these handsets. A one-to-one call has one
         * leg and is bounded by [HARD_CEILING_BPS] instead.
         */
        const val AGGREGATE_CEILING_BPS = 3_000_000

        /**
         * No single stream exceeds this, whatever the arithmetic says. 2.5 Mbit/s, the
         * same number `AccountConfigFactory.VIDEO_MAX_BPS` has always enforced — kept so
         * that adaptive quality can only ever lower the ceiling this build already had,
         * never raise it.
         */
        const val HARD_CEILING_BPS = 2_500_000
    }
}

/**
 * The largest picture worth sending, given how big the receiver will draw it.
 *
 * Sending 720p into a tile 480 pixels tall spends encode time, radio and the far end's
 * decode budget on detail that is scaled away before anybody sees it. In a four-party
 * mesh that waste is paid three times.
 *
 * ## The assumption, stated rather than hidden
 *
 * This device cannot know how the far end has laid out its screen. What it knows is its
 * own layout, and in this mesh that is a sound proxy *because the mesh is symmetric*:
 * every participant renders every other participant, the grid is chosen from the same
 * participant count by the same code, so a three-person call draws the same tiles on all
 * three handsets. It is a proxy and not a measurement, which is why it only ever caps an
 * upgrade — [admits] is consulted when climbing and never when falling — and why
 * [UNCONSTRAINED] is the default for anything that has not been told a tile size.
 *
 * A remote that laid itself out differently therefore gets a picture sized for our grid
 * rather than theirs. The cost of being wrong is bounded by [OVERSHOOT]: a tolerated 1.5x
 * over the tile, so a full-screen remote on a device that we believe is tiling still
 * receives something it can scale up without obvious softness.
 */
data class DisplayCeiling(val height: Int) {

    /**
     * True when [profile] is not extravagantly larger than the tile.
     *
     * Height only. Both ladders are 16:9 throughout, so width carries no information
     * height does not, and comparing one axis cannot disagree with the other.
     */
    fun admits(profile: VideoQualityProfile): Boolean =
        this == UNCONSTRAINED || profile.height <= height * OVERSHOOT

    companion object {
        /** Nothing known about the tile — every rung is admitted. */
        val UNCONSTRAINED: DisplayCeiling = DisplayCeiling(Int.MAX_VALUE)

        /**
         * How much larger than the tile a picture may be before it is waste.
         *
         * 1.5x, not 1.0x. A tile is measured in layout pixels at one instant, the grid
         * re-lays out when somebody joins or the phone turns, and a stream re-sized on
         * every rotation is a stream rebuilt on every rotation. Slack absorbs that.
         */
        const val OVERSHOOT = 1.5

        /**
         * The ceiling for a grid of [rows] rows over a viewport [viewportHeight] tall.
         *
         * Self-preview is excluded by the caller: it is an overlay on top of the grid, not
         * a cell in it, and counting it would shrink everybody else's tile by a row that
         * does not exist. See `ConferenceVideoLayout`, which decides the grid, and the
         * self-view overlay in `SelfPreview`.
         */
        fun forGrid(viewportHeight: Int, rows: Int): DisplayCeiling =
            if (viewportHeight <= 0 || rows <= 0) UNCONSTRAINED
            else DisplayCeiling(viewportHeight / rows)
    }
}
