package com.whatsappv2.data.sip.call

/**
 * The conference roster as XML on the wire (RFC 4575), written by the host.
 *
 * ## Why this device writes a document it also parses
 *
 * [ConferenceInfoParser] exists because the *bridge* was the only thing that knew who was
 * in the room. With the conference mixed here that is inverted: this device is the only
 * thing that knows, and every member holds one leg to it and can see exactly one picture.
 * A member could not name the people it was looking at, could not say it was in a
 * conference at all, and rendered the composed canvas as an ordinary one-to-one call —
 * cropped to fill a portrait screen, so the outer columns of the host's picture were
 * simply off the edge (measured on a TC15, 2026-09-24).
 *
 * So the host announces the roster to each member, in the dialog that already exists,
 * and the member feeds it to [ConferenceInfoParser] unchanged. One format, one parser,
 * already tested against FreeSWITCH's own documents — and the day a server bridge serves
 * the roster again, nothing above this line can tell the difference.
 *
 * ## Why RFC 4575 and not something smaller
 *
 * A two-line private format would have been less code here and a second thing to parse
 * everywhere else. This document is what the member's parser already accepts, it is what
 * a server would send, and it costs a few hundred bytes per membership change on a link
 * that is carrying video.
 *
 * ## Why SIP MESSAGE and not SUBSCRIBE/NOTIFY
 *
 * The event package would be the textbook answer and it is the one thing that cannot be
 * used here: `Call.sendRequest("SUBSCRIBE")` puts a bare SUBSCRIBE into the INVITE dialog
 * and SIGSEGVs in `evsub` about thirty seconds later, taking the process with it — see
 * `RealPjsipCoreGateway.ROSTER_SUBSCRIBE_ENABLED`. An in-dialog MESSAGE creates no
 * subscription and goes nowhere near that code: `Call.sendInstantMessage` out,
 * `Call.onInstantMessage` in, both confirmed present on the real pjsua2 artifact.
 *
 * Pure, and a `String` in and out, so the document is a JVM test rather than something to
 * read off a packet capture.
 */
internal object ConferenceInfoWriter {

    /**
     * A full roster document for [participants], as the conference at [entity].
     *
     * `state="full"` always. [ConferenceInfoParser] discards anything else on purpose — a
     * partial is a delta against state the reader does not hold — and this has the whole
     * membership in hand every time it is called, so there is nothing a delta would save.
     *
     * @param entity the conference's own address. The host's own identity, because on a
     *   device-mixed conference the host *is* the focus; a member reads it as the address
     *   of the room they are in.
     * @param participants everybody in the conference, the host included. Order is kept:
     *   the reader lists them as they arrive and the host is written first.
     */
    fun roster(
        entity: String,
        participants: List<StackParticipant>,
        mesh: Boolean = false,
    ): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("<conference-info state=\"full\" entity=\"${entity.escaped()}\">")
        append("<conference-description>")
        append("<display-text>Conference</display-text>")
        // The one thing a server bridge would never say, and the one thing a member
        // cannot work out: that this conference is a mesh, so it should hold a leg to
        // every other participant rather than wait for a canvas. An RFC 4575 reader that
        // does not know the element ignores it, which is exactly the right behaviour for
        // a peer that cannot mesh — it keeps the roster and stays a spoke.
        if (mesh) append("<$TOPOLOGY>$MESH</$TOPOLOGY>")
        append("</conference-description>")
        append("<users>")
        participants.forEach { append(it.asUser()) }
        append("</users>")
        append("</conference-info>")
    }

    /**
     * One `<user>`, shaped exactly as the parser reads it.
     *
     * The media elements are not decoration: `isMuted` is read back from the **audio**
     * stream's status and `hasVideoStream` from the video one's, so a participant written
     * without them comes back unmuted and without a picture whatever they were. Silence
     * is `recvonly` rather than `inactive` — a muted member is still listening, and the
     * distinction is the one an RFC 4575 reader expects.
     */
    private fun StackParticipant.asUser(): String = buildString {
        val address = uri ?: id
        append("<user entity=\"${address.escaped()}\">")
        displayName?.takeIf { it.isNotBlank() }?.let {
            append("<display-text>${it.escaped()}</display-text>")
        }
        append("<endpoint entity=\"${address.escaped()}\">")
        append(media(type = "audio", sending = !isMuted))
        append(media(type = "video", sending = hasVideoStream))
        append("</endpoint>")
        append("</user>")
    }

    /** The extension element naming the topology, read back by [ConferenceInfoParser]. */
    const val TOPOLOGY = "coralx-topology"

    /** Its value for a conference every participant holds a leg into. */
    const val MESH = "mesh"

    private fun media(type: String, sending: Boolean): String =
        "<media><type>$type</type><status>${if (sending) "sendrecv" else "recvonly"}</status></media>"

    /**
     * XML's five, so a display name with an ampersand in it does not produce a document
     * the other end silently drops.
     *
     * The address goes through this too. A SIP URI has no business containing `<` or `&`,
     * and a peer that sends one is exactly the case where a parser must not be handed raw
     * text (§7 — nothing off the wire is trusted).
     */
    private fun String.escaped(): String = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
