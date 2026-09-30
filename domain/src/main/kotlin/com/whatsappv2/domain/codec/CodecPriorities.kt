package com.whatsappv2.domain.codec

/**
 * Which codec gets which PJSIP priority, decided without PJSIP (§5.1, §5.2).
 *
 * ## Why this is a pure function in `:domain`
 *
 * PJSIP has no per-codec enable flag and no payload-type array to reorder. It has **one
 * priority per codec, endpoint-wide** — `255` first choice, `0` disabled — so "offer these
 * codecs in this order" becomes "assign these numbers". That translation is a rule, and
 * principle 3 says a rule that can only be checked by placing a real call is in the wrong
 * layer. Every decision below is made here, on the JVM, under test; the adapter only reads
 * the registry and writes the numbers back.
 *
 * ## The defect this exists to make impossible
 *
 * The adapter used to walk the registry inline, assign `0` to everything no preference
 * named, and stop. On 2026-09-10 an account was saved with one audio preference — `lyra` —
 * which this build does not contain. Nothing matched, so **every registered audio codec was
 * set to priority 0**, endpoint-wide and for every account.
 *
 * `pjmedia_endpt_create_audio_sdp` walks the registry in priority order and breaks on the
 * first disabled codec (`pjmedia/src/pjmedia/endpoint.c:490`), so it produced an m-line with
 * zero formats. pjsua deactivated it. Every call afterwards failed the same way and the
 * failures looked like two unrelated bugs:
 *
 *  - **outgoing** offers went out as `m=audio 0 RTP/AVP 0` — a dead media line — and the
 *    server answered `488`;
 *  - **inbound** calls rang, and answering them produced `PJMEDIA_SDPNEG_ENOMEDIA` and a
 *    `488 Unable to create media session` **sent by this app**, one second after the user
 *    pressed answer. Which is exactly "the call disconnects when I answer it".
 *
 * Two rules below prevent it, and both are here rather than in the adapter because both are
 * decisions:
 *
 *  1. **[Assignment.isEmpty] is never silently applied.** A preference set that matches
 *     nothing yields no changes at all, so the endpoint keeps whatever pjmedia registered
 *     rather than losing its voice. A degraded offer is recoverable; a mute endpoint is not.
 *  2. **Priorities are endpoint-wide, so the input is every account's preferences**, not one
 *     account's. Ranking follows the account being applied; anything another account needs
 *     stays enabled below it. Without this the last account to register decides what the
 *     others may negotiate.
 */
object CodecPriorities {

    /**
     * The priority the first preference gets; each next one gets less.
     *
     * Not the nominal maximum of 255. pjmedia's `sort_codecs` (`vid_codec.c:527`, and the
     * audio twin) rewrites every leading codec at 255 down to 254 after sorting, so an
     * assignment starting at 255 landed the first two preferences on the same number: the
     * TC15's audit read `opus@254, G722@254`. Two codecs on one priority are ordered by an
     * unstable selection sort, which is how two VP8 implementations swapped places every time
     * an account was saved (2026-09-11). Distinct priorities are what make the order the
     * account asked for the order the SDP carries.
     *
     * `253` rather than `254` since 2026-09-27, to leave [MEDIACODEC_VP8] a number of its own
     * **after** that rewrite. At 254 it did not: the rewrite lands the tiebreak on 254 too, so
     * an account whose first video preference was not VP8 produced a tie — the M23's audit read
     * `VP8/103@254, H264/99@254` while the M14's, whose account asks for VP8 first, read a
     * clean `254, 253, 252`. See [MEDIACODEC_VP8] for what the tie cost.
     */
    const val TOP: Short = 253

    /** PJSIP's "never offer this". */
    const val DISABLED: Short = 0

