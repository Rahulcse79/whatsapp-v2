package com.whatsappv2.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme

/**
 * A contact avatar: initials when a name is known, a person glyph otherwise.
 *
 * Marked decorative for accessibility. The caller's name is always displayed next to
 * it, and announcing initials as well would read the same person twice.
 */
@Composable
fun Avatar(
    displayName: String?,
    modifier: Modifier = Modifier,
    size: Dp = AppTheme.sizing.avatarSmall,
) {
    val initials = displayName?.toInitials()

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        if (initials.isNullOrEmpty()) {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(size / ICON_DIVISOR),
            )
        } else {
            Text(
                text = initials,
                style = if (size >= AppTheme.sizing.avatarLarge) {
                    MaterialTheme.typography.headlineMedium
                } else {
                    MaterialTheme.typography.titleMedium
                },
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

private const val ICON_DIVISOR = 2
private const val MAX_INITIALS = 2

/**
 * First letters of the first and last word.
 *
 * Uses code points rather than chars so an emoji or a non-BMP character does not get
 * cut in half — a mangled glyph looks like a rendering bug, not a name.
 */
private fun String.toInitials(): String = trim()
    .split(Regex("\\s+"))
    .filter { it.isNotBlank() }
    .let { words -> if (words.size <= MAX_INITIALS) words else listOf(words.first(), words.last()) }
    .mapNotNull { word -> word.codePoints().findFirst().takeIf { it.isPresent }?.asInt }
    .joinToString("") { String(Character.toChars(it)) }
    .uppercase()

@ThemePreviews
@Composable
private fun AvatarWithNamePreview() = PreviewSurface { Avatar(displayName = "Alice Example") }

@ThemePreviews
@Composable
private fun AvatarWithoutNamePreview() = PreviewSurface { Avatar(displayName = null) }
