package com.whatsappv2.feature.accounts.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.validation.AccountField
import com.whatsappv2.domain.validation.AccountViolation
import com.whatsappv2.domain.validation.SipAccountDraft

/**
 * The account form.
 *
 * Grouped into Identity, Server, Transport and NAT, and Media and Security, with the
 * advanced groups after the ones every account needs. A flat list of eighteen fields
 * makes the four that matter impossible to find.
 *
 * Errors are shown per field. `supportingText` carries the message so a screen reader
 * announces it with the field rather than leaving the user to hunt for what is wrong.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountEditorScreen(
    state: AccountEditorUiState,
    onDraftChange: ((SipAccountDraft) -> SipAccountDraft) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNewAccount) "Add account" else "Edit account") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(AppTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.medium),
        ) {
            IdentitySection(state, onDraftChange)
            ServerSection(state, onDraftChange)
            TransportSection(state, onDraftChange)
            MediaSection(state, onDraftChange)

            state.warnings.forEach { warning ->
                Text(
                    text = warning.describe(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            Button(
                onClick = onSave,
                enabled = !state.isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AppTheme.spacing.large),
            ) {
                Text(if (state.isSaving) "Saving..." else "Save account")
            }
        }
    }
}

/** Who the account is. The four fields every account needs come first. */
@Composable
private fun IdentitySection(
    state: AccountEditorUiState,
    onDraftChange: ((SipAccountDraft) -> SipAccountDraft) -> Unit,
) {
    SectionHeader("Identity")
    Field("Label", state.draft.label, state.errorFor(AccountField.LABEL)) { value ->
        onDraftChange { it.copy(label = value) }
    }
    Field("Username", state.draft.username, state.errorFor(AccountField.USERNAME)) { value ->
        onDraftChange { it.copy(username = value) }
    }
    Field("Extension", state.draft.extension, state.errorFor(AccountField.EXTENSION)) { value ->
        onDraftChange { it.copy(extension = value) }
    }
    Field(
        label = "Display name",
        value = state.draft.displayName,
        error = state.errorFor(AccountField.DISPLAY_NAME),
    ) { value -> onDraftChange { it.copy(displayName = value) } }
}

/** Where it registers, and with what credentials. */
@Composable
private fun ServerSection(
    state: AccountEditorUiState,
    onDraftChange: ((SipAccountDraft) -> SipAccountDraft) -> Unit,
) {
    SectionHeader("Server")
    Field("SIP domain", state.draft.domain, state.errorFor(AccountField.DOMAIN)) { value ->
        onDraftChange { it.copy(domain = value) }
    }
    Field(
        label = "Authentication username",
        value = state.draft.authUsername,
        error = state.errorFor(AccountField.AUTH_USERNAME),
        supporting = "Defaults to the username",
    ) { value -> onDraftChange { it.copy(authUsername = value) } }
    PasswordField(
        label = if (state.isNewAccount) "Password" else "New password",
        value = state.draft.password,
        error = state.errorFor(AccountField.PASSWORD),
        // An existing account's password is never loaded back, so a blank field means
        // "unchanged" rather than "empty".
        supporting = if (state.isNewAccount) null else "Leave blank to keep the current password",
    ) { value -> onDraftChange { it.copy(password = value) } }
    Field(
        label = "Registrar",
        value = state.draft.registrar,
        error = state.errorFor(AccountField.REGISTRAR),
        supporting = "Optional. Defaults to the SIP domain",
    ) { value -> onDraftChange { it.copy(registrar = value) } }
    Field(
        label = "Outbound proxy",
        value = state.draft.outboundProxy,
        error = state.errorFor(AccountField.OUTBOUND_PROXY),
    ) { value -> onDraftChange { it.copy(outboundProxy = value) } }
}

/** Transport, ports and NAT traversal. Advanced, so it sits below the essentials. */
@Composable
private fun TransportSection(
    state: AccountEditorUiState,
    onDraftChange: ((SipAccountDraft) -> SipAccountDraft) -> Unit,
) {
    SectionHeader("Transport and NAT")
    TransportChips(state.draft.transport) { value -> onDraftChange { it.copy(transport = value) } }
    Field(
        label = "Port",
        value = state.draft.port,
        error = state.errorFor(AccountField.PORT),
        supporting = "Optional. Defaults to ${state.draft.transport.defaultPort}",
        keyboardType = KeyboardType.Number,
    ) { value -> onDraftChange { it.copy(port = value) } }
    Field(
        label = "Registration expiry (seconds)",
        value = state.draft.registrationExpirySeconds,
        error = state.errorFor(AccountField.REGISTRATION_EXPIRY),
        keyboardType = KeyboardType.Number,
    ) { value -> onDraftChange { it.copy(registrationExpirySeconds = value) } }
    SwitchRow("Enable ICE", state.draft.iceEnabled) { value ->
        onDraftChange { it.copy(iceEnabled = value) }
    }
    SwitchRow("Enable STUN", state.draft.stunEnabled) { value ->
        onDraftChange { it.copy(stunEnabled = value) }
    }
    Field(
        label = "Keepalive interval (seconds)",
        value = state.draft.keepaliveIntervalSeconds,
        error = state.errorFor(AccountField.KEEPALIVE_INTERVAL),
        keyboardType = KeyboardType.Number,
    ) { value -> onDraftChange { it.copy(keepaliveIntervalSeconds = value) } }
    Field(
        label = "STUN server",
        value = state.draft.stunServer,
        error = state.errorFor(AccountField.STUN_SERVER),
    ) { value -> onDraftChange { it.copy(stunServer = value) } }
    Field(
        label = "TURN server",
        value = state.draft.turnServer,
        error = state.errorFor(AccountField.TURN_SERVER),
    ) { value -> onDraftChange { it.copy(turnServer = value) } }
    Field(
        label = "TURN username",
        value = state.draft.turnUsername,
        error = state.errorFor(AccountField.TURN_USERNAME),
    ) { value -> onDraftChange { it.copy(turnUsername = value) } }
    PasswordField(
        label = "TURN password",
        value = state.draft.turnPassword,
        error = state.errorFor(AccountField.TURN_PASSWORD),
    ) { value -> onDraftChange { it.copy(turnPassword = value) } }
}

