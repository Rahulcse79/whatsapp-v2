package com.whatsappv2.data.sip.registration.stack

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * What one directed media leg has done, counted rather than narrated (Phase 2).
 *
 * ## Why counters and not more logging
 *
 * A frozen tile asks two questions a log line cannot answer on its own: *when* did this
 * leg stop, and *what stopped first*. The periodic media trace already answers the first
 * for RTP. This answers the second, by keeping the handful of events that mark a boundary
 * in the pipeline — the stream being rebuilt under the decoder, the decoder announcing it
 * has lost its reference frame, the decoder announcing it has found one again — and
 * reporting them as deltas beside the RTP rates they have to be read against.
 *
 * Counting rather than logging is the whole point. `PJMEDIA_EVENT_KEYFRAME_MISSING` can
 * arrive per frame on a leg that has lost its reference, so a line per event is precisely
 * the flood that makes the real signal unreadable — and it arrives on PJSIP's own media
 * thread, where a log write is the last thing that belongs. Each event here is one atomic
 * increment; the numbers are read and written out by the trace that was already running.
 *
 * ## What it deliberately does not do
 *
 * Nothing here changes what the media does. It does not request a keyframe, re-create a
 * stream, touch the camera, or alter codec, bitrate or resolution. It is a measurement,
 * and Phase 2 is measurement only — the counters exist so that a later change can be shown
 * to have worked rather than asserted to have.
 *
 * ## What it cannot see
 *
 * `pjsua2` exposes no video frame counters — `StreamStat` carries RTCP and jitter-buffer
 * state and nothing else — so capture, encode, decode and render-submission rates cannot
 * come from the bindings. They are counted natively instead (see the numbered PJSIP patch)
 * and arrive here as a `vidcnt` line, parsed by [VideoFrameCounterLine].
 *
 * What is still **not** measured is actual presentation. The deepest native boundary is
 * `pjmedia_vid_dev_stream_put_frame()`, which on Android posts the frame to an OpenGL job
 * queue and returns — so it is render *submission*, and is named that way everywhere. A
 * tile that is black while `render-submit` advances is a question for the renderer, the
 * surface or the GPU, which is precisely the boundary this draws.
 */
internal class VideoLegTelemetry {

    private val legs = ConcurrentHashMap<String, Leg>()

    /** The counters for one stream of one call, created on first touch. */
    fun leg(callKey: String, streamIndex: Long): Leg =
        legs.computeIfAbsent(key(callKey, streamIndex)) { Leg() }

    /** Forgets every leg of [callKey], so a finished call stops being reported. */
    fun forget(callKey: String) {
        val prefix = "$callKey#"
        legs.keys.removeAll { it.startsWith(prefix) }
    }

    private fun key(callKey: String, streamIndex: Long) = "$callKey#$streamIndex"

    /**
     * One leg's counters.
     *
     * Every field is an [AtomicLong] because the writers are PJSIP's media and callback
     * threads and the reader is the scheduled trace — different threads, no lock, and an
     * increment that is allowed to be seen late but never torn.
     */
    class Leg {
        /** `pjsua` built a new stream — and with it a new decoder, at its default format. */
        val streamCreated = AtomicLong()

        /** `pjsua` tore the stream down. Paired with [streamCreated] to spot a rebuild. */
        val streamDestroyed = AtomicLong()

        /** The decoder has no reference frame: everything it is fed decodes to nothing. */
        val keyframeMissing = AtomicLong()

        /** The decoder got its keyframe. This is what recovery actually looks like. */
        val keyframeFound = AtomicLong()

        /** The decoded picture changed shape — a peer that rotated, or re-negotiated. */
        val formatChanged = AtomicLong()

        /** RTCP feedback arrived from the far end (PLI or NACK). */
        val rtcpFeedbackRx = AtomicLong()

        /** The capture or render device failed. A black *self*-view starts here. */
        val deviceError = AtomicLong()

        /** When the stream was last (re)built, so an error can be placed either side of it. */
        @Volatile
        var lastCreatedAtMillis: Long = 0L

