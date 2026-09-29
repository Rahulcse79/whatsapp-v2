package com.whatsappv2.data.sip.registration.stack

import com.whatsappv2.core.common.logging.NoOpLogger
import com.whatsappv2.domain.video.DevicePressure
import com.whatsappv2.domain.video.DevicePressureSource
import com.whatsappv2.domain.video.DisplayCeiling
import com.whatsappv2.domain.video.ThermalPressure
import com.whatsappv2.domain.video.VideoBudget
import com.whatsappv2.domain.video.VideoQualityTier
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The coordinator's own rules: one policy for the device, and a hard bound on renegotiation.
 *
 * The *policy* is tested in `:domain` with a fake clock and no PJSIP. What is left here is
 * everything that could not live there — how several legs become one decision, what happens
 * across a media restart, and the token bucket that exists because a per-change cooldown
 * cannot bound a sequence.
 */
class VideoQualityCoordinatorTest {

    private val tick = 2_000L

    private class FixedPressure(var pressure: DevicePressure = DevicePressure()) : DevicePressureSource {
        override fun sample(): DevicePressure = pressure
    }

    private fun coordinator(pressure: DevicePressureSource = FixedPressure()) =
        VideoQualityCoordinator(pressure, NoOpLogger)

    /** A leg whose counters advance healthily by [frames] pictures each tick. */
    private fun leg(
        key: String,
        at: Long,
        packets: Long,
        loss: Long = 0,
        frames: Long = 60,
        encodeBegin: Long = 60,
    ) = VideoLegTelemetrySample(
        callKey = key,
        rtp = VideoRtpSample(
            atMillis = at,
            txPkt = packets,
            txBytes = packets * 1_200,
            txLoss = loss,
            rxPkt = packets,
            rxLoss = loss,
            rxJitterUsec = 5_000,
            rttUsec = 40_000,
            feedbackRx = 0,
        ),
        frames = VideoLegTelemetry.FrameReading(
            peer = "peer-$key",
            atMillis = at,
            captured = frames,
            encoded = encodeBegin,
            encodedFrames = frames,
            encodedEmpty = encodeBegin - frames,
            decoded = frames,
            renderSubmit = frames,
            renderSubmitNew = frames,
            renderReject = 0,
            encoderInput = frames,
            encodeBegin = encodeBegin,
            encodeMicros = encodeBegin * 12_000,
            scans = frames,
            scansMissing = 0,
            assembled = frames,
        ),
    )

    @Test
    fun `no legs produces nothing and does not build a policy`() {
        val coordinator = coordinator()
        assertNull(coordinator.onSample(emptyList(), 1_000))
        assertNull(coordinator.current)
    }

    @Test
    fun `a healthy one-to-one call is left alone`() {
        val coordinator = coordinator()
        var packets = 0L
        var frames = 0L
        repeat(60) { i ->
            packets += 120
            frames += 60
            val action = coordinator.onSample(
                listOf(leg("a", at = i * tick, packets = packets, frames = frames, encodeBegin = frames)),
                i * tick,
            )
            assertNull(action, "a healthy call must not change tier (tick $i)")
        }
        assertEquals(VideoQualityTier.HIGH, coordinator.current?.tier)
    }

    @Test
    fun `a second video leg makes it a conference immediately`() {
        val coordinator = coordinator()
        var packets = 0L
        var frames = 0L
        repeat(5) { i ->
            packets += 120; frames += 60
            coordinator.onSample(listOf(leg("a", i * tick, packets, frames = frames, encodeBegin = frames)), i * tick)
        }
        assertEquals(1280, coordinator.current?.width)

        packets += 120; frames += 60
        val action = coordinator.onSample(
            listOf(
                leg("a", 5 * tick, packets, frames = frames, encodeBegin = frames),
                leg("b", 5 * tick, packets, frames = frames, encodeBegin = frames),
            ),
            5 * tick,
        )

        assertNotNull(action, "a second leg changes the ladder and must be applied at once")
        assertTrue(action.settings.width <= 960, "a conference must not keep sending 720p")
        // The tier applies to both legs: the codec parameter is endpoint-wide, and each leg
        // picks it up at its next stream build.
        assertEquals(setOf("a", "b"), action.appliesTo)
    }

    @Test
    fun `the worst leg decides, not the average`() {
        val coordinator = coordinator()
        var packets = 0L
        var frames = 0L
        var healthy = 0L
        var lost = 0L

        var action: VideoQualityAction? = null
        repeat(20) { i ->
            packets += 120
            frames += 60
            healthy += 60
            lost += 40          // one leg losing a third of everything it sends
            val legs = listOf(
                leg("good", i * tick, packets, frames = frames, encodeBegin = frames),
                leg("bad", i * tick, packets, loss = lost, frames = healthy, encodeBegin = frames),
            )
            coordinator.onSample(legs, i * tick)?.let { if (it.transition.isDowngrade) action = it }
        }

        assertNotNull(action, "a collapsing leg must cost quality even beside a healthy one")
    }

