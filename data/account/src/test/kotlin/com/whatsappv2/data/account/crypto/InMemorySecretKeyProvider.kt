package com.whatsappv2.data.account.crypto

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * A [SecretKeyProvider] backed by an ordinary in-memory AES key.
 *
 * Exists so the AES-GCM handling can be tested on the JVM: the Android Keystore is not
 * available there, and without this seam the encoding, the IV behaviour and every
 * failure path could only be exercised on a device — which in practice means not at all.
 *
 * It can also be told to fail, so the recovery paths are testable without contriving a
 * real key invalidation.
 */
class InMemorySecretKeyProvider(
    private val keySizeBits: Int = AesGcmCredentialCipher.KEY_SIZE_BITS,
    /**
     * The key algorithm. A test can supply one AES-GCM cannot use, which is the only
     * practical way to exercise the generic `GeneralSecurityException` path.
     */
    private val algorithm: String = "AES",
) : SecretKeyProvider {

    private var key: SecretKey? = null
    var deleteCount: Int = 0
        private set

    /** When set, [getOrCreateKey] returns this instead of a key. */
    var failure: CipherError? = null

    override fun getOrCreateKey(): Outcome<SecretKey, CipherError> {
        failure?.let { return failure(it) }
        val existing = key ?: KeyGenerator.getInstance(algorithm)
            .apply { if (algorithm == "AES") init(keySizeBits) }
            .generateKey()
            .also { key = it }
        return success(existing)
    }

    override fun deleteKey() {
        deleteCount++
        key = null
    }

    /** Replaces the key, simulating a Keystore key that was invalidated and re-created. */
    fun rotateKey() {
        key = null
    }
}
