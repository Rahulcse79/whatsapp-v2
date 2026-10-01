package com.whatsappv2.data.chat.crypto

import com.whatsappv2.data.chat.BuildConfig

/**
 * Where [CoralCredentialCipher]'s key comes from, and what happens when it is absent.
 *
 * ## It is a 16-character string, not hex and not a passphrase through a KDF
 *
 * The server does `new SecretKeySpec(secretKey.getBytes("UTF-8"), "AES")` on the value of
 * its `login.key` property, so the key **is** those 16 characters. Decoding or deriving
 * anything from them would produce a different key and a 500 from the login endpoint,
 * which is what the server returns when it cannot decrypt.
 *
 * There is no IV here: the server reads a random one from the front of each ciphertext.
 *
 * ## Absent is a supported state, not a crash
 *
 * The key is a build input, supplied by a Gradle property from `~/.gradle/gradle.properties`
 * locally and from a secret in CI — the pattern `data/sip/build.gradle.kts` already uses
 * for its integration credentials, and for the reason it states there: *"a password is a
 * credential; either one in git is a leak that outlives the commit that removed it."*
 *
 * A build made without the property has no key, which is ordinary for a fork, a fresh
 * clone, or a CI job with no access to the secret. That build must still compile, install
 * and place calls — this is a SIP client first — and its chat sign-in must say *"sign-in
 * is not configured in this build"* rather than *"wrong password"*. So [load] returns null
 * and the repository maps that to `ChatAuthError.CryptoUnavailable`.
 */
internal object CoralCipherMaterial {

    /**
     * The configured cipher, or null when this build carries no usable key.
     *
     * Built fresh rather than held in a field: a key in a long-lived static is a key in
     * every heap dump for the life of the process.
     */
    fun load(): CoralCredentialCipher? {
        val configured = BuildConfig.CORAL_CIPHER_KEY
        // Strict about length. A key short by one character still initialises a cipher and
        // still produces valid Base64 — the server just answers 500, which reads as an
        // outage rather than as a misconfigured build.
        if (configured.length != CoralCredentialCipher.KEY_LENGTH_BYTES) return null

        val bytes = configured.toByteArray(CoralCredentialCipher.CHARSET)
        // Length in CHARACTERS is not length in BYTES: sixteen characters of which one is
        // non-ASCII is seventeen bytes, and AES would reject it. Checked rather than assumed.
        if (bytes.size != CoralCredentialCipher.KEY_LENGTH_BYTES) return null

        return CoralCredentialCipher(bytes)
    }
}
