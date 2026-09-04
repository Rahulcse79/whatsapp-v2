package com.whatsappv2.domain.model

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodecPreferencesTest {

    @Test
    fun `at least one audio codec is required`() {
        assertFailsWith<IllegalArgumentException> {
            CodecPreferences(audio = emptyList(), video = emptyList())
        }
    }

    @Test
    fun `duplicate codecs are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CodecPreferences(audio = listOf(AudioCodec.OPUS, AudioCodec.OPUS), video = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            CodecPreferences(
                audio = listOf(AudioCodec.OPUS),
                video = listOf(VideoCodec.VP8, VideoCodec.VP8),
            )
        }
    }

    @Test
    fun `the default offers a wideband codec first`() {
        assertEquals(AudioCodec.OPUS, CodecPreferences.DEFAULT.audio.first())
        assertTrue(CodecPreferences.DEFAULT.audio.first().isWideband)
    }

    @Test
    fun `supportsVideo reflects the video list`() {
        assertTrue(CodecPreferences.DEFAULT.supportsVideo)
        assertFalse(CodecPreferences.AUDIO_ONLY.supportsVideo)
    }

    @Test
    fun `audio only keeps the audio order of the default`() {
        assertEquals(CodecPreferences.DEFAULT.audio, CodecPreferences.AUDIO_ONLY.audio)
        assertTrue(CodecPreferences.AUDIO_ONLY.video.isEmpty())
    }

    @Test
    fun `payload names match what goes into SDP`() {
        assertEquals("opus", AudioCodec.OPUS.payloadName)
        assertEquals("PCMU", AudioCodec.PCMU.payloadName)
        assertEquals("VP8", VideoCodec.VP8.payloadName)
        assertEquals("H264", VideoCodec.H264.payloadName)
    }

    @Test
    fun `only the wideband codecs are marked wideband`() {
        val wideband = AudioCodec.entries.filter { it.isWideband }.toSet()
        assertEquals(setOf(AudioCodec.OPUS, AudioCodec.G722), wideband)
    }
}

class NatPolicyTest {

    @Test
    fun `keepalive must be within bounds`() {
        assertFailsWith<IllegalArgumentException> {
            NatPolicy(iceEnabled = true, stunEnabled = true, keepaliveIntervalSeconds = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            NatPolicy(iceEnabled = true, stunEnabled = true, keepaliveIntervalSeconds = 10_000)
        }
    }

    @Test
    fun `the bounds themselves are accepted`() {
        NatPolicy(iceEnabled = false, stunEnabled = false, keepaliveIntervalSeconds = NatPolicy.MIN_KEEPALIVE_SECONDS)
        NatPolicy(iceEnabled = false, stunEnabled = false, keepaliveIntervalSeconds = NatPolicy.MAX_KEEPALIVE_SECONDS)
    }

    @Test
    fun `the default enables ICE and STUN`() {
        assertTrue(NatPolicy.DEFAULT.iceEnabled)
        assertTrue(NatPolicy.DEFAULT.stunEnabled)
        assertEquals(NatPolicy.DEFAULT_KEEPALIVE_SECONDS, NatPolicy.DEFAULT.keepaliveIntervalSeconds)
    }
}

class TransferTypeTest {

    @Test
    fun `both transfer kinds exist`() {
        assertEquals(setOf(TransferType.BLIND, TransferType.ATTENDED), TransferType.entries.toSet())
    }
}

class SipSchemeTest {

    @Test
    fun `fromToken is case-insensitive`() {
        assertEquals(SipScheme.SIP, SipScheme.fromToken("SIP"))
        assertEquals(SipScheme.SIPS, SipScheme.fromToken("sips"))
    }

    @Test
    fun `an unknown scheme is not guessed`() {
        assertEquals(null, SipScheme.fromToken("http"))
    }

    @Test
    fun `sips defaults to the TLS port`() {
        assertEquals(5060, SipScheme.SIP.defaultPort)
        assertEquals(5061, SipScheme.SIPS.defaultPort)
    }
}

class TurnConfigurationTest {

    @Test
    fun `a TURN server requires a username`() {
        val server = requireNotNull(HostPort.parse("turn.example.com:3478").getOrNull())
        assertFailsWith<IllegalArgumentException> {
            TurnConfiguration(server, username = "  ", password = Secret("p"))
        }
    }
}
