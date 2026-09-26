package com.whatsappv2.data.sip.registration.stack

import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.domain.video.AdaptiveVideoPolicy
import com.whatsappv2.domain.video.AdaptiveVideoThresholds
import com.whatsappv2.domain.video.CallShape
import com.whatsappv2.domain.video.DecoderConditions
import com.whatsappv2.domain.video.DevicePressure
import com.whatsappv2.domain.video.DevicePressureSource
import com.whatsappv2.domain.video.DisplayCeiling
import com.whatsappv2.domain.video.EncoderConditions
import com.whatsappv2.domain.video.VideoBudget
import com.whatsappv2.domain.video.VideoConditions
import com.whatsappv2.domain.video.VideoQualityProfile
import com.whatsappv2.domain.video.VideoQualityTransition

/** One outgoing video leg's telemetry for this tick, as the gateway reads it. */
internal data class VideoLegTelemetrySample(
    val callKey: String,
    val rtp: VideoRtpSample,
    val frames: VideoLegTelemetry.FrameReading?,
    /**
     * The frame rate the *running* encoder was configured for, or null when unreadable.
     *
     * The denominator for the encoder's health, and deliberately not the policy's own tier:
     * a tier change is a standing decision the next stream build picks up, so between the two
     * the stream is still running the previous rate. Scoring a healthy encoder against a rate
     * it was never given reads optimistically straight after a downgrade, which would stall
     * adaptation exactly when more of it was needed. Falls back to the policy's tier only
     * because a rate of zero would be worse than a slightly wrong one.
     */
    val configuredFps: Int? = null,
)

/**
 * What the gateway should do about a tier change. Null is the usual answer.
 *
 * The coordinator decides and the gateway acts, so that everything touching PJSIP stays on
 * the executor and everything deciding stays testable. The split is the same one
 * `AdaptiveVideoPolicy` draws against the media layer, one level up.
 */
internal data class VideoQualityAction(
    val settings: VideoEncoderSettings,
    val transition: VideoQualityTransition,
    /**
     * The calls this tier now applies to, for the log line — **not** calls to re-INVITE.
     *
     * ## Why no re-INVITE, measured
     *
     * `pjmedia_vid_stream` has no reconfigure API: there is `send_keyframe` and nothing that
     * changes a running encoder's format. So a resolution or frame-rate change reaches a
     * *running* stream only by rebuilding it, and the only way to rebuild one is a re-INVITE.
     * That was implemented, and then removed on the evidence.
     *
     * An autonomous re-INVITE issued from a timer cannot be serialised against the
     * re-INVITEs the *user* causes — video off and on, hold, unhold — and PJSIP carries one
     * INVITE transaction at a time. Measured on 1001 (SM-M146B) 2026-09-27: a quality
     * re-INVITE, then video off/on, then hold/unhold, and from the unhold onwards the call
     * sat at `pt 102/103`, `1088x1088@45`, `capture 0.0 decode 0.0` — camera stopped, remote
     * tile frozen, for the remaining eight minutes and across two further calls. The
     * lifecycle matrix failed six of fourteen rows. Gating on "no operation pending" was
     * considered and rejected: the race is not only with operations already in flight but
     * with ones the user is about to cause, which a timer cannot know about.
     *
     * So the tier is a **standing decision**, written to the endpoint codec parameter the
     * moment it is taken and picked up by the next stream build —
     * `pjmedia_vid_stream_info_from_sdp` re-reads it through
     * `pjmedia_vid_codec_mgr_get_default_param`. The app's own lifecycle supplies those
     * builds frequently: call start, video off→on, hold→unhold, camera switch, and every
     * participant joining or leaving a mesh. What is given up is stepping the resolution of
     * a stream that nobody touches; what is kept is every call and every leg starting at the
     * quality this device has been measured able to sustain.
     */
    val appliesTo: Set<String>,
)