    /**
     * Where MediaCodec's VP8 sits, which is one step above libvpx's.
     *
     * Both answer to an account that asked for "VP8", so [assign] gives them the same
     * rank and the winner becomes whichever `pjmedia` registered first. That is not a
     * choice, and on a handset with a hardware VP8 it is usually the wrong one: libvpx
     * encodes 1280x720 in software at 68 ms a frame on an M14, against a Codec2 encoder
     * sitting unused beside it.
     *
     * `TOP + 1` rather than a fixed number, so this stays above the highest rank [assign]
     * can hand out no matter how the account's list is ordered. It is only ever written
     * for a codec `pjmedia` actually registered.
     *
     * ## What the tie cost, measured
     *
     * This was 255, which is the one value pjmedia's `sort_codecs` rewrites — down to 254,
     * which was also [TOP]. So on a handset whose account asks for H264 before VP8, the
     * hardware VP8 tied with H264 and the offer's video formats came out in an order nobody
     * chose. On an M23 (2026-09-27) that order was `m=video RTP/AVP 102 99 103`, putting
     * *libvpx's* VP8 first.
     *
     * That is not merely a lost preference. `pjmedia_vid_stream_info_from_sdp` takes the
     * receive payload type from the **first** format of the local m-line
     * (`stream_info.c:114`) while the transmit one is matched against the answer, so when the
     * far end answered `103` the stream came up `rx_pt=102, tx_pt=103`. Every arriving VP8
     * packet was discarded by the depacketiser: RTP in at 19-90 pkt/s with zero loss, jitter
     * buffer empty, `decode 0.0` — two black tiles on a three-party mesh, from the moment the
     * handset resumed from hold until the streams were next rebuilt. [TOP] therefore has to
     * stay far enough below 255 that this number survives the rewrite intact.
     *
     * What registration now guarantees, and what it does not. Both halves are discovered
     * from the platform's own list and each is **created** to prove the component really
     * exists — which is exactly what a retired `OMX.*` name fails. It is *not* proof that
     * either half produces output: nothing is configured, started or fed at registration,
     * deliberately, because doing so wedged the PJSIP thread on an SM-E236B and left the
     * account unable to register at all. A component that opens and then produces nothing
     * is caught in the call instead, and condemned so the next call avoids it.
     */
    const val MEDIACODEC_VP8: Short = (TOP + 1).toShort()

    /**
     * The lowest priority this assigns to a codec that is still enabled.
     *
     * A codec that only *another* account wants is kept, and kept below everything the
     * current account named, so it can still be answered without ever being offered first.
     * `1` rather than `0` is the whole distinction between "not my first choice" and "gone".
     */
    const val KEPT_FOR_ANOTHER_ACCOUNT: Short = 1

    /**
     * The registry entries that duplicate [keep]'s encoding and must therefore be switched off.
     *
     * One encoding under two payload types is a trap rather than a choice. Two VP8 entries
     * are byte-identical on the wire — `a=rtpmap:102 VP8/90000` beside `a=rtpmap:103
     * VP8/90000` — so a peer cannot tell them apart and the second buys nothing. What it costs
     * is that `pjmedia_vid_stream_info_from_sdp` takes the receive payload type from the
     * **first** format of the local m-line (`stream_info.c:114`) while the transmit one is
     * matched against the answer, so any answer naming the other number splits the two and the
     * depacketiser then discards every packet that arrives. See [MEDIACODEC_VP8].
     *
     * Matched on the encoding name, because the number is the part that varies: which payload
     * type each implementation gets depends on the order `pjmedia` registered them in.
     *
     * Empty when [keep] is not in [available] — there is nothing to prefer, so there is nothing
     * to switch off, and the other implementation stays exactly as it was.
     */
    fun duplicatesOf(keep: String, available: List<String>): List<String> {
        if (available.none { it.equals(keep, ignoreCase = true) }) return emptyList()
        val encoding = encodingOf(keep)
        return available.filter { !it.equals(keep, ignoreCase = true) && encodingOf(it) == encoding }
    }

    /** A codec id's encoding name, without the payload type or clock rate PJSIP spells it with. */
    private fun encodingOf(codecId: String): String = codecId.substringBefore('/').lowercase()

