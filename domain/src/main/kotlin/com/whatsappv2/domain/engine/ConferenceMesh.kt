package com.whatsappv2.domain.engine

import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.SipUri

/**
 * A full-mesh conference, as arithmetic (ADR-009's successor topology).
 *
 * ## The star could not give a member a grid, and no amount of UI could
 *
 * Until now a conference was a star: the device that pressed Merge held a leg to every
 * member, `pjmedia`'s video bridge composed a personal canvas for each of them, and every
 * member held exactly **one** dialog and therefore received exactly **one** picture. A
 * member cannot draw a tile per participant out of one stream — there is nothing to draw
 * it from — so the grid, the per-tile labels and the mute badges existed on the host's
 * screen alone.
 *
 * In a mesh every participant holds a leg to every other, so every device has N-1 streams
 * and draws the identical grid from its own calls. The host stops being special: it is
 * simply the participant that pressed Merge.
 *
 * ## Nobody relays, and that is what makes duplicates impossible
 *
 * The star had to relay — a member's only path to another member went through the host,
 * as a `pjmedia_conf` cross-link for audio and a composed canvas for video. A mesh must
 * **not**: a peer that is heard directly *and* relayed is heard twice, which is the one
 * failure a conference cannot survive. So in a mesh conference this device connects its
 * microphone and camera to each leg and each leg to its own speaker and its own tile, and
 * never connects one leg to another. See `ConferenceMix.wanted` and `VideoMix.wanted`,
 * which are handed `relay = false` for exactly this reason.
 *
 * The cost is stated rather than hidden: between the merge and a pair's direct call
 * connecting, that pair cannot hear each other, and a pair whose direct call *fails* never
 * will. Both are visible — the peer has no row and no tile until its leg is up — which is
 * the honest failure. A relay fallback would reintroduce the duplicate it exists to avoid.
 *
 * ## The roster is the membership, and reconciling to it is the whole protocol
 *
 * There is no join message and no leave message. The focus announces who is in the
 * conference; every device compares that list against the legs it holds and closes the
 * difference — dial a member with no leg, hang up a leg with no member. One rule covers
 * joining, leaving, being removed by the host, and recovering a leg that failed, because
 * all four are the same difference seen from different sides.
 *
 * Pure, and in `:domain`, so the part that can be wrong in ways a handset would not show
 * — two phones dialling each other at once, a device dialling itself, a leg left open to
 * somebody who has been removed — is decided by a JVM test (§1.3).
 */
object ConferenceMesh {

    /**
     * What this device must do to make its legs match [members].
     *
     * @param members everybody in the conference **including this device**, as the focus
     *   announced them. This device is filtered out here rather than by the caller, so a
     *   roster that names us cannot make us dial ourselves.
     * @param self this device's own address on the conference's account.
     * @param legs the conference legs this device already holds, by the address each
     *   reaches. A leg that is still ringing counts: it is a dial already in flight, and
     *   dialling again would open a second dialog to the same peer.
     */
    fun plan(members: Set<SipUri>, self: SipUri, legs: Map<CallId, SipUri>): MeshPlan {
        val selfKey = key(self)
        val wanted = members.filterNot { key(it) == selfKey }.associateBy { key(it) }
        val held = legs.mapValues { (_, uri) -> key(uri) }

        return MeshPlan(
            // Only the peers we owe a call to: a pair opens one dialog, and [dials]
            // decides which end opens it.
            dial = wanted
                .filterKeys { peer -> peer !in held.values && dials(selfKey, peer) }
                .values
                .toSet(),
            // A leg to somebody the focus no longer lists. That is how a removal reaches
            // the members who were not the one removed, and how a member that left stops
            // being drawn on five other screens.
            drop = held.filterValues { it !in wanted.keys }.keys,
            // The peers we are owed a call *from*. Not an instruction — there is nothing
            // to do but wait — but the screen shows them as connecting rather than as
            // absent, which is the difference between "still arriving" and "not coming".
            awaiting = wanted
                .filterKeys { peer -> peer !in held.values && !dials(selfKey, peer) }
                .values
                .toSet(),
        )
    }

    /**
     * Which end of a pair places the call.
     *
     * The lower key dials. Any total order would do; what matters is that both ends
     * compute the same one from the same two addresses, because the alternative is glare
     * — two INVITEs crossing, two dialogs between one pair, and therefore every peer
     * heard and drawn twice, which is the defect this whole file exists to make
     * impossible.
     *
     * Deliberately **not** a numeric comparison of extensions. `4030` and `4031` order the
     * same either way, but a deployment whose addresses are names, or whose extensions are
     * different lengths, would have two devices disagreeing about which of them is
     * "greater" — and a rule that is right on one dial plan and wrong on the next is worse
     * than an arbitrary one that is always the same.
     */
    fun dials(self: String, peer: String): Boolean = self < peer

    /**
     * The identity two devices will agree on for one address.
     *
     * `user@host`, lowercased, and nothing else. The same participant reaches this device
     * as `sip:4030@192.168.20.56;transport=udp` from the wire and as `sip:4030@…` from the
     * focus's roster, and `SipUri.equals` counts those as different addresses because it
     * compares parameters and port. Matching on the full URI therefore had every device
     * dialling peers it already held a leg to — a second dialog per pair, which is the
     * duplicate audio [dials] exists to prevent, arriving by the other door.
     */
    fun key(uri: SipUri): String = buildString {
        uri.user?.let { append(it.lowercase()).append('@') }
        append(uri.host.rendered.lowercase())
    }

    /**
     * The most participants a mesh carries, counting this device.
     *
     * The same four as [SipConferenceController.MAX_VIDEO_CONFERENCE], and now for a
     * sharper reason than the mixer's four-source ceiling: in a mesh every device encodes
     * and decodes N-1 streams, so the *member* pays what the host used to pay alone. Four
     * is three encodes and three decodes per handset, which is what a four-party star cost
     * its host and what ADR-009 measured as affordable.
     */
    const val MAX_MESH = 4
}

/**
 * The difference between the conference this device is in and the legs it holds.
 *
 * Three sets rather than a list of operations, for the reason `ConferenceMix` gives:
 * a membership can be re-stated on every event and converges, while an add/remove pair has
 * to be applied exactly once in exactly the right order.
 */
data class MeshPlan(
    /** Peers to call, because this end of the pair is the one that dials. */
    val dial: Set<SipUri>,

    /** Legs to end, because the focus no longer lists who they reach. */
    val drop: Set<CallId>,

    /** Peers that are expected to call us. Nothing to do; something to show. */
    val awaiting: Set<SipUri> = emptySet(),
) {
    /** True when the legs already match the membership, so nothing need be sent. */
    val isSettled: Boolean get() = dial.isEmpty() && drop.isEmpty()
}
