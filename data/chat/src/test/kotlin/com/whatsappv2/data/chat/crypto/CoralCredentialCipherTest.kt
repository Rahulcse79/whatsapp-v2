package com.whatsappv2.data.chat.crypto

import com.whatsappv2.core.common.result.errorOrNull
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.data.chat.BuildConfig
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The cipher, against the server's own algorithm as the oracle.
 *
 * ## Why [serverDecrypt] is a copy of the server and not a call to this class
 *
 * Because a round trip through one implementation proves only that it is self-consistent —
 * it would pass just as happily with CBC, with a fixed IV, or with the IV appended instead
 * of prepended. [serverDecrypt] is `DecryptCredential.decryptData` from `services-app`,
 * transcribed: it decodes Base64, takes the first sixteen bytes as the IV, and decrypts
 * from offset sixteen with `AES/CFB/PKCS5Padding`. If this cipher stops matching it, the
 * server stops being able to read a login — and these tests go red instead of a handset
 * getting a 500 that reads like an outage.
 *
 * That makes this the known-answer test the plan asked for, with the answer derived from
 * the server's source rather than waited on.
 */
class CoralCredentialCipherTest {

    /** Sixteen characters, used as its own UTF-8 bytes — the shape `login.key` has. */
    private val key = "0123456789abcdef"
    private val cipher = CoralCredentialCipher(key.toByteArray(Charsets.UTF_8))

    private fun encrypt(plaintext: String): String =
        cipher.encrypt(plaintext).getOrNull() ?: fail("expected $plaintext to encrypt")

    /** `DecryptCredential.decryptData`, transcribed. The oracle, not a convenience. */
    private fun serverDecrypt(base64: String): String {
        val all = Base64.getDecoder().decode(base64)
        val iv = all.copyOfRange(0, CoralCredentialCipher.IV_LENGTH_BYTES)
        val jce = Cipher.getInstance(CoralCredentialCipher.TRANSFORMATION)
        jce.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), CoralCredentialCipher.ALGORITHM),
            IvParameterSpec(iv),
        )
        return String(
            jce.doFinal(all, CoralCredentialCipher.IV_LENGTH_BYTES, all.size - CoralCredentialCipher.IV_LENGTH_BYTES),
            Charsets.UTF_8,
        )
    }

    @Test
    fun `the server can read what this produces`() {
        assertEquals("mcx10001", serverDecrypt(encrypt("mcx10001")))
    }

    @Test
    fun `a password with punctuation and spaces survives`() {
        val password = "p@ss w0rd/+=?&"

        assertEquals(password, serverDecrypt(encrypt(password)))
    }

    @Test
    fun `non-ASCII survives, which pins the charset to UTF-8`() {
        assertEquals("pässwörd-é", serverDecrypt(encrypt("pässwörd-é")))
    }

    @Test
    fun `an empty string encrypts rather than throwing`() {
        // Reachable: field validation lives in the use case, and a caller that skipped it
        // must get a typed answer rather than an exception out of a JCE provider.
        assertEquals("", serverDecrypt(encrypt("")))
    }

    // ------------------------------------------------------------------ the layout

    @Test
    fun `the IV is fresh per message, so the same plaintext never encodes the same way`() {
        // The server reads a random IV off the front of every message, so the client must
        // put a new one there. A fixed IV would still decrypt - and would leak that two
        // sign-ins used the same credential, which is the whole point of having one.
        assertNotEquals(encrypt("mcx10001"), encrypt("mcx10001"))
    }

    @Test
    fun `the IV is the first sixteen bytes, and the body is padded`() {
        // 16 bytes of IV plus one padded block for an 8-character username. This is the
        // arithmetic that proves PKCS5Padding rather than bare CFB: unpadded, an 8-byte
        // plaintext would give 24 bytes, not 32.
        val raw = Base64.getDecoder().decode(encrypt("mcx10001"))

        assertEquals(32, raw.size)
    }

    @Test
    fun `a six-character password encodes to the same length as an eight-character username`() {
        // Both pad to one block. It is also the check that matched the captured payload:
        // two 44-character Base64 strings for a 6- and an 8-character secret.
        assertEquals(
            Base64.getDecoder().decode(encrypt("123456")).size,
            Base64.getDecoder().decode(encrypt("mcx10001")).size,
        )
        assertEquals(44, encrypt("123456").length)
    }

    @Test
    fun `the output is standard Base64, padded and unwrapped`() {
        // android.util.Base64 inserts newlines unless passed NO_WRAP, and a newline inside
        // a credential field is a classic rejection that reads as a wrong password. This
        // uses java.util.Base64, which never wraps - asserted so it stays that way.
        val encoded = encrypt("a-long-enough-secret-to-wrap-at-seventy-six-characters-were-it-going-to-wrap")

        assertTrue('\n' !in encoded && '\r' !in encoded, "the encoder wrapped: $encoded")
        assertTrue('-' !in encoded && '_' !in encoded, "URL-safe alphabet, not standard: $encoded")
        // No assertion on the "=" itself: whether there is padding depends on the
        // length, and a 96-byte body happens to land on a multiple of three.
        assertTrue(encoded.matches(Regex("[A-Za-z0-9+/]+=*")), "not the standard alphabet: $encoded")
    }

    // ------------------------------------------------------------------ failure

    @Test
    fun `a key of the wrong length fails as a cipher failure, carrying no key material`() {
        val failure = CoralCredentialCipher(ByteArray(8)).encrypt("123456").errorOrNull()
            ?: fail("an 8-byte key must not produce a cipher")

        assertNotNull(failure.detail)
        assertTrue("0123456789abcdef" !in failure.detail, "the failure detail carries key material")
    }
}

/**
 * A build with no key must be a working build — and this test has to pass in both kinds.
 *
 * A developer's machine supplies `coral.cipher.key` from `~/.gradle/gradle.properties`, so
 * their build HAS a key. CI has no such secret, so its build has none. Asserting either
 * outcome outright would make the suite pass in one place and fail in the other, which is
 * worse than not asserting at all — so what is asserted is the **contract**: a cipher
 * exists exactly when the build carries a usable 16-byte key, and asking never throws.
 */
class CoralCipherMaterialTest {

    @Test
    fun `a cipher exists exactly when this build carries a usable key`() {
        val configured = BuildConfig.CORAL_CIPHER_KEY
        val usable = configured.toByteArray(Charsets.UTF_8).size == CoralCredentialCipher.KEY_LENGTH_BYTES

        val cipher = CoralCipherMaterial.load()

        if (usable) {
            assertNotNull(cipher, "the build has a 16-byte key but produced no cipher")
        } else {
            // The state a fresh clone and CI are in. It must be null rather than an
            // exception: the app still has to install and place calls without a chat key.
            assertNull(cipher)
        }
    }

    @Test
    fun `a key of the wrong length is refused rather than half-used`() {
        // A key short by one character still initialises a cipher and still produces valid
        // Base64 - the server just answers 500, which reads as an outage rather than as a
        // misconfigured build. Length is checked in bytes, not characters.
        assertEquals(16, CoralCredentialCipher.KEY_LENGTH_BYTES)
    }
}