    @Test
    fun `thermal reaches the policy through the source, and corroborates rather than decides`() {
        val pressure = FixedPressure()
        val coordinator = coordinator(pressure)
        var packets = 0L
        var frames = 0L

        repeat(3) { i ->
            packets += 120; frames += 60
            coordinator.onSample(listOf(leg("a", i * tick, packets, frames = frames, encodeBegin = frames)), i * tick)
        }

        // SEVERE with the media plainly healthy: the source is being read, and the policy is
        // right not to act on it. Measured on an SM-E236B -- see the policy's own test.
        pressure.pressure = DevicePressure(thermal = ThermalPressure.SEVERE, cpuLoad = 0.9)
        repeat(20) { i ->
            val t = (i + 3) * tick
            packets += 120; frames += 60
            val action = coordinator.onSample(
                listOf(leg("a", t, packets, frames = frames, encodeBegin = frames)), t,
            )
            assertNull(action, "skin temperature alone must not cost a sustainable tier")
        }
        assertEquals(VideoQualityTier.HIGH, coordinator.current?.tier)

        // The same thermal status, now with an encoder that is not making its rate.
        var dropped = false
        repeat(20) { i ->
            val t = (i + 23) * tick
            packets += 120
            frames += 6          // 3 fps out of a configured 30
            coordinator.onSample(
                listOf(leg("a", t, packets, frames = frames, encodeBegin = frames * 10)), t,
            )?.let { dropped = true }
        }
        assertTrue(dropped, "thermal plus a starved encoder must cost quality")
    }

    @Test
    fun `critical thermal is sufficient on its own`() {
        val pressure = FixedPressure()
        val coordinator = coordinator(pressure)
        var packets = 0L
        var frames = 0L
        repeat(3) { i ->
            packets += 120; frames += 60
            coordinator.onSample(listOf(leg("a", i * tick, packets, frames = frames, encodeBegin = frames)), i * tick)
        }

        // At CRITICAL the platform is actively cutting the device back, so waiting for a
        // counter to prove it is waiting for the picture to break first.
        pressure.pressure = DevicePressure(thermal = ThermalPressure.CRITICAL, cpuLoad = 0.5)
        var dropped = false
        repeat(20) { i ->
            val t = (i + 3) * tick
            packets += 120; frames += 60
            coordinator.onSample(listOf(leg("a", t, packets, frames = frames, encodeBegin = frames)), t)
                ?.let { dropped = true }
        }
        assertTrue(dropped, "CRITICAL must cost quality even with healthy counters")
    }

    @Test
    fun `a thermometer that throws does not take the call's video with it`() {
        val throwing = object : DevicePressureSource {
            override fun sample(): DevicePressure = error("no thermal service on this device")
        }
        val coordinator = coordinator(throwing)
        var packets = 0L
        var frames = 0L
        repeat(10) { i ->
            packets += 120; frames += 60
            coordinator.onSample(listOf(leg("a", i * tick, packets, frames = frames, encodeBegin = frames)), i * tick)
        }
        assertEquals(VideoQualityTier.HIGH, coordinator.current?.tier)
    }

    @Test
    fun `a media restart keeps the tier and forgets the measurements`() {
        val coordinator = coordinator()
        var packets = 0L
        var frames = 0L
        repeat(30) { i ->
            packets += 120
            frames += 6          // a badly starved encoder, so it drops
            coordinator.onSample(listOf(leg("a", i * tick, packets, frames = frames, encodeBegin = frames * 10)), i * tick)
        }
        val dropped = coordinator.current
        assertNotNull(dropped)
        assertTrue(dropped.tier != VideoQualityTier.HIGH, "the starved encoder should have cost a tier")

        coordinator.onMediaRestarted("a")
        assertEquals(dropped, coordinator.current, "a restart is not a reason to re-climb from the floor")
    }

    @Test
    fun `pausing video keeps the tier, because the resume is what applies it`() {
        val coordinator = coordinator()
        var packets = 0L
        var frames = 0L
        repeat(30) { i ->
            packets += 120
            frames += 6
            coordinator.onSample(
                listOf(leg("a", i * tick, packets, frames = frames, encodeBegin = frames * 10)),
                i * tick,
            )
        }
        val learned = coordinator.current
        assertNotNull(learned)
        assertTrue(learned.tier != VideoQualityTier.HIGH, "the starved encoder should have cost a tier")

        // Hold, or video off. The tier must survive: the unhold is the stream build that
        // finally carries it to the encoder.
        coordinator.onVideoPaused()
        assertEquals(learned, coordinator.current)
        assertEquals(learned.fps, coordinator.startingSettings(VideoBudget(1)).fps)
    }

