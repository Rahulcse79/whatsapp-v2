package com.whatsappv2.data.sip.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Everyone hears everyone, exactly once, for 2 to 5 participants and every mix of
 * topologies in between.
 *
 * ## Why a matrix and not a list of cases
 *
 * "A hears B, C and D" is the requirement, and it is the one that was broken: the host
 * heard every member and the members heard only the host. Asserting it directly means
 * modelling the whole audio graph rather than the piece [ConferenceMix] owns, because
 * three different mechanisms carry audio in this conference and the requirement is a
 * statement about their union:
 *
 *  1. **pjsua's per-call wiring.** Every call's media is joined to the microphone and the
 *     speaker as it comes up (`RealPjsipCoreGateway`), so the host hears every member and
 *     every member hears the host, with no conference logic involved at all.
 *  2. **A direct mesh leg.** Two participants that can both mesh hold a dialog of their
 *     own and hear each other across it.
 *  3. **A bridge cross-link.** [ConferenceMix] opens one for a pair that has no dialog,
 *     and `pjmedia_conf` transcodes across it in both directions.
 *
 * [audioGraph] builds all three and the assertions below read the result. The second
 * assertion is the one that earns the model: **no pair may be carried by more than one
 * mechanism**, because a participant heard directly *and* relayed is heard twice, and a
 * test that only checked "can X hear Y" would pass just as happily on a conference that
 * howls.
 */
class ConferenceHearMatrixTest {

    /** The host. Not a member of the mix set: it is the device the bridge belongs to. */
    private val host = "host"

    /**
     * Who hears whom, and by which mechanism, for a conference of [meshed] + [relayed].
     *
     * Returns a map from listener to the multiset of speakers they receive — a *list*
     * rather than a set on purpose, so that hearing somebody twice is visible instead of
     * being collapsed by the data structure.
     */
    private fun audioGraph(meshed: Set<String>, relayed: Set<String>): Map<String, List<String>> {
        val members = meshed + relayed
        val links = ConferenceMix.wanted(members, relayed = relayed)
        val heard = (members + host).associateWith { mutableListOf<String>() }

        // 1. pjsua's per-call wiring: the host and each member, both ways, always.
        members.forEach { member ->
            heard.getValue(host) += member
            heard.getValue(member) += host
        }

        // 2. A direct mesh leg between every pair that can mesh.
        meshed.forEach { a -> meshed.forEach { b -> if (a != b) heard.getValue(a) += b } }

        // 3. The bridge's cross-links. MixLink(from, to) means `from`'s audio reaches `to`.
        links.forEach { heard.getValue(it.to) += it.from }

        return heard.mapValues { (_, v) -> v.toList() }
    }

    private fun assertEveryoneHearsEveryoneExactlyOnce(meshed: Set<String>, relayed: Set<String>) {
        val everyone = meshed + relayed + host
        val graph = audioGraph(meshed, relayed)

        everyone.forEach { listener ->
            val expected = everyone - listener
            val actual = graph.getValue(listener)

            assertEquals(
                expected,
                actual.toSet(),
                "$listener (meshed=$meshed relayed=$relayed) should hear $expected but hears ${actual.toSet()}",
            )
            // The duplicate check, and the reason the graph carries a list. A pair that is
            // both meshed and relayed would appear twice here.
            assertEquals(
                actual.size,
                actual.toSet().size,
                "$listener hears somebody twice: $actual (meshed=$meshed relayed=$relayed)",
            )
        }
    }

    // ---------------------------------------------------------------- the pure mesh

    @Test
    fun `an all-CoralX conference of 2 to 5 is a mesh with no relaying at all`() {
        // The case the change must not touch. Every participant can mesh, so the bridge
        // opens nothing and this device carries nobody — CoralX-to-CoralX conferencing is
        // byte-for-byte what it was.
        (1..4).forEach { remotes ->
            val meshed = (1..remotes).map { "m$it" }.toSet()
            assertTrue(
                ConferenceMix.wanted(meshed, relayed = emptySet()).isEmpty(),
                "a pure mesh of ${remotes + 1} opened a cross-link",
            )
            assertEveryoneHearsEveryoneExactlyOnce(meshed = meshed, relayed = emptySet())
        }
    }

    // ------------------------------------------------------------- the mixed cases

    @Test
    fun `two participants, one of them a desk phone`() {
        // host + one relayed member. There is no second remote leg, so there is nothing
        // to relay to and the bridge stays empty - the member hears the host and the host
        // hears the member, on the call they are already on.
        assertEveryoneHearsEveryoneExactlyOnce(meshed = emptySet(), relayed = setOf("d1"))
        assertTrue(ConferenceMix.wanted(setOf("d1"), relayed = setOf("d1")).isEmpty())
    }

    @Test
    fun `three participants with one desk phone - the reported failure`() {
        // host A, CoralX B, desk phone C. Before this change `relay` was false for the
        // whole conference, so B and C were never linked: A heard both and B and C heard
        // only A. This is that conference, asserted.
        assertEveryoneHearsEveryoneExactlyOnce(meshed = setOf("b"), relayed = setOf("c"))

        assertEquals(
            setOf(MixLink("b", "c"), MixLink("c", "b")),
            ConferenceMix.wanted(setOf("b", "c"), relayed = setOf("c")),
            "the only pair without a dialog of its own is b<->c, and it must be carried",
        )
    }

