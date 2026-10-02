package com.whatsappv2.domain.chat

/**
 * How this deployment writes a person down: `8102 (mcx8102)`.
 *
 * Both halves, because neither alone is enough. The extension is what somebody recognises
 * and dials; the username — the directory's `designation` — is what the chat server
 * addresses and what the other person sees themselves as. The directory's `name` field is
 * null on every row of this deployment, so there is no third, friendlier option to prefer.
 *
 * Here as one function rather than on [ChatContact] because two places need the same
 * answer: a row in the directory, and the signed-in user's own identity in the Chats
 * header. Two copies of the formatting would drift, and the first sign would be a header
 * that disagrees with the list underneath it.
 *
 * Degrades rather than showing an empty bracket: extension only when there is no username,
 * username only when there is no extension, and [fallback] when there is neither.
 */
fun chatIdentityLabel(extension: String?, username: String?, fallback: String): String = when {
    extension.isNullOrBlank() -> username?.takeIf { it.isNotBlank() } ?: fallback
    username.isNullOrBlank() -> extension
    else -> "$extension ($username)"
}
