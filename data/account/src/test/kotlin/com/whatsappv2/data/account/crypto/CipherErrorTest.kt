package com.whatsappv2.data.account.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The recovery classification, exhaustively.
 *
 * `requiresReEntry` decides whether the UI prompts for the password again, so getting a
 * case wrong either dead-ends the user or tells them to retype something that retyping
 * cannot fix. Every case is asserted rather than the two that happen to be hit by the
 * cipher tests.
 */
class CipherErrorTest {

    @Test
    fun `a lost or invalidated key is recoverable by re-entering the password`() {
        assertTrue(CipherError.KeyInvalidated.requiresReEntry)
        assertTrue(CipherError.AuthenticationFailed.requiresReEntry)
    }

    @Test
    fun `failures the user cannot fix by retyping are not offered as re-entry`() {
        // Telling someone to retype their password after a Keystore provider failure
        // would be a lie: the new value could not be stored either.
        assertFalse(CipherError.KeyUnavailable("NoSuchProviderException").requiresReEntry)
        assertFalse(CipherError.MalformedCiphertext("bad framing").requiresReEntry)
        assertFalse(CipherError.Unexpected("IllegalBlockSizeException").requiresReEntry)
    }

    @Test
    fun `errors carry the detail a bug report needs`() {
        assertEquals("NoSuchProviderException", CipherError.KeyUnavailable("NoSuchProviderException").detail)
        assertEquals("bad framing", CipherError.MalformedCiphertext("bad framing").detail)
        assertEquals("boom", CipherError.Unexpected("boom").detail)
    }

    @Test
    fun `equality is by value, so an error can be asserted on directly`() {
        assertEquals(CipherError.KeyInvalidated, CipherError.KeyInvalidated)
        assertEquals(CipherError.KeyUnavailable("x"), CipherError.KeyUnavailable("x"))
        assertTrue(CipherError.KeyUnavailable("x") != CipherError.KeyUnavailable("y"))
    }

    @Test
    fun `every case is classified, so a new one cannot be silently unhandled`() {
        val cases = listOf(
            CipherError.KeyInvalidated,
            CipherError.AuthenticationFailed,
            CipherError.KeyUnavailable("x"),
            CipherError.MalformedCiphertext("x"),
            CipherError.Unexpected("x"),
        )
        val recoverable = cases.count { it.requiresReEntry }
        assertEquals(2, recoverable, "exactly the key-loss cases should prompt for re-entry")
    }
}