/**
 * Drives [AdaptiveVideoPolicy] from PJSIP's telemetry and bounds what it is allowed to do.
 *
 * ## One policy for the device, not one per leg
 *
 * The things being rationed are shared: one encoder component, one radio, one SoC, and —
 * decisively — one endpoint-wide video codec parameter, which is what `setVideoCodecParam`
 * writes and what a rebuilt stream reads back (`pjmedia_vid_stream_info_from_sdp` calls
 * `pjmedia_vid_codec_mgr_get_default_param`). Per-leg tiers are therefore not expressible
 * without native work, and they are also not wanted: the budget divides one aggregate
 * between legs, so one tier per device is the coherent design rather than a limitation
 * being accepted. Several legs reduce to the worst of them — see
 * [VideoConditionsReducer.worstOf].
 *
 * ## What bounds the change rate
 *
 * Two things, stacked. The policy's own cooldown paces tier changes at one per ten seconds,
 * and [VideoQualityChangeBudget] caps the number in any rolling two minutes whatever the
 * policy asks for. The second exists because the first is a *per-decision* rule and a storm
 * is a property of a sequence: a pathological link sitting on a threshold would obey the
 * cooldown perfectly and still change tier every ten seconds for an hour.
 *
 * When the budget is exhausted the tier simply holds. That is a safe terminal state — the
 * call keeps running at whatever quality it last reached — and it is logged once so the
 * condition is visible rather than silent.
 */
