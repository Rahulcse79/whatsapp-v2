package com.whatsappv2.data.chat.crypto

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The encoding `POST {origin}/services/app/v2/auth/login` expects for its two credential
 * fields.
 *
 * ## Written against the server's own source, not against the spec
 *
 * The API document says "AES-128-CFB + Base64". The server's `DecryptCredential.decryptData`
 * says something more specific, and the difference is not cosmetic:
 *
 * | | What this does | Why it is not a guess |
 * |---|---|---|
 * | Transformation | `AES/CFB/PKCS5Padding` — **with** padding | The server names it. Padding on a stream mode is unusual, and omitting it produces ciphertext one block short of what the server reads. |
 * | IV | **Random per message, prepended** to the ciphertext before Base64 | `decryptData` takes `encryptedBytes[0..15]` as the IV and decrypts from offset 16. |
 * | Key | The `login.key` string's **UTF-8 bytes**, 16 of them | `new SecretKeySpec(secretKey.getBytes("UTF-8"), "AES")` — a passphrase used directly, not a KDF and not hex. |
 * | Base64 | `java.util.Base64`, standard alphabet, padded, unwrapped | The server decodes with `Base64.getDecoder()`. |
 * | Charset | UTF-8 | `data.getBytes("UTF-8")`. |
 *
 * The ciphertext length corroborates it independently: an 8-character username and a
 * 6-character password both encode to 44 Base64 characters — 32 bytes — which is a 16-byte
 * IV plus one **padded** 16-byte block. Unpadded CFB would have produced 24 and 22.
 *
 * `/login2` uses a different scheme again (`AES/CBC/PKCS5Padding` with a fixed IV, via
 * `decryptPaddingCS5`). This app posts to `/login`, so it uses the one above.
 *
 * ## This is not the account cipher, and the key is not a secret
 *
 * `:data:account`'s cipher is Keystore-backed and protects credentials **at rest**; its
 * keys cannot leave the device. This one is a shared key agreed with a server, protecting
 * a field **in transit** — and it ships inside the APK, so anybody who unpacks the APK can
 * recover it. **It is obfuscation of a credential, not transport security.** What actually
 * protects the password on the wire is TLS. Nothing here depends on the key being secret;
 * it is implemented this way because it is the server's contract.
 *
 * Plaintext, key, IV and ciphertext never reach a log.
 */
internal class CoralCredentialCipher(private val key: ByteArray) {

    /**
     * One source of randomness for the IV, not injected.
     *
     * Making it configurable would invite a test to substitute a predictable one, and a
     * repeated IV under a fixed key is the one mistake that makes this worth less than the
     * little it is worth.
     */
    private val random = SecureRandom()

    /** `Base64(iv || AES/CFB/PKCS5Padding(plaintext))`, ready for the request body. */
    fun encrypt(plaintext: String): Outcome<String, CoralCipherFailure> = try {
        val iv = ByteArray(IV_LENGTH_BYTES).also(random::nextBytes)

        // Deliberately NOT `Cipher.getInstance(T).apply { init(..., IvParameterSpec(iv)) }`.
        // Inside that lambda the implicit receiver is the Cipher, which has its own
        // `getIV()` — so `iv` would bind to the cipher's IV, which is null until init has
        // run. It compiles, and every encryption throws NullPointerException.
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, ALGORITHM), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray(CHARSET))

        success(Base64.getEncoder().encodeToString(iv + encrypted))
    } catch (e: GeneralSecurityException) {
        // The class name and nothing else. A JCE provider's message can carry key length
        // and mode detail, and neither belongs in a bug report.
        failure(CoralCipherFailure(e.javaClass.simpleName))
    }

    companion object {
        /** `DecryptCredential`, verbatim. `AES/CFB/NoPadding` would be one block short. */
        const val TRANSFORMATION = "AES/CFB/PKCS5Padding"

        const val ALGORITHM = "AES"

        /** AES-128: `login.key` is a 16-character string used as its own UTF-8 bytes. */
        const val KEY_LENGTH_BYTES = 16

        /** One AES block, prepended to the ciphertext. The server reads exactly this many. */
        const val IV_LENGTH_BYTES = 16

        val CHARSET = Charsets.UTF_8
    }
}

/**
 * The cipher could not run.
 *
 * Carries a class name for a bug report and no value from the operation. Mapped to
 * [com.whatsappv2.domain.chat.ChatAuthError.CryptoUnavailable] at the repository boundary,
 * because a build with no key must never report itself to a user as a wrong password.
 */
internal data class CoralCipherFailure(val detail: String)
