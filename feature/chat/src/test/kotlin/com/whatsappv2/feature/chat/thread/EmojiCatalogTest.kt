package com.whatsappv2.feature.chat.thread

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The catalogue is data, and these are the two ways data like this goes wrong.
 *
 * The first one is not a tidiness check. `EmojiPicker`'s grid keys each cell on the emoji
 * itself, and Compose throws `IllegalArgumentException: Key was already used` when two
 * items in one list share a key — so a duplicate inside a category crashes the panel the
 * moment that tab is opened, on a device, in front of somebody.
 */
class EmojiCatalogTest {

    @Test
    fun `no category repeats an emoji, because the grid keys on it`() {
        EMOJI_CATEGORIES.forEach { category ->
            val duplicates = category.emoji.groupingBy { it }.eachCount().filterValues { it > 1 }

            assertEquals(emptyMap(), duplicates, "${category.name} repeats ${duplicates.keys}")
        }
    }

    @Test
    fun `every category has a name, an icon and something to show`() {
        EMOJI_CATEGORIES.forEach { category ->
            assertTrue(category.name.isNotBlank(), "a category has no name")
            assertTrue(category.icon.isNotBlank(), "${category.name} has no tab icon")
            assertTrue(category.emoji.isNotEmpty(), "${category.name} is empty")
        }
    }

    @Test
    fun `the tab icon is one of the category's own emoji, so the tab says what is behind it`() {
        EMOJI_CATEGORIES.forEach { category ->
            assertTrue(
                category.icon in category.emoji,
                "${category.name}'s tab shows ${category.icon}, which is not in it",
            )
        }
    }

    @Test
    fun `the categories are distinct from each other`() {
        val names = EMOJI_CATEGORIES.map { it.name }
        val icons = EMOJI_CATEGORIES.map { it.icon }

        assertEquals(names.distinct(), names, "two categories share a name")
        assertEquals(icons.distinct(), icons, "two tabs show the same emoji")
    }
}
