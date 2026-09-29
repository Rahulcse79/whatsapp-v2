package com.whatsappv2.data.sip.registration.stack

import com.whatsappv2.domain.video.CallShape
import com.whatsappv2.domain.video.DecoderConditions
import com.whatsappv2.domain.video.DevicePressure
import com.whatsappv2.domain.video.DisplayCeiling
import com.whatsappv2.domain.video.EncoderConditions
import com.whatsappv2.domain.video.NetworkConditions
import com.whatsappv2.domain.video.VideoBudget
import com.whatsappv2.domain.video.VideoConditions
import com.whatsappv2.domain.video.VideoQualityProfile

/**
 * One reading of a video stream's RTP state, so two of them make a rate.
 *
 * A copy of the handful of fields the policy needs rather than the whole `StreamStat`,
 * because `StreamStat` is a native object whose lifetime belongs to pjsua2 and reading it
 * twice is not guaranteed to give the same answer. Everything here is cumulative except
 * the two that are already means.
 */
internal data class VideoRtpSample(
    val atMillis: Long,
    val txPkt: Long,
    val txBytes: Long,
    /** Loss the *far end* reported in its receiver reports: our outbound loss. */
    val txLoss: Long,
    val rxPkt: Long,
    val rxLoss: Long,
    /**
     * Microseconds, the most recent sample rather than the stream's running mean.
     *
     * `MathStat.mean` never decays, so a spike blocks recovery for the rest of the call —
     * see the note at the read site in `RealPjsipCoreGateway.videoLegSamples`.
     */
    val rxJitterUsec: Long,
    val rttUsec: Long,
    /** PLI and NACK arrivals. A far end that keeps asking has lost its reference chain. */
    val feedbackRx: Long,
)

/**
 * Turns two telemetry readings into the smoothed policy's raw input.
 *
 * ## Why this is a pure function in its own file
 *
 * Every number here is a ratio with a chosen denominator, and choosing the wrong
 * denominator is the single most expensive mistake available in this subsystem — Phase 3
 * spent a session on `vidcnt enc` being read as a frame rate when it counted
 * `encode_begin` calls including the ones that produced nothing. So each denominator is
 * named, justified, and unit-tested against real counter values, with no PJSIP object
 * anywhere near it.
 *
 * ## What a null return means
 *
 * Not enough to say anything honest: one reading, no elapsed time, or counters that went
 * backwards because the stream was rebuilt and started again at zero. The caller feeds the
 * policy nothing rather than a zero, because a zero here reads as "the encoder produced no
 * frames" and would drive a downgrade on a stream that had merely just restarted.
 */
internal object VideoConditionsReducer {

    /**
     * @param configuredFps the tier in force, which is the denominator for the encoder's
     *   frame rate. Taken from the policy rather than from the stream, so that the ratio
     *   answers "is the encoder keeping up with what we asked for" and not "with what it
     *   happens to be doing".
     */
    @Suppress("LongParameterList")
    fun reduce(
        previousRtp: VideoRtpSample?,
        currentRtp: VideoRtpSample,
        previousFrames: VideoLegTelemetry.FrameReading?,
        currentFrames: VideoLegTelemetry.FrameReading?,
        configuredFps: Int,
        shape: CallShape,
        budget: VideoBudget,
        device: DevicePressure,
        displayCeiling: DisplayCeiling = DisplayCeiling.UNCONSTRAINED,
        fallbackEncoder: EncoderConditions? = null,
        fallbackDecoder: DecoderConditions? = null,
    ): VideoConditions? {
        if (previousRtp == null) return null
        val seconds = (currentRtp.atMillis - previousRtp.atMillis) / 1_000.0
        if (seconds <= 0.0) return null
        if (currentRtp.txPkt < previousRtp.txPkt) return null

        // RTP is read in-process on every tick; the frame counters arrive from the native
        // log on their own interval. When the two do not line up -- and they never line up
        // exactly -- the newest *usable* pipeline figures are carried forward rather than
        // replaced by the healthy defaults. That distinction matters: a default reads as
        // "the encoder is keeping up perfectly", so defaulting on a tick where no new
        // counter arrived would fold optimism into the mean of a collapsing encoder and
        // could hold a downgrade off indefinitely.
        val staleFrames = previousFrames == null || currentFrames == null ||
            currentFrames.atMillis <= previousFrames.atMillis

        return VideoConditions(
            shape = shape,
            budget = budget,
            network = network(previousRtp, currentRtp, seconds),
            encoder = when {
                !staleFrames -> encoder(previousFrames, currentFrames, configuredFps)
                else -> fallbackEncoder ?: EncoderConditions()
            },
            decoder = when {
                !staleFrames -> decoder(previousFrames, currentFrames)
                else -> fallbackDecoder ?: DecoderConditions()
            },
            device = device,
            displayCeiling = displayCeiling,
        )
    }

