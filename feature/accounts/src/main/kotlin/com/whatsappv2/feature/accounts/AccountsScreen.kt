package com.whatsappv2.feature.accounts

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.whatsappv2.core.designsystem.component.EmptyState
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews

/**
 * SIP accounts.
 *
 * A placeholder destination: it exists so navigation, rotation and the bottom bar can be
 * verified now, before any of the real behaviour lands. It renders the shared
 * [EmptyState] rather than ad-hoc text, so it is already consistent with the finished
 * screen and the design-system rules apply to it.
 */
@Composable
fun AccountsScreen(modifier: Modifier = Modifier) {
    EmptyState(
        title = "SIP accounts",
        description = "Adding and editing accounts arrives in Tasks 20 and 21.",
        icon = Icons.Filled.AccountCircle,
        modifier = modifier,
    )
}

@ThemePreviews
@Composable
private fun AccountsScreenPreview() = PreviewSurface { AccountsScreen() }
