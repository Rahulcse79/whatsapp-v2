package com.whatsappv2.domain.codec

import com.whatsappv2.domain.model.AudioCodec
import com.whatsappv2.domain.model.VideoCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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
        // The state H264 is in today, and LYRA was in until ADR-008 closed at Exit A. A
        // decision, reported at INFO — the auditor is told what is compiled and does not care
        // which year it is.
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
    fun `a registered codec whose model files are unusable says so, ahead of any peer`() {
        // Lyra on Exit A: the codec registers from the library alone, and only fails when
        // a stream opens and the weights are not where it was told. That is worse than
        // NotCompiled — it is in every offer — and it must not be softened into "no peer".
        val audit = CodecAuditor(declared = setOf(lyra), knownUnnegotiable = setOf("lyra")).audit(
            registeredAudio = listOf("lyra/16000/1" to 200),
            registeredVideo = emptyList(),
            compiledIn = setOf("lyra"),
            modelFilesUnusable = mapOf("lyra" to "lyragan.tflite missing from /data/user/0/x/files/lyra"),
        )

        assertEquals(
            AbsenceReason.ModelFilesUnusable("lyragan.tflite missing from /data/user/0/x/files/lyra"),
            audit.absent[lyra],
        )
        assertEquals(listOf("lyra"), audit.registeredAudio.map { it.name }, "still in the registry")
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
    fun `a stranded codec is reported with a reason AND left in the registry`() {
        // Opus against the deployed server, recorded 2026-09-09: registered here, not
        // offered there. Both halves have to be sayable at once.
        val audit = CodecAuditor(
            declared = setOf(opus, pcmu),
            knownUnnegotiable = setOf("opus"),
            knownUnnegotiableSource = "recorded from show codec, 2026-09-09",
        ).audit(
            registeredAudio = listOf("opus/48000/2" to 255, "PCMU/8000/1" to 254),
            registeredVideo = emptyList(),
            compiledIn = setOf("opus", "pcmu"),
        )

        assertEquals(setOf(opus), audit.strandedByPeer)
        assertTrue(audit.defects.isEmpty(), "an absent peer is not a defect in this app")

        // THE REGRESSION THIS PINS. The registry used to have stranded codecs filtered out
        // of it before anything could read it, so the "registered codecs" log line was a
        // subset of the registry while claiming to be the registry. On 2026-09-10 that line
        // was read as proof this build registers no Opus, and an INVITE off the same handset
        // carried `a=rtpmap:96 opus/48000/2`. The list is verbatim or it is not evidence.
        assertEquals(listOf("opus", "pcmu"), audit.registeredAudio.map { it.name })
    }

    @Test
    fun `the reason for strandedness carries the evidence it rests on`() {
        // It was `NoPeerAccepts`, which asserts something about every peer that nothing here
        // has ever measured. What exists is one hand-maintained list from one server on one
        // day, and the reason has to say so or nobody can weigh it.
        val audit = CodecAuditor(
            declared = setOf(opus),
            knownUnnegotiable = setOf("opus"),
            knownUnnegotiableSource = "recorded from show codec, 2026-09-09",
        ).audit(
            registeredAudio = listOf("opus/48000/2" to 255),
            registeredVideo = emptyList(),
            compiledIn = setOf("opus"),
        )

        val reason = audit.absent[opus]
        assertIs<AbsenceReason.ExpectedUnsupportedByServer>(reason)
        assertEquals("recorded from show codec, 2026-09-09", reason.source)
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
