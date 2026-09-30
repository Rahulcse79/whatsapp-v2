package com.whatsappv2.domain.chat

import com.whatsappv2.core.common.secret.Secret

/**
 * Proof that somebody is signed in to the Coral platform, and who they are.
 *
 * ## The token is a [Secret]
 *
 * A bearer token is a credential: anyone holding it is the user until it expires. `Secret`
 * masks it in every `toString`, string template and crash report, which is what keeps it
 * out of a log without anyone having to remember (§7, DoD 12). Reading it requires
 * `reveal()`, which is explicit and greppable — call it where the `Authorization` header
 * is built and nowhere else.
 *
 * ## Why the password is not here
 *
 * Because it is not needed after sign-in and storing it would be a liability with no
 * benefit. It lives in [ChatCredentials] for the duration of one request. If token
 * refresh turns out to require re-sending it, that changes — and it would then be stored
 * through the same Keystore cipher SIP passwords use, never in plain preferences.
 */
data class ChatSession(
    /** This user's id on the Coral platform. */
    val userId: String,

    /** What to show in the UI. Null when the server did not supply one. */
    val displayName: String?,

    /** The bearer token, for `Authorization: Bearer`. */
    val token: Secret,

    /**
     * When the token stops working, or null when the server does not say.
     *
     * Null means "assume it is good until a 401 says otherwise", which is the only honest
     * behaviour without an expiry: guessing one would sign the user out early.
     */
    val expiresAtMs: Long?,

    /**
     * The `deviceId` sent at sign-in, kept so a refresh or a re-sign-in reuses it.
     *
     * **Not the chat SDK's install id.** The SDK keeps its own, in its own format
     * (`UUID.randomUUID().toString()` — lowercase, dashed) for its own purpose. This one
     * is 32 uppercase hex characters and belongs to the Coral platform. Two values, two
     * owners; do not try to share one.
     */
    val deviceId: String,
) {
    /** True when [expiresAtMs] is in the past. Null expiry is never expired — see the field. */
    fun isExpiredAt(nowMs: Long): Boolean = expiresAtMs != null && expiresAtMs <= nowMs
}
