package com.whatsappv2.data.sip.call

import com.whatsappv2.core.common.logging.Logger
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The conference event package's roster, as XML on the wire (RFC 4575).
 *
 * ## Why this exists at all
 *
 * The bridge is the only thing that knows who is in the room. This device holds one leg
 * into `conference 3000` and can see exactly one picture — the composed canvas — so it
 * cannot count faces, and the legs it REFERred away are gone from its own call list the
 * moment the transfer completes. Without this parser `ConferenceSession.rosterAvailable`
 * is false for ever and the participant list is absent rather than wrong, which is honest
 * and useless in equal measure.
 *
 * FreeSWITCH serves it: the `whatsapp-video` profile carries the `rfc-4579` flag, and a
 * `SUBSCRIBE` with `Event: conference` is answered `202 Accepted` followed by a NOTIFY
 * carrying `application/conference-info+xml`. Verified against the running server on
 * 2026-09-20 before a line of this was written.
 *
 * ## Full state only, deliberately
 *
 * [StackConferenceEvent] is documented as a full restatement rather than a delta, and
 * this keeps that promise: a `state="partial"` document describes a change against a
 * roster this parser did not keep, and applying one to a list assembled from a
 * notification that may have been missed is how a participant list ends up showing
 * somebody who left. Partial documents are reported as null and the next full one is
 * used. FreeSWITCH sends `state="full"` on subscribe and on every membership change,
 * so in practice nothing is lost.
 *
 * ## Namespace-unaware on purpose
 *
 * The document declares a default namespace and uses no prefixes, so element names on
 * the wire are exactly `user`, `media`, `display-text`. Parsing namespace-aware would
 * mean every lookup carries a URI to match a document that never varies it.
 */
internal object ConferenceInfoParser {

    /** The roster of one notification. */
    data class Roster(
        /** The conference's own address, from the document's `entity`. */
        val entity: String?,
        val participants: List<StackParticipant>,
    )

    /**
     * The roster in [rawMessage], or null when there is not one.
     *
     * Takes the **whole SIP message** rather than a body, because that is what
     * `SipRxData.wholeMsg` hands over and splitting headers from body is this function's
     * business rather than its caller's.
     *
     * @param selfUri this device's own address, so the member that is us can be marked.
     *   A null or unmatched value simply leaves [StackParticipant.isSelf] false — being
     *   unable to recognise ourselves is not a reason to discard the room.
     */
    fun parse(rawMessage: String, selfUri: String?, logger: Logger? = null): Roster? {
        val xml = bodyOf(rawMessage) ?: return null
        val root = documentElementOf(xml, logger)?.takeIf { it.tagName == "conference-info" } ?: return null

        // See the class note: a partial is a delta against state this does not hold.
        val state = root.getAttribute("state")
        if (state.isNotBlank() && !state.equals("full", ignoreCase = true)) {
            logger?.debug(TAG, "Ignoring a '$state' conference-info; waiting for a full one")
            return null
        }

        return Roster(
            entity = root.getAttribute("entity").takeIf { it.isNotBlank() },
            participants = root.childrenNamed("users")
                .flatMap { it.childrenNamed("user") }
                .mapNotNull { it.toParticipant(selfUri) },
        )
    }

