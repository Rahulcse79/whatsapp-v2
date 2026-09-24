package com.whatsappv2.data.sip.registration.stack

import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.data.sip.call.VideoLink
import com.whatsappv2.data.sip.call.VideoMix
import com.whatsappv2.data.sip.call.VideoMixPlan
import com.whatsappv2.data.sip.call.VideoPortRef

/**
 * The conference picture, as `pjmedia`'s video bridge actually holds it.
 *
 * The counterpart of [ConferenceBridge], deliberately the same shape — wanted membership,
 * accepted links, ports remembered by slot — because the two solve the same problem and a
 * reader who has understood one should not have to learn a second arrangement. What
 * differs is [VideoMix]: audio is a full mesh of one port per member, video is a set of
 * personal canvases built from asymmetric source and sink ports. See that file for why.
 *
 * ## No server composes the picture, and that is the point
 *
 * Under ADR-003 the conference picture was composed by FreeSWITCH's `mod_conference` in
 * room 3000 and arrived as one stream. This composes it here: every peer's decoder is a
 * source, every peer's encoder is a sink, and the mixer makes each of them a canvas of
 * everyone else. No conference room is dialled and none carries a picture.
 *
 * ## It does **not** require the RTP to leave the server
 *
 * Worth stating plainly, because a dialplan change was once made on the opposite belief.
 * What this class needs is that each leg negotiates a video stream — nothing more. Whether
 * FreeSWITCH bypasses the media, proxies it, or sits in the path and transcodes is
 * invisible here: the decoders and encoders are this handset's either way, and the canvas
 * is built from them.
 *
 * That matters because the media path is not free to choose. Server-side Lyra recording
 * (`freeswitch-lyra`) can only record what reaches the server, so a deployment that
 * records keeps FreeSWITCH in the path — and this composes exactly the same picture. The
 * cost of being in the path is the server's codec list, not the conference.
 *
 * ## Opening a link is asynchronous, and the audio bridge's is not
 *
 * `pjsua_vid_conf_connect` queues the operation and executes it inside the video clock
 * tick (`vid_conf.c`'s `handle_op_queue`), so `startTransmit` returning success means the
 * request was *accepted*, not that the link is open. Completion arrives on
 * `Endpoint.onVideoMediaOpCompleted`. Two consequences, both handled here rather than left
 * to callers:
 *
 *  - a link is recorded as open when the request is accepted, because the next [remix]
 *    would otherwise queue it a second time; `vid_conf` answers a duplicate with
 *    "Video ports connection N->M already exists", which is harmless but hides real
 *    failures in noise;
 *  - nothing may be released between the request and the tick. [remove] runs before a
 *    call's media is freed for exactly that reason, and it closes by plan.
 *
 * ## PJSIP thread only
 *
 * Every entry point is called from the gateway's `onPjsip` block or from a pjsua2
 * callback, which is already on that thread. Neither field is synchronised and neither
 * needs to be — the same rule [ConferenceBridge] follows.
 */
