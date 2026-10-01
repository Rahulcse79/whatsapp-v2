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
     * The id to hand to `ChatSdk.openDirectConversation`.
     *
     * **Provisional.** The phonebook response has not been specified yet, and in this
     * server's vocabulary `userId`, `contactIdentifier`, `deviceKey` and an extension
     * number are four different things. Whichever one the conversation API accepts is what
     * belongs here; the mapper in `:data:chat` is the single place that decides, so
     * changing the answer is a one-file change.
     */
    val id: String,

    val displayName: String,

    /** The dialable extension, when the directory carries one. Lets a row offer a call as well as a chat. */
    val extension: String?,

    /** Which department the row came back under. Null when the response does not say. */
    val department: String?,

    /** Absolute URL, or null. Drawn by `:core:designsystem`'s `Avatar`, which falls back to initials. */
    val avatarUrl: String?,
)
