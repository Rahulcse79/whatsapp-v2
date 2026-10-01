package com.whatsappv2.feature.chat.thread

/**
 * One tab of the emoji picker, and the emoji behind it.
 *
 * Its own file because it is the shape both halves agree on: `EmojiCatalog` fills it and
 * `EmojiPicker` draws it, and neither owns it more than the other.
 */
internal data class EmojiCategory(
    /** What the tab shows. An emoji itself, because a word here would need translating. */
    val icon: String,
    /** Named for the content description, which is what a screen reader announces. */
    val name: String,
    val emoji: List<String>,
)
