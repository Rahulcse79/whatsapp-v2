package com.whatsappv2.domain.codec

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The codec-priority rule, with no PJSIP anywhere near it.
 *
 * The first two cases are the 2026-09-10 defect: an account saved with `audio=[lyra]`
 * disabled every registered audio codec endpoint-wide, so answering a call produced
 * `PJMEDIA_SDPNEG_ENOMEDIA` and this app sent itself a `488`. Both are now failures of this
 * test rather than of a call.
 */
class CodecPrioritiesTest {

    /** What the handset's library actually registers, verbatim off the device. */
    private val registry = listOf(
        "opus/48000/2", "G722/16000/1", "PCMU/8000/1", "PCMA/8000/1",
        "GSM/8000/1", "iLBC/8000/1", "AMR-WB/16000/1", "AMR/8000/1",
        "speex/32000/1", "speex/8000/1", "speex/16000/1",
    )

    @Test
    fun `a preference list that matches nothing changes no priority at all`() {
        // The defect, exactly: `lyra` is declared in the domain and PJMEDIA_HAS_LYRA_CODEC
        // is 0, so it matches nothing. Disabling the registry to honour it left the endpoint
        // unable to build an m-line with any format in it.
        val result = CodecPriorities.assign(available = registry, preferred = listOf("lyra"))

        assertTrue(result.priorities.isEmpty(), "nothing may be written when nothing matched")
        assertTrue(result.wouldDisableEverything)
        assertEquals(listOf("lyra"), result.unmatchedPreferences)
    }

    @Test
    fun `an empty assignment cannot mute the endpoint when applied`() {
        // The guard has to hold as a property of the value, not of the caller remembering to
        // check a flag: applying the map must be a no-op, whatever the caller does with it.
        val result = CodecPriorities.assign(available = registry, preferred = listOf("lyra"))

        val stillEnabled = registry.filter { result.priorities[it] != CodecPriorities.DISABLED }
        assertEquals(registry, stillEnabled, "every codec survives an unmatchable list")
    }

    @Test
    fun `preferences become descending priorities in the order they were given`() {
        val result = CodecPriorities.assign(
            available = registry,
            preferred = listOf("opus", "PCMU", "PCMA"),
        )

        assertEquals(CodecPriorities.TOP, result.priorities.getValue("opus/48000/2"))
        assertEquals(below(1), result.priorities.getValue("PCMU/8000/1"))
        assertEquals(below(2), result.priorities.getValue("PCMA/8000/1"))
        assertFalse(result.wouldDisableEverything)
    }

    @Test
    fun `a codec no preference names is disabled`() {
        val result = CodecPriorities.assign(available = registry, preferred = listOf("PCMU"))

        assertEquals(CodecPriorities.DISABLED, result.priorities.getValue("GSM/8000/1"))
        assertEquals(CodecPriorities.DISABLED, result.priorities.getValue("speex/8000/1"))
    }

    @Test
    fun `one account cannot disable a codec another account requires`() {
        // PJSIP's priorities are endpoint-wide, so without this the last account to register
        // decides what every other account may negotiate. The second account's codec stays
        // enabled, and stays below the first account's list so it is never offered first.
        val result = CodecPriorities.assign(
            available = registry,
            preferred = listOf("PCMU"),
            alsoRequired = setOf("opus"),
        )

        assertEquals(CodecPriorities.TOP, result.priorities.getValue("PCMU/8000/1"))
        assertEquals(
            CodecPriorities.KEPT_FOR_ANOTHER_ACCOUNT,
            result.priorities.getValue("opus/48000/2"),
            "kept, and ranked below what this account asked for",
        )
        assertEquals(CodecPriorities.DISABLED, result.priorities.getValue("GSM/8000/1"))
    }

    @Test
    fun `matching is by prefix and ignores case, because PJSIP spells ids with a clock rate`() {
        // `opus` must match `opus/48000/2`, and the case difference between the domain's
        // `PCMU` and a registry that could spell it either way must not matter.
        val result = CodecPriorities.assign(
            available = listOf("opus/48000/2", "pcmu/8000/1"),
            preferred = listOf("OPUS", "PCMU"),
        )

        assertEquals(CodecPriorities.TOP, result.priorities.getValue("opus/48000/2"))
        assertEquals(below(1), result.priorities.getValue("pcmu/8000/1"))
        assertTrue(result.unmatchedPreferences.isEmpty())
    }

