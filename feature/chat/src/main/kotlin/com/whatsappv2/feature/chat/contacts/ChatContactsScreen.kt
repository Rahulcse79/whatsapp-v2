package com.whatsappv2.feature.chat.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.whatsappv2.core.designsystem.component.AppTopBar
import com.whatsappv2.core.designsystem.component.Avatar
import com.whatsappv2.core.designsystem.component.EmptyState
import com.whatsappv2.core.designsystem.component.ErrorState
import com.whatsappv2.core.designsystem.component.LoadingState
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme
import com.whatsappv2.domain.chat.ChatAuthError
import com.whatsappv2.domain.chat.ChatContact
import com.whatsappv2.feature.chat.signin.describe

/**
 * Who this account can message.
 *
 * ## These are server contacts, and that is not a naming quibble
 *
 * Every row here comes from the company's own directory over the network. The device
 * address book — `:data:contacts`, [com.whatsappv2.domain.contacts.Contact] — is a
 * different thing: somebody else's personal data, held on loan to put a name on a ringing
 * screen, which architecture rule 9 forbids from leaving the device. Nothing in this
 * screen reads it and nothing in this screen may.
 *
 * ## Four states, and the empty one is not an error
 *
 * Loading, empty, error-with-rows, error-without. "You have no contacts yet" and "we could
 * not load your contacts" look similar and mean opposite things; `StateViews` keeps them
 * apart, and a failed refresh over an existing list shows both rather than replacing one
 * with the other.
 */
@Composable
fun ChatContactsScreen(
    state: ChatContactsUiState,
    onQueryChange: (String) -> Unit,
    onContactSelected: (ChatContact) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AppTopBar(
                title = "New conversation",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                below = if (state.isSearchable) {
                    {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = onQueryChange,
                            singleLine = true,
                            placeholder = { Text("Search the directory") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = AppTheme.spacing.large,
                                    vertical = AppTheme.spacing.small,
                                )
                                .testTag(TAG_SEARCH),
                        )
                    }
                } else {
                    null
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // An error over rows is a strip, not a takeover: the directory already on
            // screen is still the most useful thing there.
            if (state.error != null && state.contacts.isNotEmpty()) {
                ContactsErrorStrip(error = state.error, onRetry = onRetry)
            }

            ContactsBody(state = state, onContactSelected = onContactSelected, onRetry = onRetry)
        }
    }
}

/** The four states, chosen in one place so none of them can be reached two ways. */
@Composable
private fun ContactsBody(
    state: ChatContactsUiState,
    onContactSelected: (ChatContact) -> Unit,
    onRetry: () -> Unit,
) {
    when {
        state.contacts.isEmpty() && state.isLoading ->
            LoadingState(label = "Loading contacts")

        state.contacts.isEmpty() && state.error != null ->
            ErrorState(
                title = "Could not load contacts",
                description = state.error.describe(),
                // No retry for something retrying cannot fix - an expired session needs a
                // sign-in, and "Try again" would invite an action that cannot succeed.
                onRetry = onRetry.takeIf { state.error.isRetryable },
                modifier = Modifier.testTag(TAG_ERROR),
            )

        state.isEmpty && state.query.isNotEmpty() ->
            EmptyState(
                title = "Nobody matches “${state.query}”",
                icon = Icons.Filled.PersonSearch,
                modifier = Modifier.testTag(TAG_EMPTY),
            )

        state.isEmpty ->
            EmptyState(
                title = "No contacts yet",
                description = "When your directory has people in it, they will appear here.",
                modifier = Modifier.testTag(TAG_EMPTY),
            )

        else -> LazyColumn(modifier = Modifier.fillMaxSize().testTag(TAG_LIST)) {
            items(state.contacts, key = { it.id }) { contact ->
                ContactRow(contact = contact, onClick = { onContactSelected(contact) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ContactRow(contact: ChatContact, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AppTheme.spacing.large, vertical = AppTheme.spacing.medium),
    ) {
        Avatar(displayName = contact.displayName, photoUri = contact.avatarUrl)

        Column(modifier = Modifier.padding(start = AppTheme.spacing.large)) {
            Text(text = contact.displayName, style = MaterialTheme.typography.bodyLarge)

            // The extension and the department, when the directory carries them. Joined
            // rather than stacked: two short facts on one line read faster than two lines.
            listOfNotNull(contact.extension, contact.department)
                .takeIf { it.isNotEmpty() }
                ?.let { details ->
                    Text(
                        text = details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }
    }
}

@Composable
private fun ContactsErrorStrip(error: ChatAuthError, onRetry: () -> Unit) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().testTag(TAG_ERROR_STRIP),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                horizontal = AppTheme.spacing.large,
                vertical = AppTheme.spacing.small,
            ),
        ) {
            Text(
                text = error.describe(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (error.isRetryable) {
                androidx.compose.material3.TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

internal const val TAG_SEARCH = "chat-contacts-search"
internal const val TAG_LIST = "chat-contacts-list"
internal const val TAG_EMPTY = "chat-contacts-empty"
internal const val TAG_ERROR = "chat-contacts-error"
internal const val TAG_ERROR_STRIP = "chat-contacts-error-strip"

private val SAMPLE = listOf(
    ChatContact("1001", "Rahul Singh", extension = "1001", department = "coral-test", avatarUrl = null),
    ChatContact("1005", "Priya Nair", extension = "1005", department = "coral-test", avatarUrl = null),
)

@ThemePreviews
@Composable
private fun ChatContactsPreview() = PreviewSurface {
    ChatContactsScreen(
        state = ChatContactsUiState(contacts = SAMPLE),
        onQueryChange = {},
        onContactSelected = {},
        onRetry = {},
        onBack = {},
    )
}

@ThemePreviews
@Composable
private fun ChatContactsEmptyPreview() = PreviewSurface {
    ChatContactsScreen(
        state = ChatContactsUiState(),
        onQueryChange = {},
        onContactSelected = {},
        onRetry = {},
        onBack = {},
    )
}
