package com.whatsappv2.data.account.crypto

import com.whatsappv2.core.common.result.Outcome
import javax.crypto.SecretKey

/**
 * Supplies the symmetric key used to protect stored credentials.
 *
 * Extracted behind an interface for one reason: the Android Keystore cannot be exercised
 * on the JVM, so without this seam the AES-GCM handling — the encoding, the IV
 * behaviour, the failure paths — could only be tested on a device, and in practice would
 * not be tested at all.
 *
 * The seam does not weaken anything. Production always uses the Keystore-backed
 * implementation; the in-memory one exists only in test source.
 */
interface SecretKeyProvider {

    /** Returns the existing key, creating one on first use. */
    fun getOrCreateKey(): Outcome<SecretKey, CipherError>

    /**
     * Deletes the key, making every existing ciphertext permanently unreadable.
     *
     * Called only after [CipherError.KeyInvalidated], as part of the recovery path: the
     * old key is already useless, and leaving it in place would keep returning the same
     * error forever.
     */
    fun deleteKey()
}
