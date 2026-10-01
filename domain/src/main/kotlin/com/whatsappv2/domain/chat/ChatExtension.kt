package com.whatsappv2.domain.chat

import com.whatsappv2.core.common.secret.Secret

/**
 * The telephony half of a Coral login: which extension this person is, and how to register it.
 *
 * ## Every field here is the server's answer to a question this app used to guess
 *
 * The login response carries all of it, and the first version of the chat sign-in used
 * none of it — it registered the **username** against the chat host on a hard-coded port
 * with the **chat** password. Three guesses, each of which produces an account that looks
 * right in the list and cannot place a call:
 *
 * | Field | What was assumed | What the server actually says |
 * |---|---|---|
 * | [number] | the username, `mcx8101` | `extension` — `8101` |
 * | [sipPassword] | the password typed at sign-in | `sipPassword`, a different credential |
 * | [domain] | the chat origin's host | `primaryDomain` |
 * | [port] | `1234` | `serverPort` — `5061` on this deployment |
 *
 * ## [sipPassword] is transient, and that is deliberate
 *
 * It is a credential, and `ChatSessionStore`'s stated rule is that its DataStore holds
 * nothing sensitive. It is also needed exactly once — at sign-in, to create the SIP
 * account, after which it lives in the account repository's own encrypted column. So it
 * is present on a session returned by a fresh sign-in and **null on one read back from
 * storage**, which is why provisioning only ever happens on the way in.
 */
data class ChatExtension(
    /** The number the PBX knows this person by. Not the username — see the table above. */
    val number: String,

    /**
     * What to show as the caller's name, or null when the server has none.
     *
     * `extensionName` is null on the accounts this was built against, which is why
     * [displayName] falls back to the number rather than leaving the field empty.
     */
    val name: String?,

    /** The SIP credential, present only on a freshly signed-in session. See the KDoc. */
    val sipPassword: Secret?,

    /** `primaryDomain`: the SIP domain and registrar. */
    val domain: String,

    /** `serverPort`. 5061 on this deployment, which is not SIP's default. */
    val port: Int,

    /** `enableSsl`. False on this deployment, so the transport is plain UDP. */
    val secure: Boolean,
) {

    /**
     * The name to register with: the server's, or the number when it has none.
     *
     * The rule is stated once, here, rather than at each call site — a caller that forgot
     * it would register an account whose display name is blank.
     */
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: number
}
