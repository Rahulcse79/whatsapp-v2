package com.whatsappv2.data.sip.call

/**
 * Which video port is which, on `pjmedia`'s video bridge.
 *
 * Audio needs no such distinction — a call has exactly one port on `pjmedia_conf` and it
 * both sends and receives, which is why [MixLink] can name a member by its call key alone.
 * Video is asymmetric: a call has a **decoder** port carrying what the far end sends and an
 * **encoder** port carrying what this device sends *to that one peer*, and they are
 * different slots on the bridge. A conference is built by choosing, for each encoder,
 * which decoders feed it.
 */
internal enum class VideoRole {
    /** This device's camera. One per device, so [VideoPortRef.callKey] is [LOCAL]. */
    CAMERA,

    /** What a peer is sending us. A source only. */
    DECODER,

    /** What we send to one peer. A sink only. */
    ENCODER,

    /** The window on the call screen. A sink only, and there is one. */
    RENDERER,
}

/**
 * One port, named by what it is and whose call it belongs to.
 *
 * [CAMERA] and [RENDERER] belong to the device rather than to a call and carry [LOCAL].
 */
internal data class VideoPortRef(val role: VideoRole, val callKey: String = LOCAL) {
    companion object {
        /** The key for the two ports that are the device's own. */
        const val LOCAL = "<local>"

        val camera = VideoPortRef(VideoRole.CAMERA)
        val renderer = VideoPortRef(VideoRole.RENDERER)

        fun decoder(callKey: String) = VideoPortRef(VideoRole.DECODER, callKey)

        fun encoder(callKey: String) = VideoPortRef(VideoRole.ENCODER, callKey)
    }
}

/** One open transmission on the video bridge. */
internal data class VideoLink(val from: VideoPortRef, val to: VideoPortRef)

/** What has to change to reach the wanted arrangement. */
internal data class VideoMixPlan(
    val connect: Set<VideoLink>,
    val disconnect: Set<VideoLink>,
) {
    val isEmpty: Boolean get() = connect.isEmpty() && disconnect.isEmpty()
}

/**
 * Who sends video to whom, as a set of links (ADR-009's video half).
 *
 * ## Why this is not [ConferenceMix] with a different name
 *
 * The audio mix is a **full mesh**: every member transmits to every other, and
 * `pjmedia_conf` sums whatever arrives at each port. One port per member, one rule.
 *
 * The video mix is not a mesh, because a video port is either a source or a sink and never
 * both. Each peer's **encoder** is a sink that must receive this device's camera and every
 * *other* peer's decoder — and never its own, which would send a participant their own face
 * back as a tile beside the others. The local **renderer** is a fourth sink that receives
 * every decoder and no camera, because the camera is already on screen as the floating
 * self-view ([com.whatsappv2.feature.calls.SelfPreview]) and a second copy inside the grid
 * is the duplicate this function exists to prevent.
 *
 * So for members `{A, B, C}` the wanted set is:
 *
 * ```
 * camera    -> encoder(A)   decoder(B) -> encoder(A)   decoder(C) -> encoder(A)
 * camera    -> encoder(B)   decoder(A) -> encoder(B)   decoder(C) -> encoder(B)
 * camera    -> encoder(C)   decoder(A) -> encoder(C)   decoder(B) -> encoder(C)
 * decoder(A) -> renderer    decoder(B) -> renderer     decoder(C) -> renderer
 * ```
 *
 * Twelve links for a four-party call, which is host-and-spokes stated as arithmetic: this
 * device composes a personal canvas for each peer and one for itself.
 *
 * ## The ceiling is the mixer's, and it is silent
 *
 * `pjmedia`'s video bridge composes at most **four** sources onto one sink: `vid_conf.c`
 * declares `pjmedia_rect_size tr_size[4]` and loops
 * `for (i = 0; i < cp->transmitter_cnt && i < 4; ++i)`. A fifth source is given no render
 * state at all — not refused, not logged, simply never drawn. So a ceiling has to be
 * enforced here, where it can be said out loud, rather than discovered as a participant
 * who is in the call and not in the picture.
 *
 * [MAX_PARTICIPANTS] is 4 **including this device**, so at most [MAX_MEMBERS] remote legs.
 * At that size the busiest sink carries three sources (camera + two decoders), one inside
 * the mixer's own limit.
 */
internal object VideoMix {

    /**
     * Every link that should be open for [members].
     *
     * Empty below [MINIMUM_MEMBERS]: one remote leg is a one-to-one call, which pjsua
     * already wires camera → encoder and decoder → window by itself. Returning links for
     * it would re-state what is already true and make every ordinary video call pay for
     * the conference's bookkeeping.
     */
    fun wanted(members: Set<String>): Set<VideoLink> {
        if (members.size < MINIMUM_MEMBERS) return emptySet()
        return buildSet {
            members.forEach { member ->
                val encoder = VideoPortRef.encoder(member)
                // Our camera, into every peer's canvas.
                add(VideoLink(VideoPortRef.camera, encoder))
                // Every other peer, into this peer's canvas — never this peer itself.
                members.forEach { other ->
                    if (other != member) add(VideoLink(VideoPortRef.decoder(other), encoder))
                }
                // And every peer onto our own screen.
                add(VideoLink(VideoPortRef.decoder(member), VideoPortRef.renderer))
            }
        }
    }

    /** What to open and what to close to get from [established] to the mix [members] want. */
    fun plan(established: Set<VideoLink>, members: Set<String>): VideoMixPlan {
        val target = wanted(members)
        return VideoMixPlan(connect = target - established, disconnect = established - target)
    }

    /** [members] without [ended], for a leg that has gone. */
    fun without(members: Set<String>, ended: String): Set<String> = members - ended

    /**
     * True when [members] plus this device would exceed [MAX_PARTICIPANTS].
     *
     * Asked before a leg is admitted, so the refusal is a decision rather than a picture
     * with somebody missing from it.
     */
    fun isFull(members: Set<String>): Boolean = members.size >= MAX_MEMBERS

    /** Two members is the least that is a conference rather than a call. */
    const val MINIMUM_MEMBERS = 2

    /**
     * Everyone on the call, this device included.
     *
     * Four, because `vid_conf` composes four sources onto a sink and the busiest sink in a
     * four-party call carries three. It is a product ceiling that happens to sit inside a
     * mixer ceiling; both are stated so neither can be raised by accident.
     */
    const val MAX_PARTICIPANTS = 4

    /** Remote legs, which is everyone except this device. */
    const val MAX_MEMBERS = MAX_PARTICIPANTS - 1
}
