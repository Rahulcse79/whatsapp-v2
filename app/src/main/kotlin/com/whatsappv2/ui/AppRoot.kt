package com.whatsappv2.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.whatsappv2.core.designsystem.component.EmptyState
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews

/**
 * The app's root composable.
 *
 * A placeholder until Task 15 adds navigation and the real destinations. It is not a
 * stub for its own sake: it exercises the theme, the design system and edge-to-edge
 * insets on a real device, which is how Task 14's done-when is actually verified rather
 * than assumed.
 *
 * `Scaffold` consumes the window insets, so nothing here hardcodes a system-bar height.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("whatsapp-v2") }) },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            EmptyState(
                title = "No SIP accounts yet",
                description = "Account setup arrives in Task 20. The theme, insets and " +
                    "design system are live.",
                icon = Icons.Filled.Dialpad,
            )
        }
    }
}

@ThemePreviews
@Composable
private fun AppRootPreview() = PreviewSurface { AppRoot() }