        /** The counters as they were at the previous trace, so each line can state a delta. */
        @Volatile
        internal var previous: Snapshot = Snapshot()

        fun snapshot() = Snapshot(
            streamCreated = streamCreated.get(),
            streamDestroyed = streamDestroyed.get(),
            keyframeMissing = keyframeMissing.get(),
            keyframeFound = keyframeFound.get(),
            formatChanged = formatChanged.get(),
            rtcpFeedbackRx = rtcpFeedbackRx.get(),
            deviceError = deviceError.get(),
        )
    }

    // ------------------------------------------------- native frame counters

    /** The last native counter reading for each leg, by the peer RTP address. */
    private val frames = ConcurrentHashMap<String, FrameReading>()

    /**
     * Takes one `vidcnt` line from PJSIP's own log.
     *
     * The counters are produced natively because `pjsua2` exposes no video frame
     * counters at all — `StreamStat` carries RTCP and jitter-buffer state and nothing
     * else — so the stages between them are invisible from here without this.
     */
    fun recordFrameCounters(reading: FrameReading) {
        frames[reading.peer] = reading
    }

    /** The last reading for the leg reaching [peer], or null before the first line. */
    fun framesFor(peer: String): FrameReading? = frames[peer]

    /** Forgets a leg's frame counters. Called when its call ends. */
    fun forgetFrames(peer: String) {
        frames -= peer
    }

    /**
     * One native reading: raw counters and when they were read.
     *
     * Raw and monotonic, never rates. The rate is stated by differencing two readings
     * over their own elapsed time, because the native emission interval and the trace
     * interval do not line up and a rate computed against the wrong one is wrong twice.
     */
    data class FrameReading(
        val peer: String,
        val atMillis: Long,
        val captured: Long,
        val encoded: Long,
        val decoded: Long,
        val renderSubmit: Long,
        val renderReject: Long,
    )

    /** A reading of one leg's counters, for differencing against the next. */
    data class Snapshot(
        val streamCreated: Long = 0,
        val streamDestroyed: Long = 0,
        val keyframeMissing: Long = 0,
        val keyframeFound: Long = 0,
        val formatChanged: Long = 0,
        val rtcpFeedbackRx: Long = 0,
        val deviceError: Long = 0,
    )
}

/**
 * The part of a trace line that describes the pipeline rather than the wire.
 *
 * Empty when nothing happened in the interval, because the common case is a healthy leg
 * and a line that says "0 0 0 0 0" every fifteen seconds on every leg is how a log stops
 * being read. A leg that is working quietly adds nothing; a leg that is being rebuilt, or
 * whose decoder has lost its reference, says so.
 *
 * `recreated` is deliberately the first field. It is the event this whole phase exists to
 * correlate against: a stream rebuilt under a running decoder is the boundary that the
 * mesh crosses six times where a one-to-one call crosses it once.
 */
internal fun videoPipelineTraceFragment(
    previous: VideoLegTelemetry.Snapshot,
    current: VideoLegTelemetry.Snapshot,
    millisSinceCreated: Long?,
): String {
    fun delta(now: Long, before: Long) = now - before

    val parts = buildList {
        delta(current.streamCreated, previous.streamCreated)
            .takeIf { it > 0 }?.let { add("recreated $it") }
        delta(current.streamDestroyed, previous.streamDestroyed)
            .takeIf { it > 0 }?.let { add("destroyed $it") }
        delta(current.keyframeMissing, previous.keyframeMissing)
            .takeIf { it > 0 }?.let { add("kf-missing $it") }
        delta(current.keyframeFound, previous.keyframeFound)
            .takeIf { it > 0 }?.let { add("kf-found $it") }
        delta(current.formatChanged, previous.formatChanged)
            .takeIf { it > 0 }?.let { add("fmt-changed $it") }
        delta(current.rtcpFeedbackRx, previous.rtcpFeedbackRx)
            .takeIf { it > 0 }?.let { add("rtcp-fb-rx $it") }
        delta(current.deviceError, previous.deviceError)
            .takeIf { it > 0 }?.let { add("dev-error $it") }
    }

    if (parts.isEmpty()) return ""
    // How long the stream has been up, but only while it is young enough for the answer to
    // be the interesting one: an error 400 ms after a rebuild and an error twenty minutes
    // in are different findings, and without this they read identically.
    val age = millisSinceCreated?.takeIf { it in 0..STREAM_AGE_REPORTED_FOR_MILLIS }
    return buildString {
        append(" - ").append(parts.joinToString(" "))
        if (age != null) append(" (stream ").append(age).append("ms old)")
    }
}

