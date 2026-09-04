package com.whatsappv2.data.account.crypto

/**
 * Why encryption or decryption failed.
 *
 * [KeyInvalidated] is the case that matters most, and it is why this is a typed error
 * rather than an exception: it is not a bug, it is a state the app must recover from by
 * asking the user to re-enter their password (Task 16 done-when #3). Losing that
 * distinction means a crash where there should be a prompt.
 */
sealed interface CipherError {

    /**
     * The Keystore key is gone or no longer usable — the device's secure lock screen was
     * removed, the app's data was restored to another device, or the key was invalidated
     * by a credential change.
     *
     * Stored credentials are unrecoverable. The only correct response is to discard them
     * and ask the user to enter the password again.
     */
    data object KeyInvalidated : CipherError

    /** The Keystore itself is unavailable — no hardware backing, or a provider failure. */
    data class KeyUnavailable(val detail: String) : CipherError

    /**
     * The ciphertext did not authenticate.
     *
     * With AES-GCM this means the data was altered, truncated, or encrypted under a
     * different key. It is never a "wrong password" — the password is what is being
     * decrypted, not what decrypts it.
     */
    data object AuthenticationFailed : CipherError

    /** The stored blob is not in the expected format, or carries an unknown version. */
    data class MalformedCiphertext(val detail: String) : CipherError

    /** Anything the platform reported that has no specific meaning here. */
    data class Unexpected(val detail: String) : CipherError

    /** True when the user must supply the secret again; nothing else can recover it. */
    val requiresReEntry: Boolean get() = this is KeyInvalidated || this is AuthenticationFailed
}