    /** The document's root element, or null — logged — when [xml] will not parse. */
    private fun documentElementOf(xml: String, logger: Logger?): Element? = runCatching {
        DocumentBuilderFactory.newInstance()
            .apply {
                isNamespaceAware = false
                isExpandEntityReferences = false
            }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray()))
            .documentElement
    }.getOrElse {
        logger?.warn(TAG, "conference-info did not parse: ${it.message}")
        null
    }

    /**
     * The XML body of a SIP message, when it is a conference-info document.
     *
     * Matched on the content type rather than on "does it contain a `<`", so a NOTIFY for
     * some other event package — `message-summary` arrives on the same dialog — is not
     * fed to an XML parser to find out.
     */
    private fun bodyOf(rawMessage: String): String? {
        if (!rawMessage.contains(CONTENT_TYPE, ignoreCase = true)) return null
        val separator = rawMessage.indexOf("\r\n\r\n").takeIf { it >= 0 }
            ?: rawMessage.indexOf("\n\n").takeIf { it >= 0 }
            ?: return null
        return rawMessage.substring(separator).trim().takeIf { it.startsWith("<") }
    }

    /**
     * One `<user>` as a participant, or null when it cannot be identified.
     *
     * A member with no `entity` has no stable identity across notifications, and inventing
     * one makes them appear to leave and rejoin on every update — the same reasoning
     * `ConferenceMapper` applies to blank ids, one layer up.
     */
    private fun Element.toParticipant(selfUri: String?): StackParticipant? {
        val entity = getAttribute("entity").takeIf { it.isNotBlank() } ?: return null
        val endpoint = childrenNamed("endpoint").firstOrNull()
        val media = endpoint?.childrenNamed("media").orEmpty()

        return StackParticipant(
            id = entity,
            uri = entity,
            // The user's own display-text first; the endpoint's is the same value on
            // FreeSWITCH and a fallback on bridges that only fill one of them.
            displayName = textOf("display-text") ?: endpoint?.textOf("display-text"),
            // Not sending audio is what "muted" means to everybody else in the room. A
            // member whose audio is `recvonly` or `inactive` is heard by nobody, whatever
            // their own UI calls it.
            isMuted = media.audio()?.let { it.status() in SILENT } ?: false,
            // The bridge publishes no speaking state in conference-info, and guessing one
            // from the floor holder would be a different claim wearing this one's name.
            isSpeaking = false,
            isSelf = selfUri != null && entity.equalsUri(selfUri),
            hasVideoStream = media.video()?.let { it.status() !in SILENT } ?: false,
            joinedAtEpochMillis = endpoint?.childrenNamed("joining-info")
                ?.firstOrNull()?.textOf("when")?.let(::epochMillisOf),
        )
    }

    private fun List<Element>.audio() = firstOrNull { it.textOf("type").equals("audio", true) }
    private fun List<Element>.video() = firstOrNull { it.textOf("type").equals("video", true) }
    private fun Element.status() = textOf("status")?.lowercase(Locale.ROOT)

    /**
     * Two addresses naming the same member.
     *
     * Compared on user and host only. The bridge names a member `sip:1001@host` while this
     * device knows itself as `sip:1001@host;transport=udp` or `<sip:1001@host>`, and a
     * string comparison would say a room of three contains no self.
     */
    private fun String.equalsUri(other: String): Boolean = core() == other.core()

    private fun String.core(): String = trim()
        .removePrefix("<").substringBefore(">")
        .substringAfter("sip:").substringAfter("sips:")
        .substringBefore(";").substringBefore("?")
        .lowercase(Locale.ROOT)

    /**
     * `<when>` as epoch millis, or null.
     *
     * The value is xsd:dateTime with an offset — FreeSWITCH sends
     * `2026-09-20T21:39:50+05:00` — and `SimpleDateFormat`'s `X` reads that. Null on
     * anything else rather than a guess: a join time that is wrong sorts the room wrongly,
     * and no join time at all simply sorts by arrival.
     */
    private fun epochMillisOf(value: String): Long? = runCatching {
        SimpleDateFormat(WHEN_FORMAT, Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(value.trim())?.time
    }.getOrNull()

    /** Direct children with this tag. Direct, so a nested `<user>` cannot double-count. */
    private fun Element.childrenNamed(name: String): List<Element> =
        (0 until childNodes.length)
            .map(childNodes::item)
            .filterIsInstance<Element>()
            .filter { it.tagName == name }

    /** The text of the first direct child with this tag. */
    private fun Element.textOf(name: String): String? = childrenNamed(name)
        .firstOrNull()
        ?.let { element ->
            (0 until element.childNodes.length)
                .map(element.childNodes::item)
                .filter { it.nodeType == Node.TEXT_NODE || it.nodeType == Node.CDATA_SECTION_NODE }
                .joinToString("") { it.nodeValue.orEmpty() }
        }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    private const val TAG = "ConferenceInfo"

    /** The body this parser reads, and the only one it will try. */
    const val CONTENT_TYPE = "application/conference-info+xml"

    /** Media directions in which this member sends nothing. */
    private val SILENT = setOf("recvonly", "inactive")

    private const val WHEN_FORMAT = "yyyy-MM-dd'T'HH:mm:ssX"
}
