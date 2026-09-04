package com.whatsappv2.core.common.secret

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretTest {

    private val secret = Secret("hunter22")

    @Test
    fun `toString masks the value`() {
        assertEquals("Secret(***)", secret.toString())
        assertFalse("hunter22" in secret.toString())
    }

    @Test
    fun `string interpolation cannot leak the value`() {
        val interpolated = "password=$secret"
        assertFalse("hunter22" in interpolated, "interpolation leaked the secret: $interpolated")
    }

    @Test
    fun `reveal returns the real value`() {
        assertEquals("hunter22", secret.reveal())
    }

    @Test
    fun `length is exposed so unset can be told from wrong`() {
        assertEquals(8, secret.length)
        assertEquals(0, Secret.EMPTY.length)
    }

    @Test
    fun `isEmpty reflects the underlying value`() {
        assertTrue(Secret.EMPTY.isEmpty)
        assertTrue(Secret("").isEmpty)
        assertFalse(secret.isEmpty)
    }

    @Test
    fun `equality is by value`() {
        assertEquals(Secret("a"), Secret("a"))
        assertTrue(Secret("a") != Secret("b"))
    }
}