internal class VideoQualityCoordinator(
    private val devicePressure: DevicePressureSource,
    private val logger: Logger,
    private val thresholds: AdaptiveVideoThresholds = AdaptiveVideoThresholds(),
    private val changes: VideoQualityChangeBudget = VideoQualityChangeBudget(),
) {
    private var policy: AdaptiveVideoPolicy? = null
    private val previousRtp = mutableMapOf<String, VideoRtpSample>()
    private val previousFrames = mutableMapOf<String, VideoLegTelemetry.FrameReading>()

    /**
     * The newest pipeline figures per leg, carried across ticks where no new native counter
     * line arrived. See the note in [VideoConditionsReducer.reduce]: the alternative is to
     * fold a healthy default into the mean of a failing encoder.
     */
    private val lastEncoder = mutableMapOf<String, EncoderConditions>()
    private val lastDecoder = mutableMapOf<String, DecoderConditions>()
    private var shape: CallShape = CallShape.ONE_TO_ONE
    private var displayCeiling: DisplayCeiling = DisplayCeiling.UNCONSTRAINED
    private var warnedBudgetExhausted = false
    private var lastHeldReportAtMillis = 0L

    /** The tier in force, or null before the first video leg. For the trace line. */
    val current: VideoQualityProfile? get() = policy?.current

    /**
     * The tier a stream should be created at right now.
     *
     * Read when a call is about to build a video stream, so a leg that opens mid-call starts
     * where the device already knows it can run rather than at the top of the ladder and
     * then falling. Falls back to the ladder's own starting rung before any measurement.
     */
    fun startingSettings(budget: VideoBudget): VideoEncoderSettings {
        val profile = policy?.current
            ?: AdaptiveVideoPolicy(
                shape = shape,
                thresholds = thresholds,
                initialBudget = budget,
                initialDisplayCeiling = displayCeiling,
            ).current
        return VideoEncoderSettings.of(profile, budget)
    }

    /** Tells the coordinator how big the tiles are, from the call screen's own layout. */
    fun onDisplayCeiling(ceiling: DisplayCeiling) {
        displayCeiling = ceiling
    }

    /**
     * Forgets the measurements for [callKey], keeping the tier.
     *
     * For video off, hold, and background — the stream's counters restart at zero and a
     * delta across that boundary is not a rate. Phase 3's harness taught exactly this about
     * measurements carried across a restart: they look valid and are not.
     */
    fun onMediaRestarted(callKey: String) {
        previousRtp -= callKey
        previousFrames -= callKey
        lastEncoder -= callKey
        lastDecoder -= callKey
        policy?.mediaRestarted()
    }

    /** Drops everything for a call that has ended. */
    fun onCallEnded(callKey: String) {
        previousRtp -= callKey
        previousFrames -= callKey
        lastEncoder -= callKey
        lastDecoder -= callKey
    }

    /**
     * No leg is sending video right now — a hold, or video switched off.
     *
     * The measurements go and **the tier stays**. Keeping the tier is the whole point of a
     * standing decision: the next stream build is what applies it, and hold/unhold and video
     * off/on *are* those builds. An earlier version dropped the policy here, which meant the
     * learned tier was discarded at precisely the moments it would have taken effect — the
     * device re-learned from 720p30 on every unhold and the decision never reached the media.
     */
    fun onVideoPaused() {
        previousRtp.clear()
        previousFrames.clear()
        lastEncoder.clear()
        lastDecoder.clear()
        policy?.mediaRestarted()
    }

    /**
     * Every call is gone. Now the tier goes too.
     *
     * A fresh call measures the device again rather than inheriting a verdict reached about a
     * call that has ended — conditions, and which peer is on the other end, are not the same
     * question. This is the only place the policy is discarded.
     */
    fun onNoCalls() {
        policy = null
        previousRtp.clear()
        previousFrames.clear()
        lastEncoder.clear()
        lastDecoder.clear()
        warnedBudgetExhausted = false
        lastHeldReportAtMillis = 0L
    }

    /**
     * One tick. Returns what to apply, or null to leave the media alone.
     *
     * [legs] carries every leg currently sending video. An empty list is not an error — a
     * call with video negotiated but not transmitting — and produces nothing.
     */
    fun onSample(legs: List<VideoLegTelemetrySample>, nowMillis: Long): VideoQualityAction? {
        if (legs.isEmpty()) return null

        val budget = VideoBudget(outgoingVideoLegs = legs.size)
        val nextShape = if (legs.size > 1) CallShape.CONFERENCE else CallShape.ONE_TO_ONE
        shape = nextShape

        val active = policy ?: AdaptiveVideoPolicy(
            shape = nextShape,
            thresholds = thresholds,
            initialBudget = budget,
            initialDisplayCeiling = displayCeiling,
        ).also { policy = it }

        val perLeg = legs.mapNotNull { leg ->
            reduce(leg, leg.configuredFps ?: active.current.fps, nextShape, budget)
        }
        // Every reading is taken and stored even when it cannot yet be reduced, so the next
        // tick has something to difference against.
        legs.forEach { leg ->
            previousRtp[leg.callKey] = leg.rtp
            leg.frames?.let { frames ->
                val known = previousFrames[leg.callKey]
                if (known == null || frames.atMillis > known.atMillis) {
                    previousFrames[leg.callKey] = frames
                }
            }
        }
        perLeg.forEachIndexed { index, conditions ->
            val key = legs.getOrNull(index)?.callKey ?: return@forEachIndexed
            lastEncoder[key] = conditions.encoder
            lastDecoder[key] = conditions.decoder
        }
        val conditions = VideoConditionsReducer.worstOf(perLeg) ?: return null

        // A cap on how often the tier may move at all, on top of the policy's own cooldown.
        // The cooldown is a per-decision rule and a storm is a property of a sequence: a link
        // sitting exactly on a threshold would obey the cooldown perfectly and still rewrite
        // the codec parameter every ten seconds for an hour. Asked *before* the policy is
        // sampled, so the policy never moves to a tier the gateway will not write — a tier
        // the two disagreed about would make every later decision wrong at its baseline.
        if (!changes.canSpend(nowMillis)) {
            if (!warnedBudgetExhausted) {
                warnedBudgetExhausted = true
                logger.warn(
                    TAG,
                    "Video quality held at ${active.current}: change budget spent " +
                        "(${VideoQualityChangeBudget.MAX_IN_WINDOW} in " +
                        "${VideoQualityChangeBudget.WINDOW_MILLIS / 1000}s). The call continues " +
                        "at this tier; adaptation resumes when the window rolls.",
                )
            }
            return null
        }

        val transition = active.sample(conditions, nowMillis)
        if (transition == null) {
            reportHeld(active, conditions, nowMillis)
            return null
        }
        changes.spend(nowMillis)
        warnedBudgetExhausted = false

        logger.info(TAG, "VideoQuality $transition")

        return VideoQualityAction(
            settings = VideoEncoderSettings.of(transition.to, conditions.budget),
            transition = transition,
            appliesTo = legs.map { it.callKey }.toSet(),
        )
    }

    /**
     * One line, at most every [HELD_REPORT_INTERVAL_MILLIS], saying why quality is not rising.
     *
     * A downgrade explains itself; a climb that never happens explains nothing, and "the
     * video never came back" was therefore the one complaint the telemetry could not answer.
     * This closes that, at one line every half minute and only while there is something to
     * say — a call already at the top rung, or one whose healthy window is simply still
     * running, says nothing at all.
     */
    private fun reportHeld(
        policy: AdaptiveVideoPolicy,
        conditions: VideoConditions,
        nowMillis: Long,
    ) {
        val blocking = policy.blockingUpgrade(conditions)
        if (blocking.isEmpty() || blocking == listOf("at-top")) return
        if (nowMillis - lastHeldReportAtMillis < HELD_REPORT_INTERVAL_MILLIS) return
        lastHeldReportAtMillis = nowMillis

        val healthy = policy.healthyForMillis(nowMillis)
        logger.info(
            TAG,
            "VideoQuality held at ${policy.current} - blocked by ${blocking.joinToString(" ")}" +
                " | healthy for ${healthy ?: 0}ms of ${policy.upgradeWindowMillis()}ms needed" +
                " | pressure ${policy.pressure.joinToString(",").ifEmpty { "none" }}",
        )
    }

    private fun reduce(
        leg: VideoLegTelemetrySample,
        configuredFps: Int,
        shape: CallShape,
        budget: VideoBudget,
    ): VideoConditions? = VideoConditionsReducer.reduce(
        previousRtp = previousRtp[leg.callKey],
        currentRtp = leg.rtp,
        previousFrames = previousFrames[leg.callKey],
        currentFrames = leg.frames,
        configuredFps = configuredFps,
        shape = shape,
        budget = budget,
        device = runCatching { devicePressure.sample() }.getOrElse {
            // A thermometer that throws must not take the call's video with it.
            DevicePressure()
        },
        displayCeiling = displayCeiling,
        fallbackEncoder = lastEncoder[leg.callKey],
        fallbackDecoder = lastDecoder[leg.callKey],
    )

    private companion object {
        const val TAG = "VideoQualityCoordinator"

        /** How often a held-quality line may be written. Six sample ticks. */
        const val HELD_REPORT_INTERVAL_MILLIS = 30_000L
    }
}

