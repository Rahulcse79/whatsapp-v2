package com.whatsappv2.data.sip.registration.stack

import com.whatsappv2.domain.video.CallShape
import com.whatsappv2.domain.video.DecoderConditions
import com.whatsappv2.domain.video.DevicePressure
import com.whatsappv2.domain.video.EncoderConditions
import com.whatsappv2.domain.video.VideoBudget
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The denominators, pinned against counter values taken off the handsets.
 *
 * This is the file that exists because of a specific mistake. Phase 3 spent a session on
 * `vidcnt enc` being read as the encoder's frame rate when it counts `encode_begin` calls
 * *including the ones that produce nothing* — it read 30/s while the component emitted 4.4
 * pictures a second. Every ratio here therefore names its denominator in a test, using real
 * numbers from that phase, so the next person to change one finds out here rather than on a
 * handset.
 */
class VideoConditionsReducerTest {

    private fun rtp(
        at: Long,
        txPkt: Long = 0,
        txBytes: Long = 0,
        txLoss: Long = 0,
        jitterUsec: Long = 0,
        rttUsec: Long = 0,
        feedback: Long = 0,
    ) = VideoRtpSample(
        atMillis = at,
        txPkt = txPkt,
        txBytes = txBytes,
        txLoss = txLoss,
        rxPkt = 0,
        rxLoss = txLoss,
        rxJitterUsec = jitterUsec,
        rttUsec = rttUsec,
        feedbackRx = feedback,
    )

    private fun frames(
        at: Long,
        captured: Long = 0,
        encoded: Long = 0,
        encodedFrames: Long = 0,
        encodedEmpty: Long = 0,
        decoded: Long = 0,
        encoderInput: Long = 0,
        encodeBegin: Long = 0,
        encodeMicros: Long = 0,
        scans: Long = 0,
        scansMissing: Long = 0,
        assembled: Long = 0,
    ) = VideoLegTelemetry.FrameReading(
        peer = "192.168.2.190:4002",
        atMillis = at,
        captured = captured,
        encoded = encoded,
        encodedFrames = encodedFrames,
        encodedEmpty = encodedEmpty,
        decoded = decoded,
        renderSubmit = 0,
        renderSubmitNew = 0,
        renderReject = 0,
        encoderInput = encoderInput,
        encodeBegin = encodeBegin,
        encodeMicros = encodeMicros,
        scans = scans,
        scansMissing = scansMissing,
        assembled = assembled,
    )

    private fun reduce(
        previousRtp: VideoRtpSample?,
        currentRtp: VideoRtpSample,
        previousFrames: VideoLegTelemetry.FrameReading? = null,
        currentFrames: VideoLegTelemetry.FrameReading? = null,
        configuredFps: Int = 30,
        fallbackEncoder: EncoderConditions? = null,
        fallbackDecoder: DecoderConditions? = null,
    ) = VideoConditionsReducer.reduce(
        previousRtp = previousRtp,
        currentRtp = currentRtp,
        previousFrames = previousFrames,
        currentFrames = currentFrames,
        configuredFps = configuredFps,
        shape = CallShape.ONE_TO_ONE,
        budget = VideoBudget(outgoingVideoLegs = 1),
        device = DevicePressure(),
        fallbackEncoder = fallbackEncoder,
        fallbackDecoder = fallbackDecoder,
    )

    // -------------------------------------------------------------- nothing to say

    @Test
    fun `one reading is not a rate`() {
        assertNull(reduce(previousRtp = null, currentRtp = rtp(at = 1_000)))
    }

    @Test
    fun `no elapsed time is not a rate`() {
        assertNull(reduce(rtp(at = 1_000), rtp(at = 1_000)))
    }

    @Test
    fun `counters that went backwards mean a rebuilt stream, not a collapse`() {
        // A rebuilt stream starts again at zero. Reporting the negative delta as total loss
        // would downgrade a leg that had just come back healthy.
        assertNull(reduce(rtp(at = 1_000, txPkt = 5_000), rtp(at = 3_000, txPkt = 12)))
    }

    // -------------------------------------------------------------------- network

    @Test
    fun `loss is counted against packets sent, not packets received`() {
        val conditions = reduce(
            rtp(at = 0, txPkt = 1_000, txLoss = 0),
            rtp(at = 2_000, txPkt = 1_100, txLoss = 10),
        )
        assertNotNull(conditions)
        // 10 lost out of 100 sent. Against packets *received* this would read 10/90 and get
        // better as the link got worse, which is the wrong direction entirely.
        assertEquals(0.10, conditions.network.lossFraction, 1e-9)
    }

    @Test
    fun `bitrate is bytes over the interval, in bits per second`() {
        val conditions = reduce(
            rtp(at = 0, txPkt = 100, txBytes = 0),
            rtp(at = 2_000, txPkt = 200, txBytes = 400_000),
        )
        assertNotNull(conditions)
        // 400 kB in two seconds is 1.6 Mbit/s.
        assertEquals(1_600_000, conditions.network.txBitrateBps)
    }

