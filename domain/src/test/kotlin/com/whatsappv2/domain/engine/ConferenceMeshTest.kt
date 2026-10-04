package com.whatsappv2.domain.engine

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.SipUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ConferenceMesh], which decides who calls whom.
 *
 * Every case here is one this app would otherwise only discover on four handsets at once:
 * two phones dialling each other simultaneously, a device dialling itself, a leg left open
 * to somebody the host removed. They are arithmetic, so they are settled here (§1.3).
 */
class ConferenceMeshTest {

    private fun uri(user: String, host: String = "192.168.20.56") =
        requireNotNull(SipUri.parse("sip:$user@$host").getOrNull())

    private val self = uri("4030")
    private val peerA = uri("4031")
    private val peerB = uri("4032")
    private val peerC = uri("4033")

    @Test
    fun `dials only the peers whose address sorts above this device`() {
        val plan = ConferenceMesh.plan(setOf(self, peerA, peerB, peerC), self, legs = emptyMap())

        // 4030 is below all three, so this end of every pair is the one that calls.
        assertEquals(setOf(peerA, peerB, peerC), plan.dial)
        assertTrue(plan.awaiting.isEmpty())
    }

    @Test
    fun `waits to be called by the peers whose address sorts below it`() {
        val plan = ConferenceMesh.plan(setOf(self, peerA, peerB, peerC), self = peerC, legs = emptyMap())

        // Every other participant sorts below 4033, so 4033 dials nobody and is owed three
        // calls. This is the half of the rule that prevents glare: if both ends dialled,
        // each pair would have two dialogs and every peer would be heard twice.
        assertTrue(plan.dial.isEmpty())
        assertEquals(setOf(self, peerA, peerB), plan.awaiting)
    }

    @Test
    fun `exactly one end of every pair dials`() {
        val everyone = listOf(self, peerA, peerB, peerC)

        everyone.forEach { one ->
            everyone.filter { it != one }.forEach { other ->
                val oneDials = ConferenceMesh.dials(ConferenceMesh.key(one), ConferenceMesh.key(other))
                val otherDials = ConferenceMesh.dials(ConferenceMesh.key(other), ConferenceMesh.key(one))
                assertTrue(oneDials != otherDials, "both or neither of $one and $other would dial")
            }
        }
    }

    @Test
    fun `every participant planning independently produces one dialog per pair`() {
        // Phase 4's topology requirement, as arithmetic rather than as four handsets: for N
        // participants the mesh must hold exactly N*(N-1)/2 dialogs. Each device runs `plan`
        // against the same roster knowing nothing about what the others decided, so the count
        // is only right if the dialling rule is a total order they all compute identically.
        //
        // This is the invariant that a duplicate-dialog bug breaks in the direction the UI
        // cannot show: two dialogs for one pair both carry media, both tiles move, and the
        // only symptom is doubled audio and twice the encode cost.
        listOf(3, 4).forEach { size ->
            val everyone = listOf(self, peerA, peerB, peerC).take(size).toSet()

            val pairsDialled = everyone.flatMap { device ->
                ConferenceMesh.plan(everyone, device, legs = emptyMap()).dial
                    .map { peer -> setOf(ConferenceMesh.key(device), ConferenceMesh.key(peer)) }
            }

            val expected = size * (size - 1) / 2
            assertEquals(
                expected, pairsDialled.size,
                "$size participants must plan exactly $expected dialogs, got ${pairsDialled.size}",
            )
            assertEquals(
                expected, pairsDialled.distinct().size,
                "$size participants planned the same pair twice: $pairsDialled",
            )
            // And every pair is covered -- a missing dialog is a participant in the roster
            // with no media relationship, which Phase 4 forbids explicitly.
            val allPairs = everyone.flatMap { a ->
                everyone.filter { it != a }.map { b -> setOf(ConferenceMesh.key(a), ConferenceMesh.key(b)) }
            }.distinct()
            assertEquals(
                allPairs.toSet(), pairsDialled.toSet(),
                "some pair of the $size has no dialog planned",
            )
        }
    }

    @Test
    fun `nobody dials a peer that is already reachable through an open leg`() {
        // The same invariant one step later: once a pair has its dialog, re-planning must not
        // add a second one. Phase 4 exercises this by reconciling repeatedly while a
        // conference settles, and a planner that ignored open legs would add a dialog per
        // reconciliation rather than per pair.
        val everyone = setOf(self, peerA, peerB, peerC)
        val firstRound = ConferenceMesh.plan(everyone, self, legs = emptyMap()).dial
        val open = firstRound.mapIndexed { i, peer -> CallId("leg-$i") to peer }.toMap()

        val second = ConferenceMesh.plan(everyone, self, legs = open)

        assertTrue(
            second.dial.isEmpty(),
            "a settled device must plan no further dialogs, wanted ${second.dial}",
        )
    }

    @Test
    fun `never dials itself, however the roster spells this device`() {
        // The focus writes its own address into the roster, and the wire spells it with
        // whatever parameters the transport added. A plan that matched on the whole URI
        // would have every device place a call to itself.
        val spelled = requireNotNull(SipUri.parse("sip:4030@192.168.20.56;transport=udp").getOrNull())

        val plan = ConferenceMesh.plan(setOf(spelled, peerA), self, legs = emptyMap())

        assertEquals(setOf(peerA), plan.dial)
    }

