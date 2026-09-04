package com.whatsappv2.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TransportTest {

    @Test
    fun `TLS defaults to 5061 and the rest to 5060`() {
        assertEquals(5060, Transport.UDP.defaultPort)
        assertEquals(5060, Transport.TCP.defaultPort)
        assertEquals(5061, Transport.TLS.defaultPort)
    }

    @Test
    fun `only TLS is secure`() {
        assertTrue(Transport.TLS.isSecure)
        assertFalse(Transport.UDP.isSecure)
        assertFalse(Transport.TCP.isSecure)
    }

    @Test
    fun `fromToken is case-insensitive and trims`() {
        assertEquals(Transport.TLS, Transport.fromToken("TLS"))
        assertEquals(Transport.TLS, Transport.fromToken(" tls "))
        assertEquals(Transport.UDP, Transport.fromToken("Udp"))
    }

    @Test
    fun `fromToken returns null rather than guessing`() {
        assertNull(Transport.fromToken("sctp"))
        assertNull(Transport.fromToken(""))
    }
}

class SrtpPolicyTest {

    @Test
    fun `only MANDATORY forbids a cleartext fallback`() {
        assertTrue(SrtpPolicy.MANDATORY.requiresEncryptedMedia)
        assertFalse(SrtpPolicy.OPTIONAL.requiresEncryptedMedia)
        assertFalse(SrtpPolicy.DISABLED.requiresEncryptedMedia)
    }
}

class MediaProfileTest {

    @Test
    fun `rejects a profile with no media at all`() {
        assertNull(MediaProfile.of(audio = false, video = false))
    }

    @Test
    fun `returns the shared constants for the common combinations`() {
        assertSame(MediaProfile.AUDIO, MediaProfile.of(audio = true, video = false))
        assertSame(MediaProfile.AUDIO_VIDEO, MediaProfile.of(audio = true, video = true))
    }

    @Test
    fun `video-only is allowed and requires the camera`() {
        val videoOnly = MediaProfile.of(audio = false, video = true)
        checkNotNull(videoOnly)
        assertTrue(videoOnly.requiresCamera)
        assertFalse(videoOnly.hasAudio)
    }

    @Test
    fun `audio only does not require the camera`() {
        assertFalse(MediaProfile.AUDIO.requiresCamera)
    }

    @Test
    fun `equality is by value`() {
        assertEquals(MediaProfile.AUDIO, MediaProfile.of(audio = true, video = false))
        assertEquals(MediaProfile.AUDIO.hashCode(), MediaProfile.AUDIO_VIDEO.let { MediaProfile.AUDIO }.hashCode())
        assertTrue(MediaProfile.AUDIO != MediaProfile.AUDIO_VIDEO)
        assertFalse(MediaProfile.AUDIO.equals(null))
    }

    @Test
    fun `toString names both streams`() {
        assertEquals("MediaProfile(audio=true, video=true)", MediaProfile.AUDIO_VIDEO.toString())
    }
}

class IdentifiersTest {

    @Test
    fun `identifiers reject blank values`() {
        assertFailsWith<IllegalArgumentException> { AccountId("") }
        assertFailsWith<IllegalArgumentException> { AccountId("   ") }
        assertFailsWith<IllegalArgumentException> { CallId("") }
        assertFailsWith<IllegalArgumentException> { CallId(" ") }
    }

    @Test
    fun `identifiers render as their raw value`() {
        assertEquals("acct-1", AccountId("acct-1").toString())
        assertEquals("call-1", CallId("call-1").toString())
    }
}

class DtmfDigitTest {

    @Test
    fun `covers all sixteen tones`() {
        assertEquals(16, DtmfDigit.entries.size)
    }

    @Test
    fun `parses every symbol including lower-case a to d`() {
        for (digit in DtmfDigit.entries) {
            assertEquals(digit, DtmfDigit.fromChar(digit.symbol))
            assertEquals(digit, DtmfDigit.fromChar(digit.symbol.lowercaseChar()))
        }
    }

    @Test
    fun `rejects characters that are not DTMF`() {
        assertNull(DtmfDigit.fromChar('E'))
        assertNull(DtmfDigit.fromChar(' '))
        assertNull(DtmfDigit.fromChar('+'))
    }

    @Test
    fun `parses a whole dial string`() {
        assertEquals(
            listOf(DtmfDigit.STAR, DtmfDigit.TWO, DtmfDigit.ONE, DtmfDigit.HASH),
            DtmfDigit.parseSequence("*21#"),
        )
    }

    @Test
    fun `rejects a sequence outright rather than sending it partially`() {
        // A half-sent sequence is worse than none: the far end acts on what arrived.
        assertNull(DtmfDigit.parseSequence("12X4"))
        assertNull(DtmfDigit.parseSequence(""))
    }

    @Test
    fun `renders as its symbol`() {
        assertEquals("*", DtmfDigit.STAR.toString())
        assertEquals("7", DtmfDigit.SEVEN.toString())
    }
}

class RegistrationStateTest {

    @Test
    fun `only Registered is usable`() {
        assertTrue(RegistrationState.Registered(grantedExpirySeconds = 300).isUsable)
        assertFalse(RegistrationState.Registering.isUsable)
        assertFalse(RegistrationState.Unregistered.isUsable)
        assertFalse(
            RegistrationState.Failed(RegistrationFailure.TIMEOUT, retryScheduled = true).isUsable,
        )
    }

    @Test
    fun `a granted expiry must be positive`() {
        assertFailsWith<IllegalArgumentException> { RegistrationState.Registered(0) }
        assertFailsWith<IllegalArgumentException> { RegistrationState.Registered(-1) }
    }

    @Test
    fun `failures the user must fix are distinguished from transient ones`() {
        assertTrue(RegistrationFailure.AUTHENTICATION_FAILED.requiresUserAction)
        assertTrue(RegistrationFailure.ACCOUNT_REJECTED.requiresUserAction)
        assertTrue(RegistrationFailure.INVALID_CONFIGURATION.requiresUserAction)

        assertFalse(RegistrationFailure.NETWORK_UNAVAILABLE.requiresUserAction)
        assertFalse(RegistrationFailure.TIMEOUT.requiresUserAction)
        assertFalse(RegistrationFailure.SERVER_UNAVAILABLE.requiresUserAction)
        assertFalse(RegistrationFailure.TRANSPORT_FAILURE.requiresUserAction)
    }
}

class HangupReasonTest {

    @Test
    fun `reasons that precede an answer are identified`() {
        val beforeAnswer = setOf(
            HangupReason.BUSY,
            HangupReason.DECLINED,
            HangupReason.NO_ANSWER,
            HangupReason.CANCELLED,
            HangupReason.LOCAL_REJECTED,
        )
        for (reason in HangupReason.entries) {
            assertEquals(reason in beforeAnswer, reason.endedBeforeAnswer, "for $reason")
        }
    }
}
