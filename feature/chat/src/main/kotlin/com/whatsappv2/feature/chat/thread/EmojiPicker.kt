package com.whatsappv2.feature.chat.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.whatsappv2.core.designsystem.theme.AppTheme

/**
 * The emoji panel under the composer.
 *
 * ## Why a panel rather than the keyboard's own emoji key
 *
 * Every soft keyboard has one, and on a phone whose keyboard hides it — or whose owner has
 * never found it — "can I send a 👍" is answered by the app or not at all. It is also what
 * this screen is modelled on: the reference client puts the smiley in the composer.
 *
 * ## It replaces the keyboard rather than stacking on it
 *
 * Both want the bottom third of the screen, and a panel above a keyboard leaves the
 * conversation as a sliver. [ChatThreadScreen] closes one when it opens the other, so the
 * composer stays where it is and only what is under it changes.
 *
 * Choosing an emoji does not close the panel — nobody sends exactly one.
 */
@Composable
internal fun EmojiPicker(
    onEmoji: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Saveable, so a rotation does not drop somebody back on Smileys halfway through
    // picking a train. An Int rather than the category: the list itself is a constant.
    var selected by rememberSaveable { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(AppTheme.sizing.emojiPanelHeight)
            .background(AppTheme.chatColors.composer)
            .testTag(TAG_EMOJI_PICKER),
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            EMOJI_CATEGORIES.forEachIndexed { index, category ->
                // The selected tab is marked by the page's own tint behind it rather than
                // by dimming the others: an emoji carries its own colours, and a greyed
                // one reads as broken rather than as unselected.
                Surface(
                    color = if (index == selected) {
                        AppTheme.chatColors.background
                    } else {
                        Color.Transparent
                    },
                    shape = CircleShape,
                ) {
                    TextButton(
                        onClick = { selected = index },
                        modifier = Modifier.semantics { contentDescription = category.name },
                    ) {
                        Text(text = category.icon, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        HorizontalDivider(color = AppTheme.chatColors.background)

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = AppTheme.sizing.emojiCell),
            contentPadding = PaddingValues(AppTheme.spacing.small),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(EMOJI_CATEGORIES[selected].emoji, key = { it }) { emoji ->
                TextButton(onClick = { onEmoji(emoji) }) {
                    Text(
                        text = emoji,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

internal const val TAG_EMOJI_PICKER = "chat-thread-emoji-picker"
internal const val TAG_EMOJI_TOGGLE = "chat-thread-emoji-toggle"