    @Test
    fun `a leg already held is not dialled again, whatever parameters it carries`() {
        // The same defect from the other side: the leg's remote comes off the wire with
        // `;transport=udp` and the roster names it without. Matching on the full URI opened
        // a second dialog to a peer this device was already talking to — the duplicate
        // audio the glare rule exists to prevent, arriving by the other door.
        val held = requireNotNull(SipUri.parse("sip:4031@192.168.20.56;transport=udp").getOrNull())

        val plan = ConferenceMesh.plan(
            members = setOf(self, peerA, peerB),
            self = self,
            legs = mapOf(CallId("a") to held),
        )

        assertEquals(setOf(peerB), plan.dial)
        assertTrue(plan.drop.isEmpty())
    }

    @Test
    fun `a leg to somebody the focus no longer lists is dropped`() {
        // How a removal reaches the members who were not the one removed: the focus
        // restates the membership without them, and every device closes its own leg.
        val plan = ConferenceMesh.plan(
            members = setOf(self, peerA),
            self = self,
            legs = mapOf(CallId("a") to peerA, CallId("gone") to peerB),
        )

        assertEquals(setOf(CallId("gone")), plan.drop)
        assertTrue(plan.dial.isEmpty())
    }

    @Test
    fun `a dial still ringing is not dialled a second time`() {
        // A leg counts from the moment the INVITE goes out, not from when it is answered.
        // Reconciliation runs on every call event, and events arrive in bursts.
        val plan = ConferenceMesh.plan(
            members = setOf(self, peerA),
            self = self,
            legs = mapOf(CallId("ringing") to peerA),
        )

        assertTrue(plan.isSettled)
    }

    @Test
    fun `a settled mesh asks for nothing`() {
        val plan = ConferenceMesh.plan(
            members = setOf(self, peerA, peerB),
            self = self,
            legs = mapOf(CallId("a") to peerA, CallId("b") to peerB),
        )

        assertTrue(plan.isSettled)
        assertTrue(plan.awaiting.isEmpty())
    }

    @Test
    fun `addresses are matched case-insensitively`() {
        assertEquals(ConferenceMesh.key(uri("Alice", "Example.COM")), ConferenceMesh.key(uri("alice", "example.com")))
        assertFalse(ConferenceMesh.key(peerA) == ConferenceMesh.key(peerB))
    }

    // ------------------------------------------------- participants that cannot mesh

    @Test
    fun `a relayed participant is never dialled`() {
        // The whole of "no duplicate audio paths". The focus is already carrying peerB on
        // its own bridge; a direct leg from here as well would be peerB heard twice.
        val plan = ConferenceMesh.plan(
            members = setOf(self, peerA, peerB),
            self = self,
            legs = emptyMap(),
            relayed = setOf(peerB),
        )

        assertEquals(setOf(peerA), plan.dial, "a carried participant was dialled")
    }

    @Test
    fun `a relayed participant is never waited for either`() {
        // From the other end of the sort order, where this device would otherwise sit
        // showing "connecting" for a call that is never coming.
        val plan = ConferenceMesh.plan(
            members = setOf(self, peerA, peerB),
            self = peerC,
            legs = emptyMap(),
            relayed = setOf(peerB),
        )

        assertTrue(peerB !in plan.awaiting, "waiting for a call from a carried participant")
        assertTrue(peerB !in plan.dial)
        assertEquals(setOf(self, peerA), plan.awaiting + plan.dial)
    }

    @Test
    fun `the focus does not hang up on the participants it is carrying`() {
        // The trap in implementing this by filtering the membership instead: the focus
        // holds a leg to every relayed participant - that leg IS the relay - and a plan
        // that treats them as "not in the conference" closes it.
        val legToB = CallId("leg-b")
        val plan = ConferenceMesh.plan(
            members = setOf(self, peerA, peerB),
            self = self,
            legs = mapOf(legToB to peerB),
            relayed = setOf(peerB),
        )

        assertTrue(plan.drop.isEmpty(), "the relay's own leg was dropped: ${plan.drop}")
    }

    @Test
    fun `a leg to somebody removed is still dropped, carried or not`() {
        // And the converse, so the exemption above cannot be used to keep a stale leg:
        // being relayed is not membership. peerC is in neither set.
        val stale = CallId("leg-c")
        val plan = ConferenceMesh.plan(
            members = setOf(self, peerA, peerB),
            self = self,
            legs = mapOf(stale to peerC),
            relayed = setOf(peerB),
        )

        assertEquals(setOf(stale), plan.drop)
    }

    @Test
    fun `an all-CoralX conference is unchanged by the relayed parameter`() {
        // The isolation requirement: normal CoralX-to-CoralX conferencing must behave
        // exactly as it did, and the default is what proves it.
        val members = setOf(self, peerA, peerB, peerC)
        val legs = mapOf(CallId("x") to peerA)

        assertEquals(
            ConferenceMesh.plan(members, self, legs),
            ConferenceMesh.plan(members, self, legs, relayed = emptySet()),
        )
    }
}
