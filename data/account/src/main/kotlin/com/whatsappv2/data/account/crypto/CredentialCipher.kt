package com.whatsappv2.data.account.crypto

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.secret.Secret

/**
 * Encrypts and decrypts stored credentials (§7, DoD 12).
 *
 * Takes and returns [Secret] rather than [String] so a decrypted password cannot leak
 * through a log line or a `toString()` on its way to the SIP stack.
 *
 * Errors are values: a lost Keystore key is an expected state after a device restore,
 * not an exceptional one, and the caller must handle it by prompting for re-entry.
 */
interface CredentialCipher {

    /** Encrypts [secret], returning an opaque blob safe to store in the database. */
    fun encrypt(secret: Secret): Outcome<String, CipherError>

    /** Decrypts a blob produced by [encrypt]. */
    fun decrypt(ciphertext: String): Outcome<Secret, CipherError>

    /**
     * Discards the key after [CipherError.KeyInvalidated].
     *
     * Every stored credential becomes unreadable, which is already true — this only
     * stops the app retrying against a key that can never work again.
     */
    fun resetKey()
}
