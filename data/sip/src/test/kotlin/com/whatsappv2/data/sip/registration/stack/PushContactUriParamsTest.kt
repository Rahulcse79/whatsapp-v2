package com.whatsappv2.data.sip.registration.stack

import com.whatsappv2.data.sip.registration.StackPushParameters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The RFC 8599 parameter string the registration `Contact` URI carries (ADR-004, Task 38).
 *
 * The `AccountConfig` it lands in is a pjsua2 type, which loads native code on class load
 * and so cannot be built on the JVM; the string is the part that can be pinned here. What
 * the pjsua2 field then does with it — inside the brackets, not after them — is
 * `pjsua_acc.c`'s job, and the reason the field is `contactUriParams`.
 */
class PushContactUriParamsTest {

    @Test
    fun `a real FCM token passes through unchanged, in RFC 8599 order`() {
        val token = "dXcQ3mZ9Tq2:APA91bFt4-Xn_9yQ0aM2pO6r8s1U3v5W7x9Y1z3A5b7C9d1E3f5G7h9I1j3K5l7M9n"
        val params = StackPushParameters(provider = "fcm", param = "1234567890", prid = token)

        assertEquals(
            ";pn-provider=fcm;pn-param=1234567890;pn-prid=$token" +
                ";pn-type=firebase;app-id=1234567890;pn-silent=1",
            params.toContactUriParams(),
        )
    }

    @Test
    fun `every parameter the working client sends is present, except the one we cannot know`() {
        // Pinned against a real capture (2026-10-14) of a client that the CoralUC IMS does
        // wake, registered to the same FreeSWITCH. The point of the test is the SET, not the
        // order: a server reads parameters by name, and this is what it will find.
        val params = StackPushParameters(provider = "fcm", param = "58415781151", prid = "tok")
        val emitted = params.toContactUriParams()
            .split(';').filter { it.isNotBlank() }.map { it.substringBefore('=') }.toSet()

        assertEquals(setOf("pn-provider", "pn-param", "pn-prid", "pn-type", "app-id", "pn-silent"), emitted)

        // app-imei is the deliberate omission. Android will not hand out an IMEI without a
        // privileged permission and a value we invented could only ever fail to match, so
        // it is absent until the server team supplies a real one. This asserts the absence
        // so that adding it is a decision somebody makes, not a detail that drifts in.
        assertFalse("app-imei" in emitted)
    }

    @Test
    fun `the RFC 3261 paramchar set is left alone`() {
        assertEquals("aZ09-_.!~*'()[]/:&+$", escapeUriParamValue("aZ09-_.!~*'()[]/:&+$"))
    }

    @Test
    fun `anything else is percent-encoded, so the Contact stays parseable`() {
        // A semicolon or a comma would end the parameter, a space the header, an equals
        // sign the name: each is what turns a token into a malformed REGISTER.
        assertEquals("a%3Bb%2Cc%20d%3De%3Ff%40g", escapeUriParamValue("a;b,c d=e?f@g"))
    }

    @Test
    fun `non-ASCII is encoded as UTF-8 bytes`() {
        assertEquals("%C3%A9", escapeUriParamValue("é"))
    }
}
