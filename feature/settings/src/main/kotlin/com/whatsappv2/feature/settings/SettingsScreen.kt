package com.whatsappv2.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.whatsappv2.core.designsystem.component.EmptyState
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews

/**
 * Settings.
 *
 * A placeholder destination: it exists so navigation, rotation and the bottom bar can be
 * verified now, before any of the real behaviour lands. It renders the shared
 * [EmptyState] rather than ad-hoc text, so it is already consistent with the finished
 * screen and the design-system rules apply to it.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    EmptyState(
        title = "Settings",
        description = "App preferences arrive in Task 23.",
        icon = Icons.Filled.Settings,
        modifier = modifier,
    )
}

@ThemePreviews
@Composable
private fun SettingsScreenPreview() = PreviewSurface { SettingsScreen() }
