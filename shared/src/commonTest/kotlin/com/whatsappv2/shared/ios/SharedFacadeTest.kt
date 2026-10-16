package com.whatsappv2.shared.ios

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The facade is the one file in this module whose whole job is to be correct at a
 * boundary Swift cannot type-check, so it gets the most direct tests in the module.
 *
 * These run on the JVM **and** on an iOS simulator — that is the point of putting the
 * facade in `commonMain`. A test that passes here and fails there would be a genuine
 * Kotlin/Native difference, which is exactly what is worth finding out about early.
 *
 * No comma appears in any test name below. Kotlin/Native turns a test name into an
 * Objective-C selector and rejects one containing a comma — a rule CI found the hard
 * way across eighteen names in this module on 2026-10-14.
 */
class SharedFacadeTest {

    // ------------------------------------------------------------------ dialling

    @Test
    fun `a bare extension is completed against the account domain and port`() {
        assertEquals(
            "sip:1004@192.168.7.14:5070",
            SharedFacade.resolveDialledTarget("1004", "192.168.7.14", 5070),
        )
    }

    @Test
    fun `the port is written even when it is the transport default`() {
        // The whole point of the rule: "the port you set is the port calls go to" has no
        // exception to remember and can be checked by reading one log line.
        val resolved = SharedFacade.resolveDialledTarget("1004", "example.com", 5060)
        assertNotNull(resolved)
        assertContains(resolved, ":5060")
    }

    @Test
    fun `a full SIP URI is passed through rather than re-completed`() {
        assertEquals(
            "sip:bob@other.example:5080",
            SharedFacade.resolveDialledTarget("sip:bob@other.example:5080", "example.com", 5060),
        )
    }

    @Test
    fun `an empty dial string resolves to nothing`() {
        assertNull(SharedFacade.resolveDialledTarget("", "example.com", 5060))
    }

    // ------------------------------------------------------------------ URIs

    @Test
    fun `a well formed URI validates and a bare address does not`() {
        assertTrue(SharedFacade.isValidSipUri("sip:alice@example.com"))
        // Rejected on purpose: guessing a scheme is how a sips account silently becomes
        // an unencrypted sip one.
        assertFalse(SharedFacade.isValidSipUri("alice@example.com"))
    }

    @Test
    fun `the label of a URI is its user part`() {
        assertEquals("7001", SharedFacade.labelForUri("sip:7001@192.168.80.145"))
    }

    @Test
    fun `a URI with no user part labels as its host`() {
        assertEquals("conference.example.com", SharedFacade.labelForUri("sip:conference.example.com"))
    }

    @Test
    fun `an unparseable label falls back to the input rather than throwing`() {
        assertEquals("not a uri", SharedFacade.labelForUri("not a uri"))
    }

    // ------------------------------------------------------------------ backoff

    @Test
    fun `the retry window grows with the attempt and then stops`() {
        val first = SharedFacade.registrationRetryWindowSeconds(0)
        val later = SharedFacade.registrationRetryWindowSeconds(4)
        val muchLater = SharedFacade.registrationRetryWindowSeconds(40)
        assertTrue(later > first, "window should grow: $first -> $later")
        assertEquals(muchLater, SharedFacade.registrationRetryWindowSeconds(20),
                     "the window must reach a ceiling and stay there")
    }

    @Test
    fun `a seeded retry delay is reproducible`() {
        assertEquals(
            SharedFacade.registrationRetryDelaySeconds(attempt = 3, seed = 42),
            SharedFacade.registrationRetryDelaySeconds(attempt = 3, seed = 42),
        )
    }

    @Test
    fun `a server Retry-After is honoured rather than replaced by the backoff`() {
        val delay = SharedFacade.registrationRetryDelaySeconds(
            attempt = 0, retryAfterSeconds = 120, seed = 7,
        )
        // At least what the server asked for, plus only a small spread on top - the
        // point of the jitter is to break simultaneity and not to keep the client away
        // materially longer than it was told to stay.
        assertTrue(delay >= 120.0, "must not retry before the server said: $delay")
        assertTrue(delay < 180.0, "jitter must stay small beside the request: $delay")
    }

    @Test
    fun `every retry delay is positive`() {
        for (attempt in 0..10) {
            assertTrue(
                SharedFacade.registrationRetryDelaySeconds(attempt, seed = attempt + 1) > 0.0,
                "attempt $attempt produced a non-positive delay",
            )
        }
    }

    // ------------------------------------------------------------------ validation

    private fun validate(
        label: String = "Desk",
        username: String = "1001",
        password: String = "secret",
        domain: String = "192.168.7.14",
        port: String = "5070",
        transport: String = "udp",
        expiry: String = "180",
    ) = SharedFacade.validateAccount(
        accountId = "acc-1",
        label = label,
        username = username,
        authUsername = "",
        password = password,
        displayName = "",
        domain = domain,
        registrar = "",
        outboundProxy = "",
        port = port,
        transportToken = transport,
        registrationExpirySeconds = expiry,
        stunServer = "",
    )

    @Test
    fun `a complete account validates`() {
        val report = validate()
        assertTrue(report.isValid, "violations: ${report.violations}")
        assertTrue(report.violations.isEmpty())
    }

    @Test
    fun `a missing label is reported against the label field`() {
        val report = validate(label = "")
        assertFalse(report.isValid)
        assertTrue(report.violations.any { it.field == "LABEL" },
                   "expected a LABEL violation in ${report.violations}")
    }

    @Test
    fun `a port that is not a number is reported against the port field`() {
        val report = validate(port = "not-a-port")
        assertFalse(report.isValid)
        assertTrue(report.violations.any { it.field == "PORT" })
    }

    @Test
    fun `a port outside the legal range is rejected`() {
        val report = validate(port = "70000")
        assertFalse(report.isValid)
        assertTrue(report.violations.any { it.field == "PORT" })
    }

    @Test
    fun `every violation carries a message a person could read`() {
        val report = validate(label = "", username = "", password = "")
        assertFalse(report.isValid)
        assertTrue(report.violations.isNotEmpty())
        report.violations.forEach {
            assertTrue(it.message.isNotBlank(), "empty message for ${it.field}")
            assertFalse(it.message.contains("AccountViolation"),
                        "a data class toString leaked into the UI: ${it.message}")
        }
    }

    // ------------------------------------------------------------------ codecs

    @Test
    fun `the default codec orders are not empty and name real codecs`() {
        val audio = SharedFacade.defaultAudioCodecNames()
        val video = SharedFacade.defaultVideoCodecNames()
        assertTrue(audio.isNotEmpty())
        assertTrue(video.isNotEmpty())
        assertTrue(SharedFacade.allAudioCodecNames().containsAll(audio))
        assertTrue(SharedFacade.allVideoCodecNames().containsAll(video))
    }

    @Test
    fun `VP8 is among the known video codecs`() {
        // Not a style assertion: the deployed FreeSWITCH offers VP8 and VP9 from CORE_VPX
        // and no H.264 at all, so a build whose video list lost VP8 cannot make a video
        // call against it.
        assertTrue(SharedFacade.allVideoCodecNames().any { it.equals("VP8", ignoreCase = true) })
    }

    // ------------------------------------------------------------------ video

    @Test
    fun `the frame rate options are ordered and within the supported range`() {
        val options = SharedFacade.videoFrameRateOptions()
        assertTrue(options.isNotEmpty())
        assertEquals(options.sorted(), options, "options must be lowest first")
        assertTrue(options.all { it in 5..30 }, "unexpected frame rate in $options")
    }
}
