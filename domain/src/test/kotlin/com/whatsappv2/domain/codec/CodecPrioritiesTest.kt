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

        assertEquals(255.toShort(), result.priorities.getValue("opus/48000/2"))
        assertEquals(254.toShort(), result.priorities.getValue("PCMU/8000/1"))
        assertEquals(253.toShort(), result.priorities.getValue("PCMA/8000/1"))
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

        assertEquals(255.toShort(), result.priorities.getValue("PCMU/8000/1"))
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

        assertEquals(255.toShort(), result.priorities.getValue("opus/48000/2"))
        assertEquals(254.toShort(), result.priorities.getValue("pcmu/8000/1"))
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
        assertEquals(255.toShort(), result.priorities.getValue("VP8/102"))
        assertFalse(result.wouldDisableEverything)
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
}
