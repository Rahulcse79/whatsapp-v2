package com.whatsappv2.ui.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme

/**
 * The Chats tab, until the module that fills it arrives.
 *
 * ## Why a placeholder ships rather than the tab waiting
 *
 * Because the expensive part of adding a tab is not the screen, it is the navigation: the
 * route, the bar entry, the back-stack behaviour, the insets, and what happens when the
 * user switches away mid-task. Settling all of that now against a screen that draws one
 * sentence means the team building chat replaces a composable, not an app shell.
 *
 * It says what it is. A blank tab reads as a bug, and a spinner reads as something that is
 * about to finish — this is neither, and saying so plainly costs one line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsPlaceholderScreen(modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Chats") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(AppTheme.spacing.extraLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Chat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .padding(AppTheme.spacing.large)
                        .size(AppTheme.spacing.extraLarge),
                )
            }

            Text(
                text = "Messages are coming",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = AppTheme.spacing.large),
            )
            Text(
                text = "Chat is being built separately. Calls work as they always have, " +
                    "and this tab will fill in when it lands.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = AppTheme.spacing.small),
            )
        }
    }
}

@ThemePreviews
@Composable
private fun ChatsPlaceholderPreview() = PreviewSurface { ChatsPlaceholderScreen() }
