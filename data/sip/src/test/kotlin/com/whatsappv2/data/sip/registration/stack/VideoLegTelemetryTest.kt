package com.whatsappv2.data.sip.registration.stack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the pipeline half of a media trace says, and — mostly — when it says nothing.
 *
 * The counters themselves are atomics written by PJSIP's threads and are not worth a test.
 * The part that can be wrong is the reduction to a line: a healthy leg must stay silent,
 * or a four-party call writes twelve lines of zeroes every fifteen seconds and the signal
 * this phase exists to produce is buried in it.
 */
class VideoLegTelemetryTest {

    @Test
    fun `a leg where nothing happened says nothing`() {
        val snapshot = VideoLegTelemetry.Snapshot(streamCreated = 1, keyframeFound = 1)

        assertEquals(
            "",
            videoPipelineTraceFragment(previous = snapshot, current = snapshot, millisSinceCreated = 4_000),
        )
    }

    @Test
    fun `a stream rebuilt in the interval is reported, with how long ago`() {
        val before = VideoLegTelemetry.Snapshot(streamCreated = 1, streamDestroyed = 1)
        val after = before.copy(streamCreated = 2, streamDestroyed = 2)

        val line = videoPipelineTraceFragment(before, after, millisSinceCreated = 420)

        assertTrue("recreated 1" in line, line)
        assertTrue("destroyed 1" in line, line)
        assertTrue("(stream 420ms old)" in line, line)
    }

    @Test
    fun `a decoder that lost its reference and got it back reads as both`() {
        // The pair that distinguishes "rebuilt and recovered" from "rebuilt and never did",
        // which is the whole question a black tile asks.
        val before = VideoLegTelemetry.Snapshot()
        val after = VideoLegTelemetry.Snapshot(keyframeMissing = 9, keyframeFound = 1)

        val line = videoPipelineTraceFragment(before, after, millisSinceCreated = 1_000)

        assertTrue("kf-missing 9" in line, line)
        assertTrue("kf-found 1" in line, line)
    }

    @Test
    fun `a decoder still missing its keyframe reports no recovery`() {
        val before = VideoLegTelemetry.Snapshot(keyframeMissing = 40, keyframeFound = 1)
        val after = before.copy(keyframeMissing = 90)

        val line = videoPipelineTraceFragment(before, after, millisSinceCreated = 30_000)

        assertTrue("kf-missing 50" in line, line)
        assertTrue("kf-found" !in line, "recovery must not be implied when none happened: $line")
    }

    @Test
    fun `the stream age is left out once it stops explaining anything`() {
        val before = VideoLegTelemetry.Snapshot()
        val after = VideoLegTelemetry.Snapshot(keyframeMissing = 1)

        val young = videoPipelineTraceFragment(before, after, millisSinceCreated = 500)
        val old = videoPipelineTraceFragment(before, after, millisSinceCreated = 20 * 60_000)

        assertTrue("stream 500ms old" in young, young)
        assertTrue("old)" !in old, "a twenty-minute-old stream explains nothing: $old")
    }

    @Test
    fun `an unknown stream age is simply absent`() {
        val before = VideoLegTelemetry.Snapshot()
        val after = VideoLegTelemetry.Snapshot(deviceError = 1)

        val line = videoPipelineTraceFragment(before, after, millisSinceCreated = null)

        assertTrue("dev-error 1" in line, line)
        assertTrue("stream" !in line, line)
    }

    @Test
    fun `received RTCP feedback is counted separately from anything it caused`() {
        // PLI arriving from the far end is an instruction to this device's encoder, not a
        // fault on this device's decoder, and the two must never be read as one number.
        val before = VideoLegTelemetry.Snapshot()
        val after = VideoLegTelemetry.Snapshot(rtcpFeedbackRx = 3, formatChanged = 1)

        val line = videoPipelineTraceFragment(before, after, millisSinceCreated = 2_000)

        assertTrue("rtcp-fb-rx 3" in line, line)
        assertTrue("fmt-changed 1" in line, line)
    }

    @Test
    fun `a leg is created once and shared by both writers`() {
        val telemetry = VideoLegTelemetry()

        telemetry.leg("call-a", 1).keyframeMissing.incrementAndGet()
        telemetry.leg("call-a", 1).keyframeMissing.incrementAndGet()

        assertEquals(2, telemetry.leg("call-a", 1).snapshot().keyframeMissing)
        assertEquals(0, telemetry.leg("call-a", 2).snapshot().keyframeMissing, "a second stream is its own leg")
        assertEquals(0, telemetry.leg("call-b", 1).snapshot().keyframeMissing, "a second call is its own leg")
    }

    @Test
    fun `forgetting a call drops its legs and nobody else's`() {
        val telemetry = VideoLegTelemetry()
        telemetry.leg("call-a", 1).keyframeMissing.incrementAndGet()
        telemetry.leg("call-ab", 1).keyframeMissing.incrementAndGet()
        telemetry.leg("call-b", 1).keyframeMissing.incrementAndGet()

        telemetry.forget("call-a")

        assertEquals(0, telemetry.leg("call-a", 1).snapshot().keyframeMissing)
        assertEquals(1, telemetry.leg("call-ab", 1).snapshot().keyframeMissing, "a prefix is not a match")
        assertEquals(1, telemetry.leg("call-b", 1).snapshot().keyframeMissing)
    }
}