    @Test
    fun `every call ending forgets the tier so the next call measures again`() {
        val coordinator = coordinator()
        coordinator.onSample(listOf(leg("a", 0, 120)), 0)
        coordinator.onSample(listOf(leg("a", tick, 240)), tick)
        assertNotNull(coordinator.current)

        coordinator.onNoCalls()
        assertNull(coordinator.current)
    }

    @Test
    fun `the starting tier is the best the budget allows, before any measurement`() {
        val coordinator = coordinator()
        val solo = coordinator.startingSettings(VideoBudget(outgoingVideoLegs = 1))
        assertEquals(1280, solo.width)
        assertEquals(30, solo.fps)

        // A leg's share of the aggregate, not the profile's own peak.
        val mesh = coordinator.startingSettings(VideoBudget(outgoingVideoLegs = 3))
        assertTrue(mesh.maxBps <= 1_000_000, "three legs share 3 Mbit/s")
    }

    @Test
    fun `a small tile caps what the starting tier will climb to`() {
        val coordinator = coordinator()
        coordinator.onDisplayCeiling(DisplayCeiling(height = 360))
        val settings = coordinator.startingSettings(VideoBudget(outgoingVideoLegs = 3))
        assertTrue(settings.height <= 540, "720p into a 360px tile is waste: got ${settings.height}")
    }

    @Test
    fun `the encoder is judged against the rate the running stream was given`() {
        // A tier change is a standing decision the next stream build picks up, so the stream
        // keeps its old rate in between. Judging it against the policy's new, lower tier would
        // score a struggling encoder as healthy and stall further adaptation.
        val coordinator = coordinator()
        var packets = 0L
        var frames = 0L

        // 18 fps out of a stream configured for 30: unhealthy, and it must be seen as such
        // even though 18 would be comfortable against a 15 fps tier.
        var dropped = false
        repeat(30) { i ->
            packets += 120
            frames += 36          // 18 fps over a two-second tick
            val action = coordinator.onSample(
                listOf(
                    leg("a", i * tick, packets, frames = frames, encodeBegin = frames)
                        .copy(configuredFps = 30),
                ),
                i * tick,
            )
            if (action != null) dropped = true
        }
        assertTrue(dropped, "18 fps against a stream configured for 30 must cost quality")
    }

    @Test
    fun `an action names the calls it applies to and asks for no renegotiation`() {
        val coordinator = coordinator()
        var packets = 0L
        var frames = 0L
        var action: VideoQualityAction? = null
        repeat(30) { i ->
            packets += 120
            frames += 6
            coordinator.onSample(
                listOf(leg("a", i * tick, packets, frames = frames, encodeBegin = frames * 10)),
                i * tick,
            )?.let { action = it }
        }
        assertNotNull(action)
        // The whole of the apply path is "write this where the next stream will read it".
        assertEquals(setOf("a"), action.appliesTo)
        assertTrue(action.settings.width > 0 && action.settings.fps > 0)
    }

    // ---------------------------------------------------------- change budget

    @Test
    fun `six tier changes fit in the window and a seventh does not`() {
        val budget = VideoQualityChangeBudget()
        repeat(VideoQualityChangeBudget.MAX_IN_WINDOW) { i ->
            val at = i * 10_000L
            assertTrue(budget.canSpend(at), "change ${i + 1} should be allowed")
            budget.spend(at)
        }
        assertTrue(
            !budget.canSpend(VideoQualityChangeBudget.MAX_IN_WINDOW * 10_000L),
            "a seventh change inside the window is oscillation, not adaptation",
        )
    }

    @Test
    fun `the window rolls, so adaptation resumes rather than stopping for ever`() {
        val budget = VideoQualityChangeBudget()
        repeat(VideoQualityChangeBudget.MAX_IN_WINDOW) { budget.spend(it * 1_000L) }
        assertTrue(!budget.canSpend(10_000))
        assertTrue(
            budget.canSpend(VideoQualityChangeBudget.WINDOW_MILLIS + 1),
            "holding the tier is a pause, not a terminal state",
        )
    }

    @Test
    fun `an exhausted budget holds the tier instead of moving it`() {
        val spent = VideoQualityChangeBudget()
        repeat(VideoQualityChangeBudget.MAX_IN_WINDOW) { spent.spend(0) }
        val coordinator = VideoQualityCoordinator(FixedPressure(), NoOpLogger, changes = spent)

        var packets = 0L
        var frames = 0L
        repeat(40) { i ->
            packets += 120
            frames += 6
            val action = coordinator.onSample(
                listOf(leg("a", i * tick, packets, loss = packets / 3, frames = frames, encodeBegin = frames * 10)),
                i * tick,
            )
            assertNull(action, "nothing may change while the budget is spent (tick $i)")
        }
        // And the tier never moved either, so the policy and the media still agree.
        assertEquals(VideoQualityTier.HIGH, coordinator.current?.tier)
    }
}
