package com.whatsappv2.domain.chat

/**
 * Whether a conversation handle is really the signed-in user talking to themselves.
 *
 * ## What this is actually for
 *
 * chat-node's guest mode provisions an identity for whatever `deviceKey` asks, and never
 * checks whether it is the caller's own. An earlier build of this app addressed
 * conversations by **extension** rather than by designation, so signing in as `mcx8102` and
 * opening a chat created `guest-8102@guest.local` — a second identity for the same person,
 * which the server then reports back to `mcx8102` as a perfectly ordinary counterparty.
 *
 * Read off the live server on 2 Oct 2026, `mcx8102`'s conversation list:
 *
 * ```
 * guest-mcx8101@guest.local   the real chat with 8101
 * guest-8101@guest.local      a phantom 8101, from the extension-keyed build
 * guest-8102@guest.local      a phantom of THEMSELVES
 * ```
 *
 * That third row is the bug this closes. It renders as `8102`, which is the signed-in
 * user's own extension, so pressing call on it dialled **8102 calling 8102** — reported
 * from a device as "I open 8101's chat and the call goes to my own number". Nothing was
 * wrong with the dialling: the row genuinely was addressed to 8102.
 *
 * ## Matched on both names, because the phantoms use both
 *
 * The designation (`mcx8102`) is what this build addresses by; the extension (`8102`) is
 * what the old one used. A row carrying either one is the same person, and the only useful
 * thing to do with it is to stop showing it as somebody to talk to or to ring.
 *
 * ## They cannot be deleted, only hidden
 *
 * `ChatSdk` has no delete and chat-node exposes no frame for one, so these rows exist on the
 * server for good. Hiding is the whole of the available fix — which is also why the rule is
 * here, in one function, rather than written out at each of the three places that need it.
 */
fun isSelfConversation(handle: String?, username: String?, extension: String?): Boolean {
    val value = handle?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return value.equals(username?.trim(), ignoreCase = true) ||
        value.equals(extension?.trim(), ignoreCase = true)
}