    @Test
    fun `a preference the build does not contain is reported without disabling anything`() {
        // H264 is in CodecPreferences.DEFAULT and PJMEDIA_HAS_OPENH264_CODEC is 0. That is a
        // decision, not a defect — but it was invisible, which is how an H264-only peer came
        // to get no video at all with nothing in the log to say why.
        val result = CodecPriorities.assign(
            available = listOf("VP8/102", "VP9/106"),
            preferred = listOf("VP8", "H264"),
        )

        assertEquals(listOf("H264"), result.unmatchedPreferences)
        assertEquals(CodecPriorities.TOP, result.priorities.getValue("VP8/102"))
        assertFalse(result.wouldDisableEverything)
    }

    @Test
    fun `two codecs with one name get distinct priorities, in the order the registry gave them`() {
        // libvpx and Android's MediaCodec both register VP8, and both matched the one
        // preference at the same number. pjmedia orders equal priorities with an unstable
        // selection sort, so which VP8 led the offer changed every time an account was
        // saved — and on the TC15 the MediaCodec one cannot decode ("Decoder failed to get
        // input Buffer"), so half the video calls showed a black far end (2026-09-11).
        val result = CodecPriorities.assign(
            available = listOf("VP8/102", "H264/99", "VP8/103", "VP9/106"),
            preferred = listOf("VP8", "H264"),
        )

        assertEquals(CodecPriorities.TOP, result.priorities.getValue("VP8/102"))
        assertEquals(below(1), result.priorities.getValue("VP8/103"))
        assertEquals(below(2), result.priorities.getValue("H264/99"))
        assertEquals(CodecPriorities.DISABLED, result.priorities.getValue("VP9/106"))
        assertEquals(
            result.priorities.filterValues { it > 0 }.size,
            result.priorities.filterValues { it > 0 }.values.toSet().size,
            "every enabled codec has its own priority",
        )
    }

    @Test
    fun `an empty registry is not treated as a disabled endpoint`() {
        // Nothing registered is a build defect the audit reports; it is not this rule's
        // business, and flagging it here would raise the alarm twice for one fault.
        val result = CodecPriorities.assign(available = emptyList(), preferred = listOf("PCMU"))

        assertFalse(result.wouldDisableEverything)
        assertTrue(result.priorities.isEmpty())
    }

    @Test
    fun `no preferences at all leaves the registry alone`() {
        // An audio-only account has an empty VIDEO list, and that must mean "offer no video",
        // which the caller expresses by not applying video priorities — not by this function
        // inventing a disable pass that would also strip another account's video.
        val result = CodecPriorities.assign(available = registry, preferred = emptyList())

        assertTrue(result.priorities.isEmpty())
        assertFalse(result.wouldDisableEverything, "an empty preference list is not a fault")
    }

    @Test
    fun `MediaCodec's VP8 outranks every priority assign can hand out`() {
        // Two VP8 implementations answer to one account preference — pjmedia's MediaCodec
        // entry and libvpx's — so `assign` gives them the same number and the winner becomes
        // whichever pjmedia registered first. That is not a choice. The adapter breaks the
        // tie by writing MEDIACODEC_VP8 afterwards, which only settles it if the number is
        // strictly above anything assign can produce, including the first preference at TOP.
        val video = listOf("VP8/103", "VP8/102", "H264/99")
        val assigned = CodecPriorities.assign(available = video, preferred = listOf("VP8", "H264"))

        val highestAssignable = assigned.priorities.values.maxOrNull() ?: CodecPriorities.TOP
        assertTrue(
            CodecPriorities.MEDIACODEC_VP8 > highestAssignable,
            "MEDIACODEC_VP8 (${CodecPriorities.MEDIACODEC_VP8}) must beat the highest " +
                "rank assign produced ($highestAssignable), or the hardware path is chosen " +
                "by registration order rather than by us",
        )
        assertTrue(
            CodecPriorities.MEDIACODEC_VP8 > CodecPriorities.TOP,
            "a first preference sits at TOP, so the tiebreak must sit above it",
        )
    }