    @Test
    fun `four participants, one desk phone - meshed pairs are left alone`() {
        val links = ConferenceMix.wanted(setOf("b", "c", "d"), relayed = setOf("d"))

        assertTrue(
            MixLink("b", "c") !in links && MixLink("c", "b") !in links,
            "b and c can mesh and must not also be relayed: $links",
        )
        assertEquals(
            setOf(
                MixLink("b", "d"),
                MixLink("d", "b"),
                MixLink("c", "d"),
                MixLink("d", "c"),
            ),
            links,
        )
        assertEveryoneHearsEveryoneExactlyOnce(meshed = setOf("b", "c"), relayed = setOf("d"))
    }

    @Test
    fun `five participants, two desk phones - including the pair of desk phones`() {
        // d1 and d2 can neither of them mesh, so the pair between THEM also has to be
        // carried. Forgetting that is the obvious way to half-fix this: two desk phones
        // in one conference would hear everybody except each other.
        val links = ConferenceMix.wanted(setOf("b", "c", "d1", "d2"), relayed = setOf("d1", "d2"))

        assertTrue(MixLink("d1", "d2") in links && MixLink("d2", "d1") in links)
        assertTrue(MixLink("b", "c") !in links && MixLink("c", "b") !in links)
        assertEveryoneHearsEveryoneExactlyOnce(meshed = setOf("b", "c"), relayed = setOf("d1", "d2"))
    }

    @Test
    fun `every topology of 2 to 5 participants is complete and duplicate-free`() {
        // The exhaustive version of the four cases above: every way of splitting up to
        // four remote legs into meshed and relayed. 2^4 subsets x 4 sizes, and all of them
        // have to satisfy the same one-line requirement.
        (1..4).forEach { remotes ->
            val legs = (1..remotes).map { "p$it" }
            (0 until (1 shl remotes)).forEach { mask ->
                val relayed = legs.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }.toSet()
                assertEveryoneHearsEveryoneExactlyOnce(
                    meshed = legs.toSet() - relayed,
                    relayed = relayed,
                )
            }
        }
    }

    // ------------------------------------------------------------------ transitions

    @Test
    fun `a member discovered to be unmeshable gains links without disturbing the rest`() {
        // Classification is learned, not known at merge time: a conference starts as a
        // mesh and a participant that never joins it is moved across. That must open the
        // new links and touch nothing else, because re-opening a live link is a glitch
        // every other participant hears.
        val members = setOf("b", "c", "d")
        val asMesh = ConferenceMix.wanted(members, relayed = emptySet())

        val plan = ConferenceMix.plan(asMesh, members, relayed = setOf("d"))

        assertTrue(plan.disconnect.isEmpty(), "a live link was torn down: ${plan.disconnect}")
        assertEquals(
            setOf(
                MixLink("b", "d"),
                MixLink("d", "b"),
                MixLink("c", "d"),
                MixLink("d", "c"),
            ),
            plan.connect,
        )
    }

    @Test
    fun `a member that joins the mesh late stops being carried`() {
        // The other direction, and the one that prevents a duplicate: the moment a direct
        // leg exists, the relayed path for that pair must close. Shrinking the relayed set
        // produces exactly those disconnects and no reconnects.
        val members = setOf("b", "c", "d")
        val carried = ConferenceMix.wanted(members, relayed = setOf("d"))

        val plan = ConferenceMix.plan(carried, members, relayed = emptySet())

        assertEquals(carried, plan.disconnect)
        assertTrue(plan.connect.isEmpty())
    }

    @Test
    fun `a relayed member who hangs up takes only their own links`() {
        val members = setOf("b", "c", "d")
        val carried = ConferenceMix.wanted(members, relayed = setOf("d"))

        val remaining = ConferenceMix.without(members, ended = "d")
        val plan = ConferenceMix.plan(carried, remaining, relayed = emptySet())

        assertEquals(carried, plan.disconnect)
        assertTrue(plan.connect.isEmpty())
        assertTrue(remaining == setOf("b", "c"))
    }

    @Test
    fun `relayed names that are not members are ignored rather than inventing links`() {
        // A classification that outlives the leg it describes. The set is intersected with
        // the membership so a stale name cannot produce a link to a port that is not here.
        val links = ConferenceMix.wanted(setOf("b", "c"), relayed = setOf("c", "ghost"))

        assertTrue(links.all { it.from in setOf("b", "c") && it.to in setOf("b", "c") })
        assertEquals(setOf(MixLink("b", "c"), MixLink("c", "b")), links)
    }

    @Test
    fun `the two extremes still mean what the boolean meant`() {
        // The boolean overload is expressed in terms of the set, so this is the proof that
        // the set generalises the old topologies rather than replacing them.
        val members = setOf("a", "b", "c")

        assertEquals(
            ConferenceMix.wanted(members, relay = true),
            ConferenceMix.wanted(members, relayed = members),
        )
        assertEquals(
            ConferenceMix.wanted(members, relay = false),
            ConferenceMix.wanted(members, relayed = emptySet()),
        )
        assertEquals(6, ConferenceMix.wanted(members, relay = true).size)
    }
}
