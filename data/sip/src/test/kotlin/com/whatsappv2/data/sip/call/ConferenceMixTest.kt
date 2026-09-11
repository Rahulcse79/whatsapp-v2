package com.whatsappv2.data.sip.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole of ADR-009's conferencing decision, with no device in the room.
 *
 * These are the failures that matter and none of them needs a handset: a member wired to
 * itself, a link left behind by somebody who hung up, a pair opened twice because two
 * call-state events landed together. A device test would find them late, once, and with a
 * native crash instead of an assertion.
 */
class ConferenceMixTest {

    @Test
    fun `a pair is two links, one each way`() {
        assertEquals(
            setOf(MixLink("a", "b"), MixLink("b", "a")),
            ConferenceMix.wanted(setOf("a", "b")),
        )
    }

    @Test
    fun `nobody is ever wired to themselves`() {
        // The mix-minus promise: a participant hearing their own voice back is the
        // commonest conferencing defect, and it cannot be expressed by this plan.
        val links = ConferenceMix.wanted(setOf("a", "b", "c", "d"))

        assertTrue(links.none { it.from == it.to }, "a member was wired to itself: $links")
    }

    @Test
    fun `eight participants is fifty-six directed links`() {
        // n * (n - 1). ADR-009's declared ceiling, so the arithmetic is asserted rather
        // than assumed — this is the number the bridge has to carry at ~350% of a core.
        val members = (1..ConferenceMix.MAX_MEMBERS).map { "call-$it" }.toSet()

        assertEquals(56, ConferenceMix.wanted(members).size)
    }

    @Test
    fun `one member is not a conference, and wants no links`() {
        assertTrue(ConferenceMix.wanted(setOf("a")).isEmpty())
        assertTrue(ConferenceMix.wanted(emptySet()).isEmpty())
    }

    @Test
    fun `planning twice changes nothing the second time`() {
        // Idempotence is what lets the gateway run this on every call-state change
        // without counting connections or remembering whether a link was made.
        val members = setOf("a", "b", "c")
        val first = ConferenceMix.plan(established = emptySet(), members = members)

        val second = ConferenceMix.plan(established = first.connect, members = members)

        assertEquals(6, first.connect.size)
        assertTrue(second.isEmpty, "a second plan asked for $second")
    }

    @Test
    fun `adding a participant touches only the new links`() {
        val two = ConferenceMix.wanted(setOf("a", "b"))

        val plan = ConferenceMix.plan(established = two, members = setOf("a", "b", "c"))

        // a<->b is already open and must not be disturbed: re-opening a live link is a
        // glitch the other two participants would hear.
        assertTrue(plan.disconnect.isEmpty(), "an existing link was torn down: ${plan.disconnect}")
        assertEquals(
            setOf(
                MixLink("a", "c"),
                MixLink("c", "a"),
                MixLink("b", "c"),
                MixLink("c", "b"),
            ),
            plan.connect,
        )
    }

    @Test
    fun `a participant who hangs up takes every one of their links with them`() {
        // The case that is a use-after-free in a native bridge rather than a stale map
        // entry: a link into a media port that has been released.
        val three = ConferenceMix.wanted(setOf("a", "b", "c"))

        val remaining = ConferenceMix.without(setOf("a", "b", "c"), ended = "c")
        val plan = ConferenceMix.plan(established = three, members = remaining)

        assertTrue(
            plan.disconnect.all { it.from == "c" || it.to == "c" },
            "links unrelated to the departed member were torn down: ${plan.disconnect}",
        )
        assertEquals(4, plan.disconnect.size)
        assertTrue(plan.connect.isEmpty())
    }

    @Test
    fun `the last two leaving tears the whole bridge down`() {
        val three = ConferenceMix.wanted(setOf("a", "b", "c"))

        val plan = ConferenceMix.plan(established = three, members = setOf("a"))

        // Falling under two members is not a special case; it is a plan whose target is
        // empty, which is what stops a lone survivor holding links to nothing.
        assertEquals(three, plan.disconnect)
        assertTrue(plan.connect.isEmpty())
    }

    @Test
    fun `a half-open pair is repaired rather than left`() {
        // One direction succeeded and the other threw. The links are directed precisely
        // so the plan can see that and ask for the missing half.
        val halfOpen = setOf(MixLink("a", "b"))

        val plan = ConferenceMix.plan(established = halfOpen, members = setOf("a", "b"))

        assertEquals(setOf(MixLink("b", "a")), plan.connect)
        assertTrue(plan.disconnect.isEmpty())
    }
}