    @Test
    fun `jitter and rtt are levels in milliseconds, not deltas`() {
        val conditions = reduce(
            rtp(at = 0, txPkt = 100, jitterUsec = 90_000, rttUsec = 300_000),
            rtp(at = 2_000, txPkt = 200, jitterUsec = 45_000, rttUsec = 120_000),
        )
        assertNotNull(conditions)
        // The newest sample is the answer, and the older one is simply gone -- a running
        // mean would still be carrying the 90 ms jitter and the 300 ms round trip, and would
        // go on blocking recovery long after the network had recovered.
        assertEquals(45.0, conditions.network.jitterMillis, 1e-9)
        assertEquals(120.0, conditions.network.rttMillis, 1e-9)
    }

    @Test
    fun `keyframe requests are a rate`() {
        val conditions = reduce(
            rtp(at = 0, txPkt = 100, feedback = 4),
            rtp(at = 2_000, txPkt = 200, feedback = 10),
        )
        assertNotNull(conditions)
        assertEquals(3.0, conditions.network.feedbackPerSecond, 1e-9)
    }

    // -------------------------------------------------------------------- encoder

    @Test
    fun `the encoder's rate comes from encf, never from enc`() {
        // Phase 3's failure, verbatim: the pipeline asked 60 times in two seconds and the
        // component produced 9 pictures. `enc` says 60 and would read as 1.0 of 30 fps.
        val conditions = reduce(
            rtp(at = 0, txPkt = 100),
            rtp(at = 2_000, txPkt = 200),
            previousFrames = frames(at = 0, encoded = 0, encodedFrames = 0, encodeBegin = 0),
            currentFrames = frames(at = 2_000, encoded = 60, encodedFrames = 9, encodeBegin = 60),
            configuredFps = 30,
        )
        assertNotNull(conditions)
        // 9 in two seconds is 4.5/s against a configured 30.
        assertEquals(4.5 / 30.0, conditions.encoder.actualFpsRatio, 1e-9)
        assertTrue(conditions.encoder.actualFpsRatio < 0.20, "this must read as the collapse it was")
    }

    @Test
    fun `encode latency is total microseconds over calls, in milliseconds`() {
        val conditions = reduce(
            rtp(at = 0, txPkt = 100),
            rtp(at = 2_000, txPkt = 200),
            previousFrames = frames(at = 0, encodeBegin = 0, encodeMicros = 0),
            // 60 calls costing 68.5 ms each -- the M14's measured figure in Phase 3.
            currentFrames = frames(at = 2_000, encodeBegin = 60, encodeMicros = 4_110_000),
        )
        assertNotNull(conditions)
        assertEquals(68.5, conditions.encoder.latencyMillis, 1e-6)
    }

    @Test
    fun `input starvation is captured frames that never reached the encoding port`() {
        val conditions = reduce(
            rtp(at = 0, txPkt = 100),
            rtp(at = 2_000, txPkt = 200),
            previousFrames = frames(at = 0, captured = 0, encoderInput = 0),
            // 60 captured, 20 offered: the 30-to-7 boundary Phase 3 measured.
            currentFrames = frames(at = 2_000, captured = 60, encoderInput = 20),
        )
        assertNotNull(conditions)
        assertEquals(40.0 / 60.0, conditions.encoder.inputStarvationRatio, 1e-9)
    }

    @Test
    fun `output starvation is codec calls that came back with nothing`() {
        val conditions = reduce(
            rtp(at = 0, txPkt = 100),
            rtp(at = 2_000, txPkt = 200),
            previousFrames = frames(at = 0, encodeBegin = 0, encodedEmpty = 0),
            currentFrames = frames(at = 2_000, encodeBegin = 60, encodedEmpty = 51),
        )
        assertNotNull(conditions)
        assertEquals(51.0 / 60.0, conditions.encoder.outputStarvationRatio, 1e-9)
    }

    @Test
    fun `a healthy Phase 3 baseline reads as healthy`() {
        // The numbers the final Phase 3 run actually produced on 1005, over two seconds.
        val conditions = reduce(
            rtp(at = 0, txPkt = 1_000, txLoss = 10),
            rtp(at = 2_000, txPkt = 1_120, txLoss = 11, jitterUsec = 6_000, rttUsec = 38_000),
            previousFrames = frames(at = 0),
            currentFrames = frames(
                at = 2_000,
                captured = 60, encoded = 60, encodedFrames = 60, encodeBegin = 60,
                encoderInput = 60, encodeMicros = 60 * 21_600,
                decoded = 45, assembled = 46, scans = 50, scansMissing = 0,
            ),
        )
        assertNotNull(conditions)
        assertEquals(1.0, conditions.encoder.actualFpsRatio, 1e-9)
        assertEquals(21.6, conditions.encoder.latencyMillis, 1e-6)
        assertEquals(0.0, conditions.encoder.inputStarvationRatio, 1e-9)
        assertTrue(conditions.network.lossFraction < 0.01)
        assertTrue(conditions.decoder.actualFpsRatio > 0.95)
        assertEquals(0.0, conditions.decoder.incompletePictureRatio, 1e-9)
    }

    // -------------------------------------------------------------------- decoder