    @Test
    fun `the tiebreak survives the rewrite pjmedia performs on a leading 255`() {
        // The assertion this replaces pinned MEDIACODEC_VP8 to 255 and called it "the one
        // usable number above TOP". It is not usable: 255 is precisely the value pjmedia's
        // `sort_codecs` rewrites, and it rewrites it to 254 -- which was TOP. So the tiebreak
        // landed back on the number it was meant to beat, and an account asking for H264
        // before VP8 got a tie between H264 and the hardware VP8. The M23's audit read
        // `VP8/103@254, H264/99@254` (2026-09-27) and the offer that came out of it put
        // libvpx's VP8 first, which cost a resumed mesh leg every decoded frame.
        //
        // So the rule is not "inside 0..255", it is "strictly below the value that gets
        // rewritten", and it has to hold for TOP as well as for the tiebreak.
        assertTrue(
            CodecPriorities.MEDIACODEC_VP8 < REWRITTEN_BY_PJMEDIA,
            "MEDIACODEC_VP8 (${CodecPriorities.MEDIACODEC_VP8}) is rewritten by sort_codecs, " +
                "which lands it back on ${REWRITTEN_BY_PJMEDIA - 1}",
        )
        assertTrue(
            CodecPriorities.TOP < CodecPriorities.MEDIACODEC_VP8,
            "TOP (${CodecPriorities.TOP}) must leave the tiebreak a number of its own",
        )
        assertTrue(
            CodecPriorities.MEDIACODEC_VP8 > CodecPriorities.DISABLED,
            "the hardware VP8 must never be written as the disabled value",
        )
    }

    @Test
    fun `the hardware VP8 wins however the account orders its video preferences`() {
        // The case the M23 was in and the M14 was not. Both handsets ran the same build; the
        // only difference was the account's list, and that was enough to decide whether the
        // hardware VP8 led the offer. Both orders are asserted here, because one of them
        // passing is what hid this.
        listOf(listOf("VP8", "H264"), listOf("H264", "VP8")).forEach { preferred ->
            val assigned = CodecPriorities.assign(
                available = listOf("VP8/103", "VP8/102", "H264/99"),
                preferred = preferred,
            )
            val highest = assigned.priorities.values.max()
            assertTrue(
                CodecPriorities.MEDIACODEC_VP8 > highest,
                "with preferences $preferred the tiebreak (${CodecPriorities.MEDIACODEC_VP8}) " +
                    "does not beat $highest, so the offer's leading video format is decided " +
                    "by pjmedia's registration order rather than by us",
            )
        }
    }

    @Test
    fun `the other VP8 is named for switching off, so an offer carries VP8 once`() {
        // What ranking alone could not do. Both entries stayed in the offer, and the receive
        // payload type comes from the first offered format while the transmit one comes from
        // the answer -- so an answer naming the other number left a stream whose depacketiser
        // dropped every packet. Measured on an M23 resuming a three-party mesh leg, 2026-09-27:
        // RTP in at 19-90 pkt/s with zero loss, jitter buffer empty, decode 0.0.
        val registry = listOf("VP8/103", "VP8/102", "H264/99", "VP9/106")

        val off = CodecPriorities.duplicatesOf(keep = "VP8/103", available = registry)

        assertEquals(listOf("VP8/102"), off, "the duplicate VP8 was not named")
    }

    @Test
    fun `nothing is switched off when the preferred codec is not registered`() {
        // The fallback case, and the reason this is a list rather than a boolean: a build or a
        // handset where the MediaCodec probe registered nothing has only libvpx's VP8, and
        // switching that off would leave no VP8 at all.
        val off = CodecPriorities.duplicatesOf(
            keep = "VP8/103",
            available = listOf("VP8/102", "H264/99"),
        )

        assertTrue(off.isEmpty(), "libvpx's VP8 was switched off with nothing to replace it: $off")
    }

    @Test
    fun `a codec sharing no encoding name is left alone`() {
        // Matching is on the encoding, not on "everything else": H264 and VP9 are different
        // codecs and a peer can tell them apart, so they are not duplicates of anything.
        val off = CodecPriorities.duplicatesOf(
            keep = "VP8/103",
            available = listOf("VP8/103", "H264/99", "VP9/106"),
        )

        assertTrue(off.isEmpty(), "a codec that is not a second VP8 was named: $off")
    }

    /** Where `assign` puts the preference [steps] places down the account's list. */
    private fun below(steps: Int): Short = (CodecPriorities.TOP - steps).toShort()

    /**
     * The one priority pjmedia does not store as written.
     *
     * `sort_codecs` rewrites a leading 255 down to 254 after sorting, so 255 is not a rank a
     * caller can use to mean "above everything" -- see the test above.
     */
    private companion object {
        const val REWRITTEN_BY_PJMEDIA = 255
    }
}
