package com.whatsappv2.feature.chat.group

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.text.style.TextOverflow
import com.whatsappv2.core.designsystem.component.AppTopBar
import com.whatsappv2.core.designsystem.component.Avatar
import com.whatsappv2.core.designsystem.theme.AppTheme
import com.whatsappv2.domain.chat.ChatContact

/**
 * A name, and up to three other people.
 *
 * ## Why the cap is a disabled row rather than an error
 *
 * At the limit the unticked rows stop responding and the header says `4 of 4`. The
 * alternative — letting a fifth tick go on and refusing on submit — teaches the rule only
 * after the user has done the work of choosing. Already-ticked rows stay live, because
 * un-choosing must never be blocked by a limit on choosing.
 *
 * The limit itself is `ChatGroup.MAX_MEMBERS`, and it is a calling limit rather than a
 * messaging one: a group this app makes is capped at the size it can also ring. That number
 * is the app's own four-party ceiling rather than something the conference bridge imposes —
 * `ChatGroup.isCallable` has the argument.
 */
@Composable
fun CreateGroupScreen(
    state: CreateGroupUiState,
    onNameChange: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onToggle: (String) -> Unit,
    onCreate: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AppTopBar(
                title = "New group",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // The count lives in the bar rather than beside the button: it is the
                    // rule, and it has to be readable while choosing rather than at the end.
                    Text(
                        text = state.countLabel,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .padding(end = AppTheme.spacing.large)
                            .testTag(TAG_COUNT),
                    )
                },
            )
        },
        floatingActionButton = {
            if (state.canCreate) {
                ExtendedFloatingActionButton(
                    onClick = onCreate,
                    modifier = Modifier.testTag(TAG_CREATE),
                ) { Text("Create") }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            GroupFields(state = state, onNameChange = onNameChange, onQueryChange = onQueryChange)

            state.failure?.let { failure ->
                Text(
                    text = failure.describe(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(horizontal = AppTheme.spacing.large)
                        .testTag(TAG_FAILURE),
                )
            }

            LazyColumn(modifier = Modifier.fillMaxSize().testTag(TAG_LIST)) {
                items(state.contacts, key = { it.id }) { contact ->
                    MemberRow(
                        contact = contact,
                        selected = state.isSelected(contact.id),
                        enabled = state.isSelectable(contact.id),
                        onToggle = { onToggle(contact.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** The name, and the search over the directory. */
@Composable
private fun GroupFields(
    state: CreateGroupUiState,
    onNameChange: (String) -> Unit,
    onQueryChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.spacing.large, vertical = AppTheme.spacing.small),
    ) {
        OutlinedTextField(
            value = state.name,
            onValueChange = onNameChange,
            singleLine = true,
            label = { Text("Group name") },
            modifier = Modifier.fillMaxWidth().testTag(TAG_NAME),
        )
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text("Search the directory") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = AppTheme.spacing.small)
                .testTag(TAG_SEARCH),
        )
    }
}

/**
 * One person, with a tick.
 *
 * The whole row toggles rather than just the checkbox — a 24 dp target for a 56 dp row is a
 * miss waiting to happen — and it is `toggleable` rather than `clickable` so the row
 * announces itself as a checkbox to a screen reader.
 */
@Composable
private fun MemberRow(
    contact: ChatContact,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = selected, enabled = enabled, onValueChange = { onToggle() })
            .padding(horizontal = AppTheme.spacing.large, vertical = AppTheme.spacing.medium),
    ) {
        Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)

        Avatar(
            displayName = contact.displayName,
            photoUri = contact.avatarUrl,
            modifier = Modifier.padding(start = AppTheme.spacing.medium),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = AppTheme.spacing.large),
        ) {
            Text(
                text = contact.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Greyed with the row, so "you cannot add a fifth" is visible rather than
                // only discoverable by tapping and getting nothing.
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            contact.department?.let { department ->
                Text(
                    text = department,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal const val TAG_NAME = "create-group-name"
internal const val TAG_SEARCH = "create-group-search"
internal const val TAG_LIST = "create-group-list"
internal const val TAG_CREATE = "create-group-create"
internal const val TAG_COUNT = "create-group-count"
internal const val TAG_FAILURE = "create-group-failure"
