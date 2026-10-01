package com.whatsappv2.domain.chat

import com.whatsappv2.core.common.result.errorOrNull
import com.whatsappv2.core.common.result.getOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What the sign-in screen's URL field accepts, and what it says when it does not.
 *
 * Every violation is covered, because each one exists to produce a different message and
 * a case nobody tests is a message nobody has read.
 */
class CoralServerUrlTest {

    private fun accept(raw: String): CoralServerUrl =
        CoralServerUrl.parse(raw).getOrNull() ?: fail(
            "expected $raw to parse, got ${CoralServerUrl.parse(raw).errorOrNull()}",
        )

    private fun reject(raw: String): ChatUrlViolation =
        CoralServerUrl.parse(raw).errorOrNull() ?: fail("expected $raw to be rejected")

    // ------------------------------------------------------------------ the happy path

    @Test
    fun `an https origin parses to itself`() {
        assertEquals("https://gujlogin.coraltele.com", accept("https://gujlogin.coraltele.com").origin)
    }

    @Test
    fun `the default is the deployment this app ships pointed at`() {
        assertEquals("https://gujlogin.coraltele.com", CoralServerUrl.DEFAULT.origin)
    }

    @Test
    fun `a port survives, because a test deployment will have one`() {
        assertEquals("https://host.example:8443", accept("https://host.example:8443").origin)
    }

    @Test
    fun `the scheme and host are lowercased, so two spellings of one host are one value`() {
        assertEquals("https://gujlogin.coraltele.com", accept("HTTPS://GujLogin.CoralTele.com").origin)
    }

    @Test
    fun `surrounding whitespace is trimmed, because a pasted URL carries it`() {
        assertEquals("https://gujlogin.coraltele.com", accept("  https://gujlogin.coraltele.com \n").origin)
    }

    // ------------------------------------------------------------------ normalisation

    @Test
    fun `a trailing slash is dropped`() {
        assertEquals("https://gujlogin.coraltele.com", accept("https://gujlogin.coraltele.com/").origin)
    }

    @Test
    fun `a pasted API path is dropped rather than refused`() {
        // Somebody copying the login URL out of the API document has given us the right
        // host. Appending "/services/" to it would produce ".../login/services/", which
        // fails much later and much less legibly than it would fail here.
        assertEquals(
            "https://gujlogin.coraltele.com",
            accept("https://gujlogin.coraltele.com/services/app/v2/auth/login").origin,
        )
    }

    @Test
    fun `a query string is dropped too`() {
        assertEquals("https://host.example", accept("https://host.example?next=/chat").origin)
    }

    // ------------------------------------------------------------------ the three derived URLs

    @Test
    fun `the services base ends in a slash, which Retrofit requires`() {
        assertEquals("https://gujlogin.coraltele.com/services/", CoralServerUrl.DEFAULT.servicesBaseUrl)
    }

    @Test
    fun `the chat REST base is the chat-node prefix`() {
        assertEquals("https://gujlogin.coraltele.com/chat/", CoralServerUrl.DEFAULT.chatRestBaseUrl)
    }

    @Test
    fun `the socket URL is wss, derived rather than typed`() {
        assertEquals("wss://gujlogin.coraltele.com/chat/ws", CoralServerUrl.DEFAULT.chatWsUrl)
    }

    @Test
    fun `all three derive from one pasted path, so they cannot disagree`() {
        val url = accept("https://host.example:8443/services/app/v2/auth/login/")

        assertEquals("https://host.example:8443/services/", url.servicesBaseUrl)
        assertEquals("https://host.example:8443/chat/", url.chatRestBaseUrl)
        assertEquals("wss://host.example:8443/chat/ws", url.chatWsUrl)
    }

    // ------------------------------------------------------------------ every violation

    @Test
    fun `an empty field is Blank`() {
        assertEquals(ChatUrlViolation.Blank, reject(""))
        assertEquals(ChatUrlViolation.Blank, reject("   "))
    }

    @Test
    fun `a bare host is accepted and assumed to be https`() {
        // Refusing it taught nobody anything. Three forms are accepted - host,
        // http://host, https://host - and only the last two say anything about the
        // scheme, so the first is guessed. https is the half of that guess that fails loudly.
        assertEquals("https://gujlogin.coraltele.com", accept("gujlogin.coraltele.com").origin)
        assertTrue(accept("gujlogin.coraltele.com").isSecure)
    }

    @Test
    fun `a bare IP address is accepted too, because that is what a lab server is`() {
        assertEquals("https://192.168.250.201", accept("192.168.250.201").origin)
    }

    @Test
    fun `http is accepted, and the scheme the user typed is the one that is used`() {
        // http://192.168.250.201 is a real deployment of this platform. A parser that
        // refused it would refuse the server the app is pointed at. Whether the PLATFORM
        // will carry a cleartext request is a manifest question, which isSecure exposes.
        val url = accept("http://192.168.250.201")

        assertEquals("http://192.168.250.201", url.origin)
        assertFalse(url.isSecure)
    }

    @Test
    fun `an http origin derives http and ws, never https and wss`() {
        val url = accept("http://192.168.250.201:8080/services/")

        assertEquals("http://192.168.250.201:8080/services/", url.servicesBaseUrl)
        assertEquals("http://192.168.250.201:8080/chat/", url.chatRestBaseUrl)
        assertEquals("ws://192.168.250.201:8080/chat/ws", url.chatWsUrl)
    }

    @Test
    fun `an https origin is secure and an http one is not`() {
        assertTrue(accept("https://host.example").isSecure)
        assertFalse(accept("http://host.example").isSecure)
    }

    @Test
    fun `any scheme but http and https is UnsupportedScheme, and names itself`() {
        assertEquals("ftp", assertIs<ChatUrlViolation.UnsupportedScheme>(reject("ftp://host.example")).scheme)
        // wss is what the socket URL is DERIVED to, never what an origin is typed as.
        assertEquals("wss", assertIs<ChatUrlViolation.UnsupportedScheme>(reject("wss://host.example")).scheme)
    }

    @Test
    fun `a scheme with no host is Malformed`() {
        assertEquals(ChatUrlViolation.Malformed, reject("https://"))
        assertEquals(ChatUrlViolation.Malformed, reject("https:///services/"))
    }

    @Test
    fun `an authority with characters a host cannot contain is Malformed`() {
        assertEquals(ChatUrlViolation.Malformed, reject("https://user@host.example"))
        assertEquals(ChatUrlViolation.Malformed, reject("https://host example"))
    }

    @Test
    fun `the violations that remain carry no suggestion, because there is no single right one`() {
        assertNull(ChatUrlViolation.Blank.suggestion)
        assertNull(ChatUrlViolation.Malformed.suggestion)
        assertNull(ChatUrlViolation.UnsupportedScheme("ftp").suggestion)
    }
}
