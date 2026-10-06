package com.whatsappv2.domain.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a tile says, and — the part that matters — when it stops saying "live".
 *
 * The case this exists for was measured on 2026-10-05: a leg whose decoder stopped while the
 * stream stayed negotiated and the last frame stayed on screen. Nothing in the old inputs
 * could express it, so the grid showed a still picture indefinitely.
 */
class VideoTileStateReducerTest {

    private fun reading(
        decoded: Long,
        atMillis: Long,
        streamActive: Boolean = true,
        frameKnown: Boolean = true,
        configuredFps: Int? = 15,
    ) = VideoTileReading(
        streamActive = streamActive,
        frameKnown = frameKnown,
        decoded = decoded,
        atMillis = atMillis,
        configuredFps = configuredFps,
    )

    @Test
    fun `a leg with no stream is unavailable whatever it decoded before`() {
        val before = reading(decoded = 900, atMillis = 0)
        val now = reading(decoded = 900, atMillis = 5_000, streamActive = false)

        assertEquals(VideoTileState.UNAVAILABLE, VideoTileStateReducer.reduce(before, now))
    }

    @Test
    fun `a stream that has produced no shape yet is connecting`() {
        val now = reading(decoded = 0, atMillis = 5_000, frameKnown = false)

        assertEquals(VideoTileState.CONNECTING, VideoTileStateReducer.reduce(null, now))
    }

    @Test
    fun `the first tick of a call is connecting, not a verdict`() {
        // One reading is not an interval, so there is no rate to judge.
        assertEquals(
            VideoTileState.CONNECTING,
            VideoTileStateReducer.reduce(null, reading(decoded = 3, atMillis = 5_000)),
        )
    }

    @Test
    fun `decoding at the configured rate is live`() {
        val before = reading(decoded = 0, atMillis = 0)
        val now = reading(decoded = 75, atMillis = 5_000) // 15 fps

        assertEquals(VideoTileState.LIVE, VideoTileStateReducer.reduce(before, now))
    }

    @Test
    fun `a picture that has stopped is unavailable, not merely low quality`() {
        // The M23 case: the stream is up, the shape is known, and nothing is decoding.
        val before = reading(decoded = 450, atMillis = 0)
        val now = reading(decoded = 450, atMillis = 5_000)

        assertEquals(VideoTileState.UNAVAILABLE, VideoTileStateReducer.reduce(before, now))
    }

    @Test
    fun `well under the configured rate is low quality`() {
        val before = reading(decoded = 0, atMillis = 0)
        val now = reading(decoded = 10, atMillis = 5_000) // 2 fps against a configured 15

        assertEquals(VideoTileState.LOW_QUALITY, VideoTileStateReducer.reduce(before, now))
    }

    @Test
    fun `a call configured low is not reported as degraded for meeting its own rate`() {
        // 5 fps requested and 5 fps delivered is a healthy call, not a failing one.
        val before = reading(decoded = 0, atMillis = 0, configuredFps = 5)
        val now = reading(decoded = 25, atMillis = 5_000, configuredFps = 5)

        assertEquals(VideoTileState.LIVE, VideoTileStateReducer.reduce(before, now))
    }

    @Test
    fun `a rebuilt stream whose counter restarted is not reported as frozen`() {
        // pjmedia starts a fresh counter at zero; differencing across that boundary is
        // negative, which measures nothing.
        val before = reading(decoded = 900, atMillis = 0)
        val now = reading(decoded = 4, atMillis = 5_000)

        assertEquals(VideoTileState.LIVE, VideoTileStateReducer.reduce(before, now))
    }

    @Test
    fun `two readings sharing a timestamp change nothing`() {
        val before = reading(decoded = 10, atMillis = 5_000)
        val now = reading(decoded = 10, atMillis = 5_000)

        assertEquals(VideoTileState.LIVE, VideoTileStateReducer.reduce(before, now))
    }

    @Test
    fun `an unreadable configured rate still distinguishes moving from frozen`() {
        val before = reading(decoded = 0, atMillis = 0, configuredFps = null)
        val moving = reading(decoded = 10, atMillis = 5_000, configuredFps = null)
        val stopped = reading(decoded = 0, atMillis = 5_000, configuredFps = null)

        assertEquals(VideoTileState.LIVE, VideoTileStateReducer.reduce(before, moving))
        assertEquals(VideoTileState.UNAVAILABLE, VideoTileStateReducer.reduce(before, stopped))
    }

    @Test
    fun `showsPicture is true only where there is one`() {
        assertEquals(true, VideoTileState.LIVE.showsPicture)
        assertEquals(true, VideoTileState.LOW_QUALITY.showsPicture)
        assertEquals(false, VideoTileState.CONNECTING.showsPicture)
        assertEquals(false, VideoTileState.UNAVAILABLE.showsPicture)
    }

    @Test
    fun `an unmeasured call reads as connecting`() {
        assertEquals(VideoTileState.CONNECTING, VideoHealth.UNKNOWN.stateFor("anything"))
    }
}