    private fun network(
        previous: VideoRtpSample,
        current: VideoRtpSample,
        seconds: Double,
    ): NetworkConditions {
        val sent = current.txPkt - previous.txPkt
        val lost = (current.txLoss - previous.txLoss).coerceAtLeast(0)
        return NetworkConditions(
            // Against packets *sent* over the same interval, which is what the far end was
            // reporting on. Against packets received it would read lower on a bad link and
            // lower still as the link got worse, which is the wrong direction entirely.
            lossFraction = if (sent > 0) (lost.toDouble() / sent).coerceIn(0.0, 1.0) else 0.0,
            // Levels, not deltas: both arrive as the newest RTCP sample, and the policy's
            // smoother is what turns them into something stable enough to act on.
            rttMillis = current.rttUsec / 1_000.0,
            jitterMillis = current.rxJitterUsec / 1_000.0,
            txBitrateBps = (((current.txBytes - previous.txBytes) * 8) / seconds).toInt(),
            feedbackPerSecond =
                ((current.feedbackRx - previous.feedbackRx).coerceAtLeast(0) / seconds),
        )
    }

    private fun encoder(
        previous: VideoLegTelemetry.FrameReading?,
        current: VideoLegTelemetry.FrameReading?,
        configuredFps: Int,
    ): EncoderConditions {
        if (previous == null || current == null || configuredFps <= 0) return EncoderConditions()
        val seconds = (current.atMillis - previous.atMillis) / 1_000.0
        if (seconds <= 0.0) return EncoderConditions()
        // A rebuilt stream restarts its counters. Reporting the negative delta as a
        // collapse would downgrade a stream that had just come back healthy.
        if (current.encodedFrames < previous.encodedFrames) return EncoderConditions()

        val produced = current.encodedFrames - previous.encodedFrames
        val calls = current.encodeBegin - previous.encodeBegin
        val micros = current.encodeMicros - previous.encodeMicros
        val captured = current.captured - previous.captured
        val offered = current.encoderInput - previous.encoderInput
        val empty = current.encodedEmpty - previous.encodedEmpty

        return EncoderConditions(
            // `encf` -- calls that produced a payload -- over the tier's own frame rate.
            // Not `enc`: that counts calls that returned success with nothing to send, and
            // reading it as a rate is the Phase 3 mistake this comment exists to prevent.
            actualFpsRatio = (produced / seconds) / configuredFps,
            latencyMillis = if (calls > 0) (micros.toDouble() / calls) / 1_000.0 else 0.0,
            // Frames the camera produced that never reached the encoding port. This is the
            // boundary Phase 3 proved the 30-to-7 drop sat at, so it is measured at exactly
            // that boundary rather than inferred from the codec's own view of its input.
            inputStarvationRatio = if (captured > 0) {
                ((captured - offered).coerceAtLeast(0).toDouble() / captured).coerceIn(0.0, 1.0)
            } else {
                0.0
            },
            // Codec calls that came back with nothing. On a healthy encoder this is ~0; it
            // was the whole of the Phase 3 deadlock's signature when the output pool had
            // been exhausted, and a non-zero value with everything else healthy is a
            // correctness regression to fix rather than a reason to drop a tier.
            outputStarvationRatio = if (calls > 0) {
                (empty.coerceAtLeast(0).toDouble() / calls).coerceIn(0.0, 1.0)
            } else {
                0.0
            },
        )
    }