/** Encryption policy and codec preferences. */
@Composable
private fun MediaSection(
    state: AccountEditorUiState,
    onDraftChange: ((SipAccountDraft) -> SipAccountDraft) -> Unit,
) {
    SectionHeader("Media and security")
    SrtpChips(state.draft.srtpPolicy) { value -> onDraftChange { it.copy(srtpPolicy = value) } }
    AudioCodecChips(state.draft, state.errorFor(AccountField.AUDIO_CODECS), onDraftChange)
    VideoCodecChips(state.draft, onDraftChange)
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = AppTheme.spacing.large),
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    error: AccountViolation?,
    supporting: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error != null,
        singleLine = true,
        supportingText = (error?.describe() ?: supporting)?.let { { Text(it) } },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PasswordField(
    label: String,
    value: Secret,
    error: AccountViolation?,
    supporting: String? = null,
    onChange: (Secret) -> Unit,
) {
    OutlinedTextField(
        value = value.reveal(),
        onValueChange = { onChange(Secret(it)) },
        label = { Text(label) },
        isError = error != null,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        supportingText = (error?.describe() ?: supporting)?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TransportChips(
    selected: Transport,
    onSelect: (Transport) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.small)) {
        ALL_TRANSPORTS.forEach { transport ->
            FilterChip(
                selected = transport == selected,
                onClick = { onSelect(transport) },
                label = { Text(transport.name) },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SrtpChips(
    selected: SrtpPolicy,
    onSelect: (SrtpPolicy) -> Unit,
) {
    Column {
        Text("Media encryption", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.small)) {
            ALL_SRTP_POLICIES.forEach { policy ->
                FilterChip(
                    selected = policy == selected,
                    onClick = { onSelect(policy) },
                    label = { Text(policy.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }
        if (selected == SrtpPolicy.MANDATORY) {
            Text(
                // Says what Mandatory actually does, because the consequence is a failed
                // call rather than a warning (DoD 13).
                text = "Calls will fail rather than connect without encryption.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioCodecChips(
    draft: SipAccountDraft,
    error: AccountViolation?,
    onDraftChange: ((SipAccountDraft) -> SipAccountDraft) -> Unit,
) {
    Column {
        Text("Audio codecs", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.small)) {
            ALL_AUDIO_CODECS.forEach { codec ->
                val selected = codec in draft.audioCodecs
                FilterChip(
                    selected = selected,
                    onClick = {
                        onDraftChange { current ->
                            current.copy(
                                audioCodecs = if (selected) {
                                    current.audioCodecs - codec
                                } else {
                                    current.audioCodecs + codec
                                },
                            )
                        }
                    },
                    label = { Text(codec.payloadName) },
                )
            }
        }
        error?.let {
            Text(it.describe(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoCodecChips(
    draft: SipAccountDraft,
    onDraftChange: ((SipAccountDraft) -> SipAccountDraft) -> Unit,
) {
    Column {
        Text("Video codecs", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.small)) {
            ALL_VIDEO_CODECS.forEach { codec ->
                val selected = codec in draft.videoCodecs
                FilterChip(
                    selected = selected,
                    onClick = {
                        onDraftChange { current ->
                            current.copy(
                                videoCodecs = if (selected) {
                                    current.videoCodecs - codec
                                } else {
                                    current.videoCodecs + codec
                                },
                            )
                        }
                    },
                    label = { Text(codec.payloadName) },
                )
            }
        }
    }
}

/**
 * Turns a typed violation into a sentence.
 *
 * The wording lives in the UI layer on purpose: the validator returns structured
 * violations precisely so it does not have to know about language or tone.
 */
internal fun AccountViolation.describe(): String = when (this) {
    is AccountViolation.Required -> "Required"
    is AccountViolation.NotANumber -> "Must be a number"
    is AccountViolation.OutOfRange -> "Must be between $min and $max"
    is AccountViolation.Malformed -> detail.replaceFirstChar { it.uppercase() }
    is AccountViolation.Conflict -> detail.replaceFirstChar { it.uppercase() }
}

@ThemePreviews
@Composable
private fun AccountEditorPreview() = PreviewSurface {
    AccountEditorScreen(
        state = AccountEditorUiState(
            draft = SipAccountDraft(
                id = AccountId("preview"),
                label = "Work",
                username = "alice",
                domain = "sip.example.com",
            ),
            isNewAccount = true,
        ),
        onDraftChange = {},
        onSave = {},
        onBack = {},
    )
}