internal class VideoConferenceBridge(
    private val logger: Logger,
    /** A port, or null while it does not exist — a leg still ringing has no decoder. */
    private val portOf: (VideoPortRef) -> VideoPort?,
) {

    private var members: Set<String> = emptySet()
    private var links: Set<VideoLink> = emptySet()

    /**
     * Whether this device composes a canvas for each peer — see [VideoMix.wanted].
     *
     * Held for the reason [ConferenceBridge]'s twin is: [remix] runs from the media-state
     * callback and has no way to be told the conference's shape at that moment.
     */
    private var compose: Boolean = true

    /** The bridge slot each end of a link was opened on — see [forgetRebuiltPorts]. */
    private val portIds = mutableMapOf<VideoPortRef, Int>()

    /** True while a video conference exists, so callers can skip the work entirely. */
    val isActive: Boolean get() = members.isNotEmpty()

    /** Who is in the picture right now, for the roster. */
    val currentMembers: Set<String> get() = members

    /**
     * Mixes exactly [callKeys], and returns the members whose ports actually exist.
     *
     * Idempotent. An empty set is a teardown: [remix] closes every link to get there, and
     * the slot bookkeeping goes with them.
     *
     * @param compose false for a mesh, where every peer receives every other peer's
     *   camera directly and a composed canvas would draw each of them twice.
     */
    fun set(callKeys: Set<String>, compose: Boolean = true): Set<String> {
        members = callKeys
        this.compose = compose
        val live = remix()
        if (members.isEmpty()) portIds.clear()
        return live
    }

    /**
     * Re-states the membership against the ports that exist now.
     *
     * Called after anything that could have moved a port: a media-state change, a hold, a
     * re-INVITE, a camera switch. Cheap and idempotent, so a caller never has to work out
     * whether something moved — which is what lets a leg that was still ringing join the
     * picture by itself the moment its media comes up.
     */
    fun remix(): Set<String> {
        val live = members.filterTo(mutableSetOf()) { portOf(VideoPortRef.decoder(it)) != null }
        forgetRebuiltPorts(live)
        val plan = VideoMix.plan(links, live, compose)
        if (plan.isEmpty) return live

        val applied = apply(plan)
        links = links - plan.disconnect + applied.opened

        if (applied.deferred > 0) {
            logger.info(TAG, "Video conference: ${applied.deferred} link(s) deferred until their media is up")
        }
        logger.info(TAG, "Video conference: ${live.size} member(s), ${links.size} link(s) open")
        return live
    }

    /**
     * Forgets every link touching a port that is not on the slot it was opened on.
     *
     * pjsua rebuilds a call's video ports on every re-INVITE that touches its media — a
     * hold, a resume, a codec change, `setVideoEnabled` — and every link the old slot held
     * goes with it, silently. This is [ConferenceBridge.forgetRebuiltPorts]'s lesson
     * applied before it can be learnt twice: on the audio bridge, believing a dead link
     * was open left two of eight members heard by nobody. Here it would leave a
     * participant in the roster and absent from everyone's picture.
     */
    private fun forgetRebuiltPorts(live: Set<String>) {
        val rebuilt = portIds.keys.filterTo(mutableSetOf()) { ref ->
            val current = portOf(ref)?.id ?: return@filterTo false
            current != portIds[ref]
        }
        // A member whose call has gone entirely: its ports cannot be read any more, so the
        // slot check above cannot see them, but its links are just as dead.
        val departed = members - live
        if (rebuilt.isEmpty() && departed.isEmpty()) return

        links = links.filterNotTo(mutableSetOf()) { link ->
            link.from in rebuilt || link.to in rebuilt ||
                link.from.callKey in departed || link.to.callKey in departed
        }
        rebuilt.forEach(portIds::remove)
        if (rebuilt.isNotEmpty()) {
            logger.info(TAG, "Video conference: ${rebuilt.size} port(s) came back on a new slot; relinking")
        }
    }

    /**
     * Drops [callKey] and releases every link it holds, **before** its media is freed.
     *
     * A link into a released port is a use-after-free in a native bridge, not a stale
     * entry in a map — and the video bridge makes it worse than the audio one, because a
     * queued connect executes on a later clock tick and can therefore land on a port that
     * was freed in between. Closing on the way out is the only ordering that is safe.
     */
    fun remove(callKey: String) {
        if (!isActive) return
        members = VideoMix.without(members, callKey)
        val live = members.filterTo(mutableSetOf()) { portOf(VideoPortRef.decoder(it)) != null }
        val plan = VideoMix.plan(links, live, compose)
        apply(plan)
        links = links - plan.disconnect
        portIds.keys.filterTo(mutableSetOf()) { it.callKey == callKey }.forEach(portIds::remove)

        if (members.size < VideoMix.MINIMUM_MEMBERS) {
            // Back to a one-to-one call, or to nothing. Everything still open is torn
            // down: pjsua's own camera → encoder and decoder → window links are its to
            // manage again, and anything this class opened must not outlive the mix.
            closeEverything()
            logger.info(TAG, "Video conference ended; ${members.size} member(s) left")
            return
        }

        // Everyone still here needs a canvas that no longer carries the leg that left.
        // The plan above closed its links; this re-states what remains against the ports
        // as they are now, which is also what picks up a member whose own ports moved
        // while this was happening. Cheap and idempotent, so doing it unconditionally
        // costs nothing when nothing moved.
        remix()
    }

    /** Closes every link this bridge opened and forgets the conference. */
    fun closeEverything() {
        if (links.isNotEmpty()) apply(VideoMixPlan(connect = emptySet(), disconnect = links))
        members = emptySet()
        links = emptySet()
        portIds.clear()
    }

    /** What [apply] managed to do, which is not always what it was asked to do. */
    private data class Applied(val opened: Set<VideoLink>, val deferred: Int)

    /**
     * Opens and closes links, reporting what happened rather than what was asked.
     *
     * A link that throws is counted, not propagated — one refused port must not collapse
     * the conference, and the next [remix] tries it again. Disconnects are attempted for
     * every link in the plan and dropped from the set either way: a port that has already
     * gone cannot be disconnected from, and insisting would leave the entry behind for
     * ever.
     */
    private fun apply(plan: VideoMixPlan): Applied {
        var deferred = 0
        val opened = buildSet {
            plan.connect.forEach { link ->
                val from = portOf(link.from)
                val to = portOf(link.to)
                // A source that is also the sink is pjsua having reused a slot; the bridge
                // would loop the port to itself, which is a participant's own picture
                // composed into their own canvas.
                if (from == null || to == null || from.id == to.id) {
                    deferred++
                    return@forEach
                }
                runCatching { from.transmitTo(to) }
                    .onSuccess {
                        add(link)
                        portIds[link.from] = from.id
                        portIds[link.to] = to.id
                    }
                    .onFailure {
                        deferred++
                        logger.warn(TAG, "Video link refused: ${it.message}")
                    }
            }
        }

        plan.disconnect.forEach { link ->
            val from = portOf(link.from) ?: return@forEach
            val to = portOf(link.to) ?: return@forEach
            runCatching { from.stopTransmitTo(to) }
        }
        return Applied(opened, deferred)
    }

    private companion object {
        const val TAG = "PjsipGateway"
    }
}

/**
 * One port on the video bridge, as much of `VideoMedia` as the conference needs.
 *
 * An interface for the reason [ConferencePort] is one: `VideoMedia` loads the native
 * library the moment the class is touched, which no unit test may do, so the arithmetic is
 * exercised on the JVM with ports that record what was asked of them.
 */
internal interface VideoPort {
    /** pjmedia's slot for this port; a new number means a new port, whatever the call. */
    val id: Int

    fun transmitTo(other: VideoPort)

    fun stopTransmitTo(other: VideoPort)
}
