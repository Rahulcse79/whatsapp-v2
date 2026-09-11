package com.whatsappv2.data.sip.registration.stack

import com.whatsappv2.core.common.logging.NoOpLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bridge against fake ports (ADR-009).
 *
 * `ConferenceMixTest` proves the arithmetic; this proves the bookkeeping around it — in
 * particular the one piece the arithmetic cannot see, which is that pjmedia's ports are
 * not the call keys and do not live as long as the call.
 */
class ConferenceBridgeTest {

    /** A port that records the links it holds, the way `pjmedia_conf` does. */
    private class FakePort(override val id: Int) : ConferencePort {
        val transmittingTo = mutableSetOf<Int>()

        override fun transmitTo(other: ConferencePort) {
            transmittingTo += other.id
        }

        override fun stopTransmitTo(other: ConferencePort) {
            transmittingTo -= other.id
        }
    }

    private val ports = mutableMapOf<String, FakePort>()
    private val bridge = ConferenceBridge(NoOpLogger) { key -> ports[key] }

    private fun givenPort(key: String, id: Int): FakePort = FakePort(id).also { ports[key] = it }

    /** Every directed link the fake ports hold right now, as (from id, to id). */
    private fun openLinks(): Set<Pair<Int, Int>> =
        ports.values.flatMap { from -> from.transmittingTo.map { from.id to it } }.toSet()

    @Test
    fun `a conference of three is six links, every member to every other`() {
        givenPort("a", 1)
        givenPort("b", 2)
        givenPort("c", 3)

        val live = bridge.set(setOf("a", "b", "c"))

        assertEquals(setOf("a", "b", "c"), live)
        assertEquals(setOf(1 to 2, 1 to 3, 2 to 1, 2 to 3, 3 to 1, 3 to 2), openLinks())
    }

    @Test
    fun `a member with no port yet is linked when it gets one`() {
        givenPort("a", 1)
        givenPort("b", 2)
        bridge.set(setOf("a", "b", "c"))
        assertEquals(2, openLinks().size)

        givenPort("c", 3)
        bridge.remix()

        assertEquals(6, openLinks().size)
    }

    @Test
    fun `a member that comes back on a new port is linked again`() {
        // The RX-0pkt defect. pjsua rebuilt "b"'s port on its resume re-INVITE — port 2
        // and everything it held are gone, "b" is now port 5. The old bridge believed the
        // links to "b" were still open and never opened them again, so "b" heard
        // everyone and was heard by nobody.
        givenPort("a", 1)
        givenPort("b", 2)
        givenPort("c", 3)
        bridge.set(setOf("a", "b", "c"))

        val rebuilt = givenPort("b", 5)
        // The old port's links died with it; the survivors' links to it are dead too.
        ports.getValue("a").transmittingTo -= 2
        ports.getValue("c").transmittingTo -= 2
        bridge.remix()

        assertEquals(setOf(1, 3), rebuilt.transmittingTo, "the rebuilt member transmits to everyone")
        assertTrue(5 in ports.getValue("a").transmittingTo, "a transmits to the new port")
        assertTrue(5 in ports.getValue("c").transmittingTo, "c transmits to the new port")
        assertEquals(6, openLinks().size)
    }

    @Test
    fun `two members reporting the same slot are not linked to each other`() {
        // A cached port whose slot pjsua has reused for another call: linking "a" to "b"
        // would be slot 1 to slot 1, a port transmitting to itself, a participant hearing
        // their own voice. Left for the remix that follows the real port.
        givenPort("a", 1)
        givenPort("b", 1)
        givenPort("c", 3)

        bridge.set(setOf("a", "b", "c"))

        assertTrue(openLinks().none { (from, to) -> from == to }, "no port transmits to itself: ${openLinks()}")
        assertEquals(setOf(1 to 3, 3 to 1), openLinks())
    }

    @Test
    fun `remix is idempotent once everyone is linked`() {
        givenPort("a", 1)
        givenPort("b", 2)
        bridge.set(setOf("a", "b"))
        val before = openLinks()

        bridge.remix()
        bridge.remix()

        assertEquals(before, openLinks())
    }

    @Test
    fun `a member leaving releases its links, and two left is still a conference`() {
        givenPort("a", 1)
        givenPort("b", 2)
        givenPort("c", 3)
        bridge.set(setOf("a", "b", "c"))

        bridge.remove("c")

        assertEquals(setOf(1 to 2, 2 to 1), openLinks())
        assertTrue(bridge.isActive)
    }

    @Test
    fun `one member left is no conference at all`() {
        givenPort("a", 1)
        givenPort("b", 2)
        bridge.set(setOf("a", "b"))

        bridge.remove("b")

        assertEquals(emptySet(), openLinks())
        assertTrue(!bridge.isActive)
    }

    @Test
    fun `a refused link is not counted as open, so the next remix tries again`() {
        givenPort("a", 1)
        val flaky = object : ConferencePort {
            override val id = 2
            var refuse = true
            val transmittingTo = mutableSetOf<Int>()
            override fun transmitTo(other: ConferencePort) {
                if (refuse) error("PJ_EINVAL") else transmittingTo += other.id
            }
            override fun stopTransmitTo(other: ConferencePort) {
                transmittingTo -= other.id
            }
        }
        val portsWithFlaky: (String) -> ConferencePort? = { key -> if (key == "b") flaky else ports[key] }
        val bridge = ConferenceBridge(NoOpLogger, portsWithFlaky)

        bridge.set(setOf("a", "b"))
        assertEquals(emptySet(), flaky.transmittingTo)

        flaky.refuse = false
        bridge.remix()

        assertEquals(setOf(1), flaky.transmittingTo)
    }
}
