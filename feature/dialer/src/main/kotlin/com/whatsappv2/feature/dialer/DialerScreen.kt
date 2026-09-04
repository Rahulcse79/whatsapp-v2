package com.whatsappv2.feature.dialer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.whatsappv2.core.designsystem.component.EmptyState
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews

/**
 * Dialer.
 *
 * A placeholder destination: it exists so navigation, rotation and the bottom bar can be
 * verified now, before any of the real behaviour lands. It renders the shared
 * [EmptyState] rather than ad-hoc text, so it is already consistent with the finished
 * screen and the design-system rules apply to it.
 */
@Composable
fun DialerScreen(modifier: Modifier = Modifier) {
    EmptyState(
        title = "Dialer",
        description = "Placing calls arrives in Task 36, once registration works.",
        icon = Icons.Filled.Dialpad,
        modifier = modifier,
    )
}

@ThemePreviews
@Composable
private fun DialerScreenPreview() = PreviewSurface { DialerScreen() }
