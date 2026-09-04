package com.whatsappv2.data.account.crypto

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.errorOrNull
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class AesGcmCredentialCipherTest {

    private val keys = InMemorySecretKeyProvider()
    private val cipher = AesGcmCredentialCipher(keys)

    private fun encrypt(value: String): String =
        cipher.encrypt(Secret(value)).getOrNull() ?: fail("encryption failed")

    private fun decrypt(blob: String): Secret =
        cipher.decrypt(blob).getOrNull() ?: fail("decryption failed")

    // ---------------------------------------------------------------- round trip

    @Test
    fun `round-trips an ordinary password`() {
        assertEquals("hunter22", decrypt(encrypt("hunter22")).reveal())
    }

    @Test
    fun `round-trips an empty password`() {
        // A blank password is a legitimate state - the user cleared the field - and must
        // not be confused with "no ciphertext stored".
        val blob = encrypt("")
        assertTrue(blob.isNotEmpty(), "an empty secret must still produce a stored blob")
        assertEquals("", decrypt(blob).reveal())
    }

    @Test
    fun `round-trips a 256-character password`() {
        val long = "a".repeat(256)
        assertEquals(256, long.length)
        assertEquals(long, decrypt(encrypt(long)).reveal())
    }

    @Test
    fun `round-trips non-ASCII characters`() {
        // SIP passwords are arbitrary text; UTF-8 handling must not mangle them.
        val awkward = "passwoerd-\u00e4\u00f6\u00fc-\u4f60\u597d"
        assertEquals(awkward, decrypt(encrypt(awkward)).reveal())
    }

    // ---------------------------------------------------------------- IV uniqueness

    @Test
    fun `two encryptions of the same value differ`() {
        val first = encrypt("hunter22")
        val second = encrypt("hunter22")
        assertNotEquals(first, second, "identical ciphertexts mean the IV is being reused")
        assertEquals("hunter22", decrypt(first).reveal())
        assertEquals("hunter22", decrypt(second).reveal())
    }

    @Test
    fun `many encryptions never repeat an IV`() {
        // IV reuse under one key breaks GCM catastrophically, so this is worth more than
        // a single comparison.
        val ivs = (1..200)
            .map { Base64.getDecoder().decode(encrypt("same")).copyOfRange(1, 13).toList() }
        assertEquals(ivs.size, ivs.toSet().size, "an IV was reused")
    }

    @Test
    fun `the ciphertext does not contain the plaintext`() {
        val blob = encrypt("hunter22")
        assertFalse("hunter22" in blob)
        assertFalse("hunter22" in String(Base64.getDecoder().decode(blob), Charsets.ISO_8859_1))
    }

    // ---------------------------------------------------------------- framing

    @Test
    fun `the blob is versioned so a future format change is detectable`() {
        val framed = Base64.getDecoder().decode(encrypt("x"))
        assertEquals(AesGcmCredentialCipher.FORMAT_VERSION, framed[0])
        assertTrue(framed.size > 1 + AesGcmCredentialCipher.IV_LENGTH)
    }

    @Test
    fun `an unknown version is rejected rather than mis-decrypted`() {
        val framed = Base64.getDecoder().decode(encrypt("x"))
        framed[0] = UNKNOWN_VERSION
        val error = cipher.decrypt(Base64.getEncoder().encodeToString(framed)).errorOrNull()
        assertIs<CipherError.MalformedCiphertext>(error)
    }

    @Test
    fun `garbage input is reported as malformed, not as tampering`() {
        for (bad in listOf("", "not base64!!", "c2hvcnQ=")) {
            assertIs<CipherError.MalformedCiphertext>(
                cipher.decrypt(bad).errorOrNull(),
                "for input '$bad'",
            )
        }
    }

    // ---------------------------------------------------------------- tampering

    @Test
    fun `altering the ciphertext fails authentication`() {
        // GCM is authenticated precisely so this cannot return plausible rubbish that
        // would then be sent as a SIP password.
        val framed = Base64.getDecoder().decode(encrypt("hunter22"))
        framed[framed.size - 1] = (framed[framed.size - 1] + 1).toByte()

        val error = cipher.decrypt(Base64.getEncoder().encodeToString(framed)).errorOrNull()
        assertIs<CipherError.AuthenticationFailed>(error)
        assertTrue(error.requiresReEntry)
    }

    @Test
    fun `altering the IV fails authentication`() {
        val framed = Base64.getDecoder().decode(encrypt("hunter22"))
        framed[1] = (framed[1] + 1).toByte()
        assertIs<CipherError.AuthenticationFailed>(
            cipher.decrypt(Base64.getEncoder().encodeToString(framed)).errorOrNull(),
        )
    }

    // ---------------------------------------------------------------- key loss

    @Test
    fun `a value encrypted under a lost key cannot be decrypted, and says so`() {
        val blob = encrypt("hunter22")
        keys.rotateKey()

        val error = cipher.decrypt(blob).errorOrNull()
        assertIs<CipherError.AuthenticationFailed>(error)
        assertTrue(error.requiresReEntry, "the user must be asked for the password again")
    }

    @Test
    fun `an invalidated key is reported as recoverable by re-entry, not as a crash`() {
        keys.failure = CipherError.KeyInvalidated

        val error = cipher.encrypt(Secret("hunter22")).errorOrNull()
        assertIs<CipherError.KeyInvalidated>(error)
        assertTrue(error.requiresReEntry)
    }

    @Test
    fun `an unavailable Keystore is distinguished from an invalidated key`() {
        // One is recoverable by re-entering the password; the other is not recoverable at
        // all, and telling the user to retype would be a lie.
        keys.failure = CipherError.KeyUnavailable("NoSuchProviderException")

        val error = cipher.encrypt(Secret("x")).errorOrNull()
        assertIs<CipherError.KeyUnavailable>(error)
        assertFalse(error.requiresReEntry)
    }

    @Test
    fun `an unusable key is reported rather than thrown`() {
        // A key AES-GCM cannot accept produces a platform GeneralSecurityException. It
        // must arrive as a value: an exception here would be a crash on launch for any
        // user whose Keystore returned something unexpected.
        val wrongAlgorithm = AesGcmCredentialCipher(InMemorySecretKeyProvider(algorithm = "DES"))

        val error = wrongAlgorithm.encrypt(Secret("hunter22")).errorOrNull()
        assertIs<CipherError.Unexpected>(error)
        assertFalse(error.requiresReEntry, "retyping cannot fix an unusable key")
    }

    @Test
    fun `resetKey discards the key so the app stops retrying against it`() {
        cipher.resetKey()
        assertEquals(1, keys.deleteCount)
    }

    @Test
    fun `decryption failures are values, never thrown`() {
        // The recovery path depends on this: a throw here becomes a crash on launch for
        // any user whose device restored app data from another handset.
        assertIs<Outcome.Failure<CipherError>>(cipher.decrypt("nonsense"))
    }

    private companion object {
        const val UNKNOWN_VERSION: Byte = 99
    }
}
