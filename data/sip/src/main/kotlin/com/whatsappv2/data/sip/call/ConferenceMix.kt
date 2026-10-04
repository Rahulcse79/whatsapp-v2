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
 * ## A conference is no longer all-star or all-mesh
 *
 * It is a mesh with however many spokes it has to have. Every participant that *can* hold
 * a leg to every other does, and is never relayed; a participant that cannot — a desk
 * phone, a server extension, anything that is not a CoralX client — is carried by this
 * device instead. [wanted] decides which is which from one set, and the two topologies
 * that existed before are the two extremes of it.
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
     * Every directed link a conference of [members] needs, given who must be relayed.
     *
     * [members] is **this device's remote legs**, and the host is not among them. That is
     * not an omission: pjsua wires the microphone to every call and every call to the
     * speaker as the call comes up, per call, so the host already hears each member and
     * each member already hears the host. A link here is only ever between two *remote*
     * legs, and it exists for exactly one reason — to carry one remote participant's
     * audio to another.
     *
     * ## The rule, and why it cannot double anybody
     *
     * A pair needs a link **iff at least one of the two is relayed**.
     *
     * A relayed participant is one that cannot take part in the mesh, and the roster says
     * so (`ConferenceInfoWriter.RELAYED`), so no CoralX peer ever dials it. Its only path
     * to another participant is through this bridge. A pair of *meshed* participants has
     * a dialog of its own and gets no link, because a peer heard directly and relayed is
     * heard twice — the one failure a conference cannot survive.
     *
     * So the two extremes are the two topologies that existed before, and everything
     * between them is the mixed conference this exists for:
     *
     * ```
     *   relayed = members    every pair linked      the star (ADR-009)
     *   relayed = {}         no pair linked         the full mesh
     *   relayed = {d}        every pair touching d  a mesh with one spoke in it
     * ```
     *
     * Worked through for `{b, c, d}` with `d` relayed, as the host's bridge sees it:
     *
     * ```
     *   b <-> c    no link      they hold a dialog of their own
     *   b <-> d    linked       d dials nobody and nobody dials d
     *   c <-> d    linked               "
     * ```
     *
     * and every participant still hears every other, because `b` and `c` hear `d` across
     * the bridge, `d` hears both the same way, and all three hear the host from the leg
     * they are already on.
     *
     * ## Transcoding is not mentioned here because it is not a decision
     *
     * `pjmedia_conf` is a PCM bridge: every port decodes its own codec into the mix at
     * the bridge's clock rate and re-encodes the mix in its own codec on the way out. A
     * link between a Lyra leg and a PCMU leg therefore *is* Lyra to PCM to PCMU, and a
     * link between two Lyra legs is Lyra to PCM to Lyra, without either end being told.
     * Opening the link is the whole of making transcoding happen; there is no codec in
     * this file and there should not be.
     *
     * @param members the conference's remote legs, by call key.
     * @param relayed the subset of [members] this device must carry audio for, because
     *   they cannot hold a leg to the others. Must be a subset of [members]; anything
     *   outside it is ignored rather than inventing a link to a leg that is not here.
     */
    fun wanted(members: Set<String>, relayed: Set<String>): Set<MixLink> {
        if (members.size < MINIMUM_MEMBERS) return emptySet()
        val carried = relayed intersect members
        if (carried.isEmpty()) return emptySet()
        return buildSet {
            members.forEach { from ->
                members.forEach { to ->
                    if (from != to && (from in carried || to in carried)) add(MixLink(from, to))
                }
            }
        }
    }

    /**
     * The two extremes of [wanted], by the boolean the topology used to be.
     *
     * Kept because that is still how the caller thinks when there is no mixed conference
     * in play — a conference is a star or it is a mesh — and because expressing it in
     * terms of the set is what proves the set generalises it rather than replacing it.
     *
     * @param relay true for the star, where this device carries every member to every
     *   other. False for the mesh, where every pair has a dialog of its own.
     */
    fun wanted(members: Set<String>, relay: Boolean = true): Set<MixLink> =
        wanted(members, relayed = if (relay) members else emptySet())

    /**
     * The difference between what the bridge holds and what [members] needs.
     *
     * @param established the links the gateway believes are open right now.
     * @param relayed see [wanted]. Growing it is how a participant that turns out not to
     *   be able to mesh starts being carried; shrinking it is how one that joins the mesh
     *   late stops being carried, in the same breath and with no teardown of its own.
     */
    fun plan(established: Set<MixLink>, members: Set<String>, relayed: Set<String>): MixPlan {
        val target = wanted(members, relayed)
        return MixPlan(
            connect = target - established,
            disconnect = established - target,
        )
    }

    /**
     * The difference, by the boolean the topology used to be. See [wanted].
     */
    fun plan(established: Set<MixLink>, members: Set<String>, relay: Boolean = true): MixPlan =
        plan(established, members, relayed = if (relay) members else emptySet())

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
