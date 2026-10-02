package com.whatsappv2.domain.chat

/**
 * One person in the company directory.
 *
 * ## This is not a [com.whatsappv2.domain.contacts] type, and the distinction is enforced
 *
 * That package holds the **device address book** — somebody else's personal data, held on
 * loan to put a name on a ringing screen, which architecture rule 9 forbids from leaving
 * the device. This is the **server's** directory: data the Coral platform already has,
 * fetched over the network, and safe to send back to it.
 *
 * Keeping them in different packages is what lets rule 9 stay coarse enough to be useful.
 * Naming this `Contact`, or putting it under `domain.contacts`, would make every file that
 * touches it look like a contact-data leak to the rule — and the rule would be right to
 * complain, because at that point nobody could tell the two apart by reading an import.
 */
data class ChatContact(
    /**
     * The id to hand to `ChatSdk.openDirectConversation` — the directory's `designation`.
     *
     * **Not the extension.** chat-node's guest mode keys an identity by `deviceKey`, and
     * this app signs in with `deviceKey = userName` (`ChatSession.userId`). So the person
     * behind extension `8102` is `guest-mcx8102@guest.local` on the chat server, and a
     * conversation opened against `8102` creates `guest-8102@guest.local` — a second,
     * phantom identity that nobody is ever signed in as. Messages sent to it are
     * delivered, acknowledged, and read by no one.
     *
     * Verified against the live directory: every `phone` row carries `designation` =
     * `mcx8102` and `offEXtn` = `8102`, and a probe as `deviceKey=8102` saw its
     * counterparty as `guest-mcx8101@guest.local` — the username, not the extension.
     *
     * Falls back to the extension only for rows with no designation (the `ec` type), which
     * have no chat user behind them at all.
     */
    val id: String,

    /**
     * What the directory calls this person — `designation`, which holds the username.
     *
     * Null for rows that have none. It is the same string as [id] whenever there is one,
     * and kept separately because they answer different questions: one addresses a
     * conversation, the other is shown to a human.
     */
    val username: String?,

    val displayName: String,

    /** The dialable extension, when the directory carries one. Lets a row offer a call as well as a chat. */
    val extension: String?,

    /** Which department the row came back under. Null when the response does not say. */
    val department: String?,

    /** Absolute URL, or null. Drawn by `:core:designsystem`'s `Avatar`, which falls back to initials. */
    val avatarUrl: String?,
) {

    /**
     * How to write this person down: `8102 (mcx8102)`.
     *
     * The rule itself is [chatIdentityLabel], shared with the signed-in user's own label in
     * the Chats header so the header and the rows underneath it cannot disagree.
     */
    val label: String get() = chatIdentityLabel(extension, username, fallback = displayName)
}
