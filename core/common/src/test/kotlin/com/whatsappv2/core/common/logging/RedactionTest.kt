package com.whatsappv2.core.common.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RedactionTest {

    @Test
    fun `redact never reveals the value but keeps its length`() {
        assertEquals("***(8)", redact("hunter22"))
        assertEquals("<empty>", redact(""))
        assertEquals("null", redact(null))
    }

    @Test
    fun `redact output contains no character of the input`() {
        val secret = "SuperSecret1"
        val masked = redact(secret)
        assertFalse(masked.contains(secret))
        assertFalse(secret.any { it.isLetter() && masked.contains(it) })
    }

    @Test
    fun `redactPartial keeps a two-character tail for correlation`() {
        assertEquals("***22", redactPartial("hunter22"))
        assertEquals("null", redactPartial(null))
    }

    @Test
    fun `redactPartial masks short values completely`() {
        assertEquals("***", redactPartial("ab"))
        assertEquals("***", redactPartial("a"))
    }

    @Test
    fun `redactSipUri hides the user part and keeps the host`() {
        assertEquals("sip:***(5)@example.com:5061", redactSipUri("sip:alice@example.com:5061"))
    }

    @Test
    fun `redactSipUri masks entirely when there is no user part`() {
        assertEquals("***(11)", redactSipUri("example.com"))
        assertEquals("null", redactSipUri(null))
    }
}