    /**
     * The result of ranking one registry against one set of preferences.
     *
     * @property priorities the priority to write per codec id, for the ids that change.
     * @property unmatchedPreferences preferences that matched no registered codec. Never an
     *   error on its own — `H264` is named by the default preferences and is not in every
     *   build — but always worth saying out loud, because it is silent otherwise.
     * @property wouldDisableEverything true when [preferred] matched nothing at all. The
     *   caller must not apply [priorities] in that case; it is empty precisely so that
     *   applying it anyway is a no-op rather than an outage.
     */
    data class Assignment(
        val priorities: Map<String, Short>,
        val unmatchedPreferences: List<String>,
        val wouldDisableEverything: Boolean,
    )

    /**
     * Ranks [available] against [preferred], keeping [alsoRequired] enabled underneath.
     *
     * Matching is by **prefix, case-insensitively**, because PJSIP spells codec ids with a
     * clock rate and a channel count — `opus/48000/2`, `PCMU/8000/1` — while the domain
     * stores the payload name alone. Lowercasing is load-bearing and has cost this project
     * one silent failure already: an uppercase `LYRA` matches `lyra/16000/1` never, and does
     * so with no error anywhere ([com.whatsappv2.domain.model.AudioCodec.LYRA]).
     *
     * ## One preference, several codecs
     *
     * A build can register the same payload name twice — `VP8/102` from libvpx and `VP8/103`
     * from Android's MediaCodec — and one preference names both. They are **not** given the
     * same priority: every enabled codec gets a distinct number, descending through the
     * preferences and, within one preference, through [available] in the order given. The
     * caller decides that order and so decides which implementation is offered first; what
     * this guarantees is that the decision sticks, because pjmedia sorts equal priorities
     * with an unstable selection sort and had been swapping the two VP8s on every save.
     *
     * @param available every codec id the library registered, in the order the caller wants
     *   same-name codecs offered.
     * @param preferred the account's preferences, most preferred first.
     * @param alsoRequired preferences belonging to *other* configured accounts. Kept enabled
     *   at [KEPT_FOR_ANOTHER_ACCOUNT] so this account's list cannot mute theirs.
     */
    fun assign(
        available: List<String>,
        preferred: List<String>,
        alsoRequired: Set<String> = emptySet(),
    ): Assignment {
        val ranked = available.associateWith { codecId -> rankOf(codecId, preferred) }
        val matchedAnything = ranked.values.any { it >= 0 }

        // Nothing matched: hand back an empty map so the caller cannot apply it by accident.
        // This is the whole guard, and it is a value rather than an exception because the
        // caller's correct response is to carry on with the registry's own defaults and say
        // so — not to fail the registration the user is waiting on.
        if (!matchedAnything) {
            return Assignment(
                priorities = emptyMap(),
                unmatchedPreferences = preferred,
                wouldDisableEverything = available.isNotEmpty() && preferred.isNotEmpty(),
            )
        }

        // Descending from the top, one number per enabled codec: preferences in their order,
        // and same-name codecs in `available`'s. The arithmetic cannot underflow: a registry
        // is far shorter than 254 entries, and coerceAtLeast makes that a guarantee rather
        // than an assumption.
        val enabled = ranked.filterValues { it >= 0 }.entries
            .sortedBy { (_, rank) -> rank }
            .mapIndexed { position, (codecId, _) ->
                codecId to (TOP - position).coerceAtLeast(KEPT_FOR_ANOTHER_ACCOUNT.toInt()).toShort()
            }
            .toMap()
        val priorities = ranked.mapValues { (codecId, _) ->
            when {
                codecId in enabled -> enabled.getValue(codecId)
                matches(codecId, alsoRequired) -> KEPT_FOR_ANOTHER_ACCOUNT
                else -> DISABLED
            }
        }

        return Assignment(
            priorities = priorities,
            unmatchedPreferences = preferred.filter { name ->
                available.none { it.startsWith(name, ignoreCase = true) }
            },
            wouldDisableEverything = false,
        )
    }

    private fun rankOf(codecId: String, preferred: List<String>): Int =
        preferred.indexOfFirst { codecId.startsWith(it, ignoreCase = true) }

    private fun matches(codecId: String, names: Set<String>): Boolean =
        names.any { codecId.startsWith(it, ignoreCase = true) }
}
