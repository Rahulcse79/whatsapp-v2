package com.whatsappv2.feature.history

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.whatsappv2.core.designsystem.component.EmptyState
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews

/**
 * Call history.
 *
 * A placeholder destination: it exists so navigation, rotation and the bottom bar can be
 * verified now, before any of the real behaviour lands. It renders the shared
 * [EmptyState] rather than ad-hoc text, so it is already consistent with the finished
 * screen and the design-system rules apply to it.
 */
@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    EmptyState(
        title = "Call history",
        description = "Calls you make and receive will appear here from Task 48.",
        icon = Icons.Filled.History,
        modifier = modifier,
    )
}

@ThemePreviews
@Composable
private fun HistoryScreenPreview() = PreviewSurface { HistoryScreen() }
