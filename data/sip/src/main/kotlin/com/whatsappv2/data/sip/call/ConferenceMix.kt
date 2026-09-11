package com.whatsappv2.data.sip.call

/**
 * One directed connection in the conference bridge: [from]'s audio reaches [to].
 *
 * Directed because `pjmedia_conf` is: `startTransmit` opens one way, and a two-way
 * conversation is two of these. Keeping them separate is what lets a half-open pair — a
 * connection that was made one way and failed the other — be repaired rather than
 * hidden.
 */
internal data class MixLink(val from: String, val to: String)

/** What has to change in the bridge to match the wanted membership. */
internal data class MixPlan(
    val connect: Set<MixLink>,
    val disconnect: Set<MixLink>,
) {
    val isEmpty: Boolean get() = connect.isEmpty() && disconnect.isEmpty()
}

/**
 * Which conference ports to connect, and which to release (ADR-009).
 *
 * ## Why this is a pure function and not a method on the gateway
 *
 * Because it is the whole of the conferencing logic, and it is the part that can be wrong
 * in ways a device test would not catch. A conference of eight is 56 directed links; the
 * question "which links should exist right now" has one right answer and a great many
 * plausible wrong ones — a member connected to itself, a link left behind by a participant
 * who hung up, a pair opened twice because two events arrived together. Every one of those
 * is decidable from a set of call keys, on the JVM, in microseconds. None of them needs a
 * handset (§1.3).
 *
 * The gateway's job is reduced to applying a delta it is handed. That is what keeps
 * `RealPjsipCoreGateway` from growing a second state machine.
 *
 * ## Mix-minus is not implemented here, and that is the point
 *
 * Nobody hears themselves, and no code below arranges that: [wanted] never pairs a member
 * with itself, and `pjmedia_conf` will not transmit a port to itself in any case. The
 * classic conferencing defect — a participant hearing their own voice back, or two members
 * feeding each other into a howl — cannot be expressed by this plan.
 *
 * ## Idempotence is the contract
 *
 * [plan] is given what the bridge currently holds and what it should hold, and returns the
 * difference. Calling it twice with the same membership produces an empty plan the second
 * time, so the caller may run it on every call-state change without counting anything or
 * tracking whether a link was already made.
 */
internal object ConferenceMix {

    /**
     * Every directed link a conference of [members] needs.
     *
     * Each member transmits to every other, which is `n * (n - 1)` links: 2 for a pair,
     * 56 for the eight ADR-009 declares. Fewer than two members is not a conference and
     * wants no links at all — which is also what makes tearing one down a plan like any
     * other rather than a special case.
     */
    fun wanted(members: Set<String>): Set<MixLink> {
        if (members.size < MINIMUM_MEMBERS) return emptySet()
        return buildSet {
            members.forEach { from ->
                members.forEach { to ->
                    if (from != to) add(MixLink(from, to))
                }
            }
        }
    }

    /**
     * The difference between what the bridge holds and what [members] needs.
     *
     * @param established the links the gateway believes are open right now.
     */
    fun plan(established: Set<MixLink>, members: Set<String>): MixPlan {
        val target = wanted(members)
        return MixPlan(
            connect = target - established,
            disconnect = established - target,
        )
    }

    /**
     * The membership after [ended] has gone, whatever it was doing.
     *
     * A separate function because a call leaving is the case that must not be forgotten:
     * a link to a released media port is a use-after-free in a native bridge, not a stale
     * entry in a map. Callers feed the result back to [plan], which produces the
     * disconnects — so a participant hanging up and a participant being removed by the
     * host follow exactly the same path.
     */
    fun without(members: Set<String>, ended: String): Set<String> = members - ended

    /** Below this a conference is just a call, and ADR-009's mixing has nothing to do. */
    const val MINIMUM_MEMBERS = 2

    /** ADR-009's ceiling: 8 participants is the host plus 7 calls, measured at ~350%. */
    const val MAX_MEMBERS = 8
}