    private fun decoder(
        previous: VideoLegTelemetry.FrameReading?,
        current: VideoLegTelemetry.FrameReading?,
    ): DecoderConditions {
        if (previous == null || current == null) return DecoderConditions()
        if (current.decoded < previous.decoded) return DecoderConditions()

        val assembled = current.assembled - previous.assembled
        val decoded = current.decoded - previous.decoded
        val scans = current.scans - previous.scans
        val missing = current.scansMissing - previous.scansMissing

        return DecoderConditions(
            // The decode stage's *yield*: of the whole pictures reassembled from RTP, how
            // many came out of the decoder. The remote's configured frame rate would be the
            // other candidate denominator and we do not know it -- it is the far end's tier,
            // not ours. Yield is both knowable and the thing that actually moves: Phase 3's
            // healthy pair ran asm 29.8 / dec 29.5, and the broken one asm 29.8 / dec 0.5.
            actualFpsRatio = if (assembled > 0) {
                (decoded.toDouble() / assembled).coerceIn(0.0, 1.0)
            } else {
                1.0
            },
            // Scans that hit a gap in the buffer, which is loss seen from the assembler's
            // side rather than from RTCP's -- and is what a stuttering remote picture is.
            incompletePictureRatio = if (scans > 0) {
                (missing.coerceAtLeast(0).toDouble() / scans).coerceIn(0.0, 1.0)
            } else {
                0.0
            },
            // Pictures assembled that never reached the codec at all.
            starvationRatio = if (assembled > 0) {
                ((assembled - decoded).coerceAtLeast(0).toDouble() / assembled).coerceIn(0.0, 1.0)
            } else {
                0.0
            },
        )
    }

    /**
     * The worst of several legs, which is the one the device has to cope with.
     *
     * A mesh handset runs one policy, not one per leg, because the thing being rationed —
     * the encoder, the radio, the SoC — is shared and the codec parameter that configures
     * it is endpoint-wide. So several legs reduce to one set of conditions, and the
     * reduction is a worst-case rather than a mean: one peer's stream collapsing is
     * precisely the case a mean hides, and two healthy tiles do not make a frozen third
     * one acceptable.
     */
    fun worstOf(legs: List<VideoConditions>): VideoConditions? {
        if (legs.isEmpty()) return null
        val first = legs.first()
        if (legs.size == 1) return first
        return first.copy(
            network = NetworkConditions(
                lossFraction = legs.maxOf { it.network.lossFraction },
                rttMillis = legs.maxOf { it.network.rttMillis },
                jitterMillis = legs.maxOf { it.network.jitterMillis },
                // Summed, not maximised: this is what the device is actually putting on the
                // wire across every leg, and the aggregate is the thing the budget bounds.
                txBitrateBps = legs.sumOf { it.network.txBitrateBps },
                feedbackPerSecond = legs.maxOf { it.network.feedbackPerSecond },
            ),
            encoder = EncoderConditions(
                actualFpsRatio = legs.minOf { it.encoder.actualFpsRatio },
                latencyMillis = legs.maxOf { it.encoder.latencyMillis },
                inputStarvationRatio = legs.maxOf { it.encoder.inputStarvationRatio },
                outputStarvationRatio = legs.maxOf { it.encoder.outputStarvationRatio },
            ),
            decoder = DecoderConditions(
                actualFpsRatio = legs.minOf { it.decoder.actualFpsRatio },
                incompletePictureRatio = legs.maxOf { it.decoder.incompletePictureRatio },
                starvationRatio = legs.maxOf { it.decoder.starvationRatio },
            ),
        )
    }
}

/** The encoder settings one tier implies, ready for `setVideoCodecParam`. */
internal data class VideoEncoderSettings(
    val width: Long,
    val height: Long,
    val fps: Int,
    val avgBps: Long,
    val maxBps: Long,
) {
    companion object {
        /**
         * [profile] under [budget], as the numbers pjsua2 wants.
         *
         * `avgBps` is the profile's target and `maxBps` the budget's verdict, so a mesh leg
         * is asked for the same picture as a one-to-one leg but is not allowed to peak as
         * far — which is the whole of what sharing an aggregate means in practice.
         */
        fun of(profile: VideoQualityProfile, budget: VideoBudget) = VideoEncoderSettings(
            width = profile.width.toLong(),
            height = profile.height.toLong(),
            fps = profile.fps,
            avgBps = profile.targetBps.toLong(),
            maxBps = budget.maxBpsFor(profile).toLong(),
        )
    }
}