    @Test
    fun `the decode ratio is yield over assembled pictures, not a guess at the remote rate`() {
        val conditions = reduce(
            rtp(at = 0, txPkt = 100),
            rtp(at = 2_000, txPkt = 200),
            previousFrames = frames(at = 0, assembled = 0, decoded = 0),
            // Phase 3's broken pair: asm 29.8/s, dec 0.5/s.
            currentFrames = frames(at = 2_000, assembled = 60, decoded = 1),
        )
        assertNotNull(conditions)
        assertEquals(1.0 / 60.0, conditions.decoder.actualFpsRatio, 1e-9)
        assertEquals(59.0 / 60.0, conditions.decoder.starvationRatio, 1e-9)
    }

    @Test
    fun `incomplete pictures are scans that hit a gap`() {
        val conditions = reduce(
            rtp(at = 0, txPkt = 100),
            rtp(at = 2_000, txPkt = 200),
            previousFrames = frames(at = 0, scans = 0, scansMissing = 0),
            currentFrames = frames(at = 2_000, scans = 200, scansMissing = 9),
        )
        assertNotNull(conditions)
        assertEquals(0.045, conditions.decoder.incompletePictureRatio, 1e-9)
    }

    @Test
    fun `nothing assembled is not a decoder failure`() {
        // A leg that received nothing this interval has a broken *network*, which the RTP
        // half already says. Reporting a zero decode yield as well would double-count it.
        val conditions = reduce(
            rtp(at = 0, txPkt = 100),
            rtp(at = 2_000, txPkt = 200),
            previousFrames = frames(at = 0),
            currentFrames = frames(at = 2_000),
        )
        assertNotNull(conditions)
        assertEquals(1.0, conditions.decoder.actualFpsRatio, 1e-9)
    }

    // ------------------------------------------------------- mismatched cadences

    @Test
    fun `a tick with no new counter line carries the last figures rather than defaulting`() {
        val stale = frames(at = 2_000, encodedFrames = 9, encodeBegin = 60)
        val collapsed = EncoderConditions(actualFpsRatio = 0.15, latencyMillis = 68.5)

        val conditions = reduce(
            rtp(at = 2_000, txPkt = 200),
            rtp(at = 4_000, txPkt = 300),
            previousFrames = stale,
            currentFrames = stale,
            fallbackEncoder = collapsed,
        )

        assertNotNull(conditions)
        // The default would be 1.0 -- "the encoder is keeping up perfectly" -- folded into
        // the mean of an encoder that is doing nothing of the kind.
        assertEquals(0.15, conditions.encoder.actualFpsRatio, 1e-9)
        assertEquals(68.5, conditions.encoder.latencyMillis, 1e-9)
    }

    @Test
    fun `with no history and no new counters the pipeline reports its neutral default`() {
        val conditions = reduce(rtp(at = 0, txPkt = 100), rtp(at = 2_000, txPkt = 200))
        assertNotNull(conditions)
        assertEquals(EncoderConditions(), conditions.encoder)
        assertEquals(DecoderConditions(), conditions.decoder)
    }

    // ------------------------------------------------------------- several legs

    @Test
    fun `several legs reduce to the worst of them, and the bitrate to their sum`() {
        val good = reduce(
            rtp(at = 0, txPkt = 100, txBytes = 0),
            rtp(at = 2_000, txPkt = 200, txBytes = 150_000, jitterUsec = 5_000),
            previousFrames = frames(at = 0),
            currentFrames = frames(at = 2_000, encodedFrames = 60, encodeBegin = 60, assembled = 60, decoded = 60),
        )!!
        val bad = reduce(
            rtp(at = 0, txPkt = 100, txBytes = 0, txLoss = 0),
            rtp(at = 2_000, txPkt = 200, txBytes = 150_000, txLoss = 30, jitterUsec = 200_000),
            previousFrames = frames(at = 0),
            currentFrames = frames(at = 2_000, encodedFrames = 6, encodeBegin = 60, assembled = 60, decoded = 3),
        )!!

        val worst = VideoConditionsReducer.worstOf(listOf(good, bad))
        assertNotNull(worst)

        // One frozen tile is not made acceptable by two that are not.
        assertEquals(bad.network.lossFraction, worst.network.lossFraction, 1e-9)
        assertEquals(bad.encoder.actualFpsRatio, worst.encoder.actualFpsRatio, 1e-9)
        assertEquals(bad.decoder.actualFpsRatio, worst.decoder.actualFpsRatio, 1e-9)
        assertEquals(200.0, worst.network.jitterMillis, 1e-9)
        // Bitrate is the exception: it is summed, because the aggregate is what the budget
        // bounds and what the radio is actually carrying.
        assertEquals(
            good.network.txBitrateBps + bad.network.txBitrateBps,
            worst.network.txBitrateBps,
        )
    }

    @Test
    fun `one leg reduces to itself and no legs to nothing`() {
        val only = reduce(rtp(at = 0, txPkt = 100), rtp(at = 2_000, txPkt = 200))!!
        assertEquals(only, VideoConditionsReducer.worstOf(listOf(only)))
        assertNull(VideoConditionsReducer.worstOf(emptyList()))
    }
}