/** Past this, "how old is the stream" stops explaining anything and is left out. */
private const val STREAM_AGE_REPORTED_FOR_MILLIS = 60_000L

/**
 * Reads PJSIP's `vidcnt` line, or returns null for every other line it is shown.
 *
 * The line is emitted by the patched `vid_stream.c` once every few seconds per stream:
 *
 * ```
 * vidcnt peer=192.168.2.198:26286 cap=1482 enc=1480 dec=1455 sub=1455 rej=0
 * ```
 *
 * Parsed rather than plumbed through `pjsua2` because exposing a new native struct to
 * Java means regenerating the SWIG bindings, and the log is a channel that already
 * exists and already crosses the boundary. The peer RTP address is the join key: the
 * application sees the same string in `StreamInfo.remoteRtpAddress`.
 */
internal object VideoFrameCounterLine {

    private val PATTERN = Regex(
        """vidcnt peer=(\S+) cap=(\d+) enc=(\d+) dec=(\d+) sub=(\d+) rej=(\d+)""",
    )

    /** Cheap enough to run on every log line: a substring test before any regex. */
    fun parse(message: String, atMillis: Long): VideoLegTelemetry.FrameReading? {
        if (MARKER !in message) return null
        val m = PATTERN.find(message) ?: return null
        return VideoLegTelemetry.FrameReading(
            peer = m.groupValues[1],
            atMillis = atMillis,
            captured = m.groupValues[2].toLong(),
            encoded = m.groupValues[3].toLong(),
            decoded = m.groupValues[4].toLong(),
            renderSubmit = m.groupValues[5].toLong(),
            renderReject = m.groupValues[6].toLong(),
        )
    }

    const val MARKER = "vidcnt peer="
}

/**
 * `capture=29.8 encode=29.7 decode=29.4 render-submit=29.4` from two native readings.
 *
 * Measured, and never the negotiated rate: the trace states the negotiated figure
 * separately, beside the resolution, so a leg running `@30` while decoding 0.4 frames a
 * second reads as the fault it is rather than as agreement.
 *
 * Null when there is nothing honest to say — one reading, no elapsed time, or counters
 * that went backwards because the stream was rebuilt and started again at zero.
 */
internal fun videoFrameRateFragment(
    previous: VideoLegTelemetry.FrameReading?,
    current: VideoLegTelemetry.FrameReading?,
): String {
    if (previous == null || current == null) return ""
    val seconds = (current.atMillis - previous.atMillis) / 1_000.0
    if (seconds <= 0.0) return ""
    if (current.decoded < previous.decoded || current.renderSubmit < previous.renderSubmit) return ""

    fun fps(now: Long, before: Long) = String.format("%.1f", (now - before) / seconds)

    return buildString {
        append(" - capture ").append(fps(current.captured, previous.captured))
        append(" encode ").append(fps(current.encoded, previous.encoded))
        append(" decode ").append(fps(current.decoded, previous.decoded))
        append(" render-submit ").append(fps(current.renderSubmit, previous.renderSubmit))
        append(" fps")
        // Only when it happened: a device refusing frames is silent otherwise, and is
        // the difference between "pjmedia stopped" and "the surface went away".
        (current.renderReject - previous.renderReject).takeIf { it > 0 }
            ?.let { append(" render-reject ").append(it) }
    }
}
