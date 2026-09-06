package com.whatsappv2.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The two pure model pieces Tasks 51 and 55 added.
 *
 * [MediaProfile]'s downgrade is Task 51's second done-when stated as a function: a device
 * that cannot capture places the call anyway. [DialledTarget] is the rule that makes
 * transferring to `1002` work exactly as calling `1002` does — one implementation, so the
 * two cannot disagree.
 */
class MediaAndTargetTest {

    // ================================================================ MediaProfile

    @Test
    fun `dropping video from a video call leaves audio`() {
        assertEquals(MediaProfile.AUDIO, MediaProfile.AUDIO_VIDEO.withoutVideo())
    }

    @Test
    fun `dropping video from an audio call is the same profile`() {
        assertSame(MediaProfile.AUDIO, MediaProfile.AUDIO.withoutVideo())
    }

    @Test
    fun `adding video to an audio call gives audio and video`() {
        assertEquals(MediaProfile.AUDIO_VIDEO, MediaProfile.AUDIO.withVideo())
        assertSame(MediaProfile.AUDIO_VIDEO, MediaProfile.AUDIO_VIDEO.withVideo())
    }

    @Test
    fun `a video call on a device with no camera becomes an audio call, not a failure`() {
        // Task 51: downgrade, never refuse. Somebody who declined the camera keeps a phone.
        assertEquals(
            MediaProfile.AUDIO,
            MediaProfile.AUDIO_VIDEO.downgradedWhenCameraUnavailable(cameraUsable = false),
        )
    }

    @Test
    fun `a video call with a camera is left alone`() {
        assertEquals(
            MediaProfile.AUDIO_VIDEO,
            MediaProfile.AUDIO_VIDEO.downgradedWhenCameraUnavailable(cameraUsable = true),
        )
    }

    @Test
    fun `an audio call is unaffected either way`() {
        assertEquals(MediaProfile.AUDIO, MediaProfile.AUDIO.downgradedWhenCameraUnavailable(false))
        assertEquals(MediaProfile.AUDIO, MediaProfile.AUDIO.downgradedWhenCameraUnavailable(true))
    }

    @Test
    fun `a video-only profile downgraded with no camera still has audio`() {
        val videoOnly = requireNotNull(MediaProfile.of(audio = false, video = true))

        // withoutVideo returns AUDIO rather than nothing: a call with no media is not a
        // call, and the factory refuses to build one.
        assertEquals(MediaProfile.AUDIO, videoOnly.downgradedWhenCameraUnavailable(false))
        assertTrue(videoOnly.requiresCamera)
    }

    // ================================================================ SrtpPolicy

    @Test
    fun `mandatory refuses a call whose media is not encrypted`() {
        // DoD 13, as a function of two values. The enforcement that matters happens after
        // negotiation, when the answer is in and the media is about to flow.
        assertFalse(SrtpPolicy.MANDATORY.permits(mediaEncrypted = false))
        assertTrue(SrtpPolicy.MANDATORY.permits(mediaEncrypted = true))
        assertTrue(SrtpPolicy.MANDATORY.requiresEncryptedMedia)
    }

    @Test
    fun `optional permits both, which is what optional means`() {
        assertTrue(SrtpPolicy.OPTIONAL.permits(mediaEncrypted = false))
        assertTrue(SrtpPolicy.OPTIONAL.permits(mediaEncrypted = true))
        assertFalse(SrtpPolicy.OPTIONAL.requiresEncryptedMedia)
    }

    @Test
    fun `disabled permits both, and never claims to require encryption`() {
        assertTrue(SrtpPolicy.DISABLED.permits(mediaEncrypted = false))
        assertTrue(SrtpPolicy.DISABLED.permits(mediaEncrypted = true))
        assertFalse(SrtpPolicy.DISABLED.requiresEncryptedMedia)
    }

    @Test
    fun `exactly one policy requires encryption, so a new one cannot be added silently`() {
        assertEquals(
            listOf(SrtpPolicy.MANDATORY),
            SrtpPolicy.entries.filter { it.requiresEncryptedMedia },
        )
    }

    // ================================================================ DialledTarget

    @Test
    fun `a bare extension is completed against the account domain`() {
        val target = DialledTarget.resolve("1002", "sip.example.com")

        assertEquals("sip:1002@sip.example.com", target?.render())
    }

    @Test
    fun `a full URI is parsed as written and not rewritten`() {
        val target = DialledTarget.resolve("sip:1002@other.example.com", "sip.example.com")

        assertEquals("sip:1002@other.example.com", target?.render())
    }

    @Test
    fun `a user at host with no scheme gets one`() {
        val target = DialledTarget.resolve("1002@other.example.com", "sip.example.com")

        assertEquals("sip:1002@other.example.com", target?.render())
    }

    @Test
    fun `a sips URI keeps its scheme`() {
        val target = DialledTarget.resolve("sips:1002@secure.example.com", "sip.example.com")

        assertEquals("sips:1002@secure.example.com", target?.render())
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("sip:1002@sip.example.com", DialledTarget.resolve("  1002  ", "sip.example.com")?.render())
    }

    @Test
    fun `blank input resolves to nothing`() {
        assertNull(DialledTarget.resolve("", "sip.example.com"))
        assertNull(DialledTarget.resolve("   ", "sip.example.com"))
    }

    @Test
    fun `an unparseable address resolves to nothing rather than a broken URI`() {
        assertNull(DialledTarget.resolve("sip:@@@", "sip.example.com"))
    }
}
