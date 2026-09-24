package com.whatsappv2.data.sip.registration.stack

import com.whatsappv2.core.common.logging.NoOpLogger
import com.whatsappv2.data.sip.call.VideoPortRef
import com.whatsappv2.data.sip.call.VideoRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The video bridge's *state*, which is the half [com.whatsappv2.data.sip.call.VideoMixTest]
 * cannot reach: ports that appear late, ports that come back on a new slot, and the
 * ordering that keeps a queued connect from landing on a freed port.
 *
 * Every port here is a fake recording what was asked of it. `VideoMedia` loads the native
 * library the moment the class is touched, which no unit test may do.
 */
class VideoConferenceBridgeTest {

    private class FakePort(override val id: Int) : VideoPort {
        val sentTo = mutableSetOf<Int>()
        var refuse = false

        override fun transmitTo(other: VideoPort) {
            if (refuse) error("port $id refuses")
            sentTo += (other as FakePort).id
        }

        override fun stopTransmitTo(other: VideoPort) {
            sentTo -= (other as FakePort).id
        }
    }

    /** The ports that exist, by reference. Absent means "not up yet", which is the ordinary case. */
    private val ports = mutableMapOf<VideoPortRef, FakePort>()
    private var nextId = 1

    private fun givenPort(ref: VideoPortRef): FakePort =
        FakePort(nextId++).also { ports[ref] = it }

    private fun givenMember(key: String) {
        givenPort(VideoPortRef.decoder(key))
        givenPort(VideoPortRef.encoder(key))
    }

    private fun bridge() = VideoConferenceBridge(NoOpLogger) { ports[it] }

    /** Which port ids are feeding [ref] right now, read back off the fakes. */
    private fun feeding(ref: VideoPortRef): Set<Int> {
        val sink = ports[ref]?.id ?: return emptySet()
        return ports.values.filterTo(mutableSetOf()) { sink in it.sentTo }.mapTo(mutableSetOf()) { it.id }
    }

    private val a = "call-a"
    private val b = "call-b"

    @Test
    fun `it opens the whole arrangement once every port exists`() {
        val camera = givenPort(VideoPortRef.camera)
        givenMember(a)
        givenMember(b)
        val bridge = bridge()

        assertEquals(setOf(a, b), bridge.set(setOf(a, b)))

        // Each peer gets our camera and the other peer, and never itself.
        assertEquals(
            setOf(camera.id, ports[VideoPortRef.decoder(b)]!!.id),
            feeding(VideoPortRef.encoder(a)),
        )
        assertEquals(
            setOf(camera.id, ports[VideoPortRef.decoder(a)]!!.id),
            feeding(VideoPortRef.encoder(b)),
        )
        // And nothing is composed for our own screen: the call screen draws a tile per
        // participant from each call's own window.
        assertTrue(bridge.isActive)
    }

    @Test
    fun `a member whose video is not up yet joins by itself when it is`() {
        givenPort(VideoPortRef.camera)
        givenMember(a)
        val bridge = bridge()

        // b is still ringing: it is wanted, and it has no ports.
        assertEquals(setOf(a), bridge.set(setOf(a, b)))
        assertTrue(feeding(VideoPortRef.encoder(a)).isEmpty(), "nothing to mix with yet")

        givenMember(b)
        assertEquals(setOf(a, b), bridge.remix())
        assertEquals(
            setOf(ports[VideoPortRef.camera]!!.id, ports[VideoPortRef.decoder(b)]!!.id),
            feeding(VideoPortRef.encoder(a)),
        )
    }

    @Test
    fun `a port that comes back on a new slot is linked again`() {
        // pjsua rebuilds a call's video ports on every re-INVITE that touches its media.
        // Believing the old links are still open leaves that member in the roster and in
        // nobody's picture — the video form of ADR-009's "RX 0pkt while TX shows".
        givenPort(VideoPortRef.camera)
        givenMember(a)
        givenMember(b)
        val bridge = bridge()
        bridge.set(setOf(a, b))

        // b is held and resumed: a brand new decoder slot, with none of the old links.
        val rebuilt = givenPort(VideoPortRef.decoder(b))
        bridge.remix()

        assertTrue(
            rebuilt.id in feeding(VideoPortRef.encoder(a)),
            "the new slot was never linked: ${feeding(VideoPortRef.encoder(a))}",
        )
    }

