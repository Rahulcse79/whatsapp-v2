package com.whatsappv2.data.chat.net.dto

import com.whatsappv2.domain.chat.ChatContact

/**
 * A directory row, or null when it carries nothing to show or reach.
 *
 * ## Which field becomes the conversation id
 *
 * [PhoneBookRow.designation] — the username — and **not** the extension. chat-node
 * keys a guest identity by `deviceKey`, which this app sets to the Coral `userName`,
 * so the person at extension `8102` is `guest-mcx8102@guest.local` on the chat server.
 * Opening against `8102` built `guest-8102@guest.local` instead: a second identity
 * nobody signs in as, whose messages are delivered to nobody. The directory hands us
 * both — `designation` is `mcx8102`, `offEXtn` is `8102`.
 *
 * The extension is the fallback, for rows whose designation is null. On this
 * deployment that is the `ec` type, which is an endpoint rather than a person.
 */
internal fun PhoneBookRow.toChatContact(): ChatContact? {
    val extension = offExtension?.takeIf { it.isNotBlank() }
    val username = designation?.takeIf { it.isNotBlank() }
    val id = username ?: extension ?: return null

    return ChatContact(
        id = id,
        username = username,
        // `name` is null on every row of this deployment, so this is the extension in
        // practice. Kept as the preference because a directory that grows real names
        // should start using them without a code change.
        displayName = name?.takeIf { it.isNotBlank() } ?: extension ?: id,
        extension = extension,
        department = department?.takeIf { it.isNotBlank() },
        // The platform's phonebook carries no avatar. Null lets the design system's
        // Avatar fall back to initials, which is what it is built to do.
        avatarUrl = null,
    )
}
