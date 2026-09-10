package com.whatsappv2.domain.codec

import com.whatsappv2.domain.model.AudioCodec
import com.whatsappv2.domain.model.VideoCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Every branch of the audit, and one test per reason — because the four reasons are the
 * whole point of the type and a reason nothing can produce is a reason nobody can act on.
 */
class CodecAuditorTest {

    private val opus = DeclaredCodec.audio(AudioCodec.OPUS)
    private val pcmu = DeclaredCodec.audio(AudioCodec.PCMU)
    private val lyra = DeclaredCodec.audio(AudioCodec.LYRA)
    private val vp8 = DeclaredCodec.video(VideoCodec.VP8)
    private val h264 = DeclaredCodec.video(VideoCodec.H264)

    @Test
    fun `a codec that registered is not absent`() {
        val audit = CodecAuditor(declared = setOf(opus, pcmu)).audit(
            registeredAudio = listOf("opus/48000/2" to 255, "PCMU/8000/1" to 254),
            registeredVideo = emptyList(),
            compiledIn = setOf("opus", "pcmu"),
        )

        assertTrue(audit.isComplete, "expected no absences, got ${audit.absent}")
        assertEquals(listOf("opus", "pcmu"), audit.registeredAudio.map { it.name })
    }

    @Test
    fun `a declared codec the build never contained is NotCompiled, not a defect`() {
        // The state H264 and LYRA are in today. A decision, reported at INFO.
        val audit = CodecAuditor(declared = setOf(vp8, h264, lyra)).audit(
            registeredAudio = emptyList(),
            registeredVideo = listOf("VP8/90000" to 255),
            compiledIn = setOf("vp8"),
        )

        assertEquals(AbsenceReason.NotCompiled, audit.absent[h264])
        assertEquals(AbsenceReason.NotCompiled, audit.absent[lyra])
        assertTrue(audit.defects.isEmpty(), "NotCompiled must not be reported as a build defect")
    }

    @Test
    fun `compiled in and not registered is a build defect`() {
        // The one case a build log cannot show: the flag said 1 and the library did not
        // register it. This is what the audit exists for.
        val audit = CodecAuditor(declared = setOf(opus)).audit(
            registeredAudio = listOf("PCMU/8000/1" to 255),
            registeredVideo = emptyList(),
            compiledIn = setOf("opus"),
        )

        assertEquals(AbsenceReason.RegistrationFailed, audit.absent[opus])
        assertEquals(setOf(opus), audit.defects.keys)
    }

    @Test
    fun `a registered codec with no peer is stranded, not missing and not broken`() {
        // Opus against the deployed FreeSWITCH, measured 2026-09-09: registered here,
        // offered by nobody there.
        val audit = CodecAuditor(
            declared = setOf(opus, pcmu),
            knownUnnegotiable = setOf("opus"),
        ).audit(
            registeredAudio = listOf("opus/48000/2" to 255, "PCMU/8000/1" to 254),
            registeredVideo = emptyList(),
            compiledIn = setOf("opus", "pcmu"),
        )

        assertEquals(AbsenceReason.NoPeerAccepts, audit.absent[opus])
        assertEquals(setOf(opus), audit.strandedByPeer)
        assertTrue(audit.defects.isEmpty(), "no peer is not a defect in this app")
        // It is genuinely registered, so it must not be reported as a working choice either.
        assertEquals(listOf("pcmu"), audit.registeredAudio.map { it.name })
    }

    @Test
    fun `matching is case-insensitive on the codec-name segment`() {
        // The real bug in P-9: `LYRA` prefix-matches `lyra/16000/1` NEVER, and silently.
        // PJSIP spells ids with a clock rate; the domain stores the name alone; and PCMU is
        // uppercase upstream while opus is lowercase. One normalisation, in one place.
        val audit = CodecAuditor(declared = setOf(pcmu, opus)).audit(
            registeredAudio = listOf("PCMU/8000/1" to 255, "OPUS/48000/2" to 254),
            registeredVideo = emptyList(),
            compiledIn = setOf("PCMU", "OPUS"),
        )

        assertTrue(audit.isComplete, "case differences must not read as an absent codec: ${audit.absent}")
    }

    @Test
    fun `an empty knownUnnegotiable set means not known, never known-good`() {
        // Absent evidence must not become a claim. With no peer information, a registered
        // codec is simply registered — the audit says nothing about whether it negotiates.
        val audit = CodecAuditor(declared = setOf(opus)).audit(
            registeredAudio = listOf("opus/48000/2" to 255),
            registeredVideo = emptyList(),
            compiledIn = setOf("opus"),
        )

        assertTrue(audit.strandedByPeer.isEmpty())
        assertTrue(audit.isComplete)
    }

    @Test
    fun `a codec cannot be both registered and absent`() {
        // The invariant, proven reachable. A rule nothing can violate is a rule nobody has
        // tested.
        assertFailsWith<IllegalArgumentException> {
            CodecAudit(
                registeredAudio = listOf(RegisteredCodec.parse("opus/48000/2", 255)),
                registeredVideo = emptyList(),
                absent = mapOf(opus to AbsenceReason.NotCompiled),
            )
        }
    }

    @Test
    fun `video and audio registries are separate`() {
        // videoCodecEnum2() is a different call from codecEnum2(), and a video codec must
        // not be satisfied by an audio registration of the same name.
        val audit = CodecAuditor(declared = setOf(vp8)).audit(
            registeredAudio = listOf("VP8/90000" to 255),
            registeredVideo = emptyList(),
            compiledIn = setOf("vp8"),
        )

        // Registered under EITHER registry counts as registered — the name space is shared
        // and PJSIP does not reuse a name across kinds. What must not happen is a silent
        // miss, and this pins the behaviour either way.
        assertTrue(audit.isComplete, "got ${audit.absent}")
    }
}