    @Test
    fun `a refused link is deferred, not fatal, and the next remix opens it`() {
        givenPort(VideoPortRef.camera)
        givenMember(a)
        givenMember(b)
        ports[VideoPortRef.decoder(b)]!!.refuse = true
        val bridge = bridge()

        bridge.set(setOf(a, b))
        // The other links are open even though one port refused everything.
        assertTrue(ports[VideoPortRef.camera]!!.sentTo.isNotEmpty(), "one refusal collapsed the conference")

        ports[VideoPortRef.decoder(b)]!!.refuse = false
        bridge.remix()
        assertTrue(ports[VideoPortRef.decoder(b)]!!.sentTo.isNotEmpty(), "the refused link was never retried")
    }

    @Test
    fun `a member leaving closes its links and leaves the others alone`() {
        val c = "call-c"
        givenPort(VideoPortRef.camera)
        listOf(a, b, c).forEach(::givenMember)
        val bridge = bridge()
        bridge.set(setOf(a, b, c))

        val goneDecoder = ports[VideoPortRef.decoder(c)]!!
        bridge.remove(c)

        assertTrue(goneDecoder.sentTo.isEmpty(), "the departing member is still transmitting: ${goneDecoder.sentTo}")
        assertFalse(goneDecoder.id in feeding(VideoPortRef.encoder(a)))
        // The two who stayed are untouched.
        assertEquals(
            setOf(ports[VideoPortRef.camera]!!.id, ports[VideoPortRef.decoder(b)]!!.id),
            feeding(VideoPortRef.encoder(a)),
        )
    }

    @Test
    fun `dropping to one member tears everything down`() {
        givenPort(VideoPortRef.camera)
        givenMember(a)
        givenMember(b)
        val bridge = bridge()
        bridge.set(setOf(a, b))

        bridge.remove(b)

        assertFalse(bridge.isActive)
        ports.values.forEach { assertTrue(it.sentTo.isEmpty(), "port ${it.id} still transmits to ${it.sentTo}") }
    }

    @Test
    fun `an empty membership is a teardown, not a no-op`() {
        // ADR-009's audio bridge had exactly this bug: `set(emptySet())` dropped the
        // membership and left every link open, so people went on seeing each other after
        // the conference had ended.
        givenPort(VideoPortRef.camera)
        givenMember(a)
        givenMember(b)
        val bridge = bridge()
        bridge.set(setOf(a, b))

        bridge.set(emptySet())

        assertFalse(bridge.isActive)
        ports.values.forEach { assertTrue(it.sentTo.isEmpty(), "port ${it.id} still transmits to ${it.sentTo}") }
    }

    @Test
    fun `a port that is its own sink is never linked to itself`() {
        // pjsua reusing a slot would otherwise have the bridge loop a port to itself,
        // which is a participant's own picture composed into their own canvas.
        val shared = FakePort(99)
        ports[VideoPortRef.camera] = shared
        ports[VideoPortRef.encoder(a)] = shared
        ports[VideoPortRef.decoder(a)] = givenPort(VideoPortRef.decoder(a))
        givenMember(b)
        val bridge = bridge()

        bridge.set(setOf(a, b))

        assertFalse(shared.id in shared.sentTo, "the port was looped to itself")
    }

    @Test
    fun `setting the same membership twice opens nothing the second time`() {
        givenPort(VideoPortRef.camera)
        givenMember(a)
        givenMember(b)
        val bridge = bridge()
        bridge.set(setOf(a, b))
        val opened = ports.values.sumOf { it.sentTo.size }

        bridge.remix()
        bridge.set(setOf(a, b))

        assertEquals(opened, ports.values.sumOf { it.sentTo.size }, "remix is not idempotent")
    }


}
