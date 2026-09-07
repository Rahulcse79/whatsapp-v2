package com.whatsappv2.feature.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.whatsappv2.core.designsystem.component.Avatar
import com.whatsappv2.core.designsystem.component.CallActionButton
import com.whatsappv2.core.designsystem.component.CallActionStyle
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme
import com.whatsappv2.domain.contacts.SipContact

/**
 * Build a group and call it (Task 78).
 *
 * Stateless, so it renders from a literal state in a test and a preview with nothing
 * behind it — the same arrangement the dialler and the history list use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupCallScreen(
    state: GroupCallUiState,
    actions: GroupCallActions,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Group call") },
                navigationIcon = {
                    IconButton(onClick = actions.onBack, modifier = Modifier.testTag(TAG_BACK)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to calls",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(AppTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.large),
        ) {
            HowThisWorks()

            OutlinedTextField(
                value = state.name,
                onValueChange = actions.onNameChanged,
                singleLine = true,
                label = { Text("Group name") },
                placeholder = { Text("Monday standup") },
                modifier = Modifier.fillMaxWidth().testTag(TAG_NAME),
            )

            OutlinedTextField(
                value = state.conferenceAddress,
                onValueChange = actions.onAddressChanged,
                singleLine = true,
                label = { Text("Conference address") },
                placeholder = { Text("3000 or sip:3000@example.com") },
                supportingText = { Text("The bridge everyone dials in to. This is what is called.") },
                modifier = Modifier.fillMaxWidth().testTag(TAG_ADDRESS),
            )

            Members(state = state, actions = actions)

            CallButtons(state = state, actions = actions)
        }
    }
}

/**
 * What this page is, said plainly.
 *
 * Not decoration. A screen that lets someone name a group and add people to it looks like
 * it created something on a server, and it did not: the list is on this phone and the call
 * is a dial-in. Leaving that unsaid would be the screen implying a feature that does not
 * exist (ADR-003, §2.2).
 */
@Composable
private fun HowThisWorks() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.medium),
        modifier = Modifier.testTag(TAG_EXPLAINER),
    ) {
        Icon(
            imageVector = Icons.Filled.Groups,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "This group is saved on this device only. Starting a call dials the " +
                "conference address below, and everyone else dials the same address to join.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Members(state: GroupCallUiState, actions: GroupCallActions) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.small)) {
        Text("Members", style = MaterialTheme.typography.titleMedium)

        if (state.members.isEmpty()) {
            Text(
                text = "Optional — a conference works without them. They are a reminder of " +
                    "who you meant to invite.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.small),
                modifier = Modifier.fillMaxWidth().testTag(TAG_MEMBERS),
            ) {
                items(state.members) { member ->
                    InputChip(
                        selected = true,
                        onClick = { actions.onRemoveMember(member) },
                        label = { Text(member.contact.displayName) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Remove ${member.contact.displayName}",
                            )
                        },
                        modifier = Modifier.testTag(memberTag(member)),
                    )
                }
            }
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = actions.onQueryChanged,
            singleLine = true,
            label = { Text("Add someone") },
            modifier = Modifier.fillMaxWidth().testTag(TAG_QUERY),
        )

        // Hidden when empty, exactly as the dialler's picker is: a user who declined
        // READ_CONTACTS is never shown a blank space where a list should be (Task 50).
        if (state.matches.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.small),
                modifier = Modifier.fillMaxWidth().testTag(TAG_MATCHES),
            ) {
                items(state.matches) { match ->
                    AssistChip(
                        onClick = { actions.onAddMember(match) },
                        label = { Text(match.contact.displayName) },
                        leadingIcon = {
                            Avatar(
                                displayName = match.contact.displayName,
                                photoUri = match.contact.photoUri,
                                size = AppTheme.sizing.avatarSmall,
                            )
                        },
                        modifier = Modifier.testTag(matchTag(match)),
                    )
                }
            }
        }
    }
}

@Composable
private fun CallButtons(state: GroupCallUiState, actions: GroupCallActions) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.extraLarge, Alignment.CenterHorizontally),
    ) {
        CallActionButton(
            icon = Icons.Filled.Call,
            contentDescription = "Start an audio group call",
            onClick = actions.onStartAudioCall,
            style = CallActionStyle.ANSWER,
            enabled = state.canJoin,
            label = "Audio",
            modifier = Modifier.testTag(TAG_START_AUDIO),
        )
        CallActionButton(
            icon = Icons.Filled.Videocam,
            contentDescription = "Start a video group call",
            onClick = actions.onStartVideoCall,
            style = CallActionStyle.ANSWER,
            enabled = state.canJoin,
            label = "Video",
            modifier = Modifier.testTag(TAG_START_VIDEO),
        )
    }
}

internal const val TAG_NAME = "group-name"
internal const val TAG_ADDRESS = "group-address"
internal const val TAG_QUERY = "group-query"
internal const val TAG_MEMBERS = "group-members"
internal const val TAG_MATCHES = "group-matches"
internal const val TAG_START_AUDIO = "group-start-audio"
internal const val TAG_START_VIDEO = "group-start-video"
internal const val TAG_BACK = "group-back"
internal const val TAG_EXPLAINER = "group-explainer"

internal fun memberTag(member: SipContact) = "group-member-${member.address.render()}"
internal fun matchTag(match: SipContact) = "group-match-${match.address.render()}"

@ThemePreviews
@Composable
private fun GroupCallPreview() = PreviewSurface {
    GroupCallScreen(
        state = GroupCallUiState(name = "Monday standup", conferenceAddress = "3000"),
        actions = GroupCallActions(),
        snackbarHostState = remember { SnackbarHostState() },
    )
}
