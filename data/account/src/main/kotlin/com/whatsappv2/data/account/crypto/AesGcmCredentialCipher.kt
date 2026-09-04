package com.whatsappv2.data.account.crypto

import android.security.keystore.KeyPermanentlyInvalidatedException
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import com.whatsappv2.core.common.secret.Secret
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject

/**
 * AES-256-GCM using a key held by the Android Keystore.
 *
 * ## Why this rather than Jetpack Security
 *
 * `EncryptedSharedPreferences` and `EncryptedFile` are deprecated, and neither fits: the
 * credentials belong in the same Room row as the account they protect, so they can be
 * written and deleted atomically with it. This is the "equivalent AES-GCM wrapping"
 * §7 permits.
 *
 * No cryptography is implemented here. AES and GCM come from the platform provider; this
 * class only chooses the mode, manages the IV, and frames the result.
 *
 * ## GCM, not CBC
 *
 * GCM is authenticated: altering a stored blob makes decryption fail loudly rather than
 * returning plausible-looking rubbish that would then be sent as a SIP password.
 *
 * ## The IV
 *
 * A fresh 12-byte IV per encryption, from [SecureRandom], stored alongside the
 * ciphertext. Reusing an IV under the same key breaks GCM catastrophically — it leaks
 * the XOR of the plaintexts and the authentication subkey — so it is generated per call
 * and never derived from the data.
 *
 * ## Framing
 *
 * `base64(version | iv | ciphertext+tag)`. The version byte exists so a future change of
 * algorithm can be detected rather than mis-decrypted: without it, old rows would fail
 * with an authentication error that looks like tampering.
 */
class AesGcmCredentialCipher @Inject constructor(
    private val keyProvider: SecretKeyProvider,
    private val random: SecureRandom = SecureRandom(),
) : CredentialCipher {

    override fun encrypt(secret: Secret): Outcome<String, CipherError> =
        when (val key = keyProvider.getOrCreateKey()) {
            is Outcome.Failure -> key
            is Outcome.Success -> runCatchingCipher {
                val iv = ByteArray(IV_LENGTH).also(random::nextBytes)
                val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.ENCRYPT_MODE, key.value, GCMParameterSpec(TAG_LENGTH_BITS, iv))
                }
                val encrypted = cipher.doFinal(secret.reveal().toByteArray(Charsets.UTF_8))

                val framed = ByteArray(1 + IV_LENGTH + encrypted.size)
                framed[0] = FORMAT_VERSION
                iv.copyInto(framed, destinationOffset = 1)
                encrypted.copyInto(framed, destinationOffset = 1 + IV_LENGTH)

                Base64.getEncoder().encodeToString(framed)
            }
        }

    override fun decrypt(ciphertext: String): Outcome<Secret, CipherError> {
        val framed = decodeFrame(ciphertext) ?: return failure(
            CipherError.MalformedCiphertext("not valid Base64, or too short to contain an IV"),
        )
        if (framed[0] != FORMAT_VERSION) {
            return failure(CipherError.MalformedCiphertext("unknown format version ${framed[0]}"))
        }

        return when (val key = keyProvider.getOrCreateKey()) {
            is Outcome.Failure -> key
            is Outcome.Success -> runCatchingCipher {
                val iv = framed.copyOfRange(1, 1 + IV_LENGTH)
                val body = framed.copyOfRange(1 + IV_LENGTH, framed.size)
                val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, key.value, GCMParameterSpec(TAG_LENGTH_BITS, iv))
                }
                Secret(String(cipher.doFinal(body), Charsets.UTF_8))
            }
        }
    }

    override fun resetKey() = keyProvider.deleteKey()

    private fun decodeFrame(ciphertext: String): ByteArray? = try {
        Base64.getDecoder().decode(ciphertext).takeIf { it.size > 1 + IV_LENGTH }
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * Maps platform failures onto [CipherError].
     *
     * The distinctions are the point. A permanently invalidated key means "ask the user
     * again"; a bad GCM tag means the stored data is corrupt or foreign. Collapsing both
     * into one generic failure would turn a recoverable prompt into a dead end.
     */
    private inline fun <T> runCatchingCipher(block: () -> T): Outcome<T, CipherError> = try {
        success(block())
    } catch (_: KeyPermanentlyInvalidatedException) {
        failure(CipherError.KeyInvalidated)
    } catch (_: AEADBadTagException) {
        failure(CipherError.AuthenticationFailed)
    } catch (e: GeneralSecurityException) {
        failure(CipherError.Unexpected(e.javaClass.simpleName))
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** 12 bytes is the GCM-recommended IV size; other lengths cost a GHASH step. */
        const val IV_LENGTH = 12

        /** Full-length authentication tag. Truncating it weakens forgery resistance. */
        const val TAG_LENGTH_BITS = 128

        const val KEY_SIZE_BITS = 256

        /** Bumped only if the framing or algorithm changes. */
        const val FORMAT_VERSION: Byte = 1
    }
}