/**
 * A token bucket over a rolling window, so a pathological link cannot rewrite the codec
 * parameter endlessly however perfectly it obeys the per-change cooldown.
 *
 * Six in two minutes. Six is the whole one-to-one ladder walked down and back up, so a call
 * whose conditions genuinely change twice still adapts freely; a call that wants a seventh
 * inside two minutes is oscillating, and holding the tier is the better answer.
 */
internal class VideoQualityChangeBudget(
    private val maxInWindow: Int = MAX_IN_WINDOW,
    private val windowMillis: Long = WINDOW_MILLIS,
) {
    private val spent = ArrayDeque<Long>()

    /** True when one more tier change is allowed as of [nowMillis]. */
    fun canSpend(nowMillis: Long): Boolean {
        evict(nowMillis)
        return spent.size < maxInWindow
    }

    /** Records one. Call only after [canSpend] returned true and the change was taken. */
    fun spend(nowMillis: Long) {
        evict(nowMillis)
        spent.addLast(nowMillis)
    }

    private fun evict(nowMillis: Long) {
        while (spent.isNotEmpty() && nowMillis - spent.first() >= windowMillis) {
            spent.removeFirst()
        }
    }

    companion object {
        const val MAX_IN_WINDOW = 6
        const val WINDOW_MILLIS = 120_000L
    }
}
