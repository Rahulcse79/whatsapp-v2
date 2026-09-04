package com.whatsappv2.feature.settings

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme
import com.whatsappv2.domain.model.AppSettings
import com.whatsappv2.domain.model.DtmfMode
import com.whatsappv2.domain.model.PreferredAudioRoute
import com.whatsappv2.domain.model.SrtpPolicy

/** App preferences, wired to the ViewModel. */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScreen(
        state = state,
        onDtmfModeChange = viewModel::setDtmfMode,
        onSrtpPolicyChange = viewModel::setDefaultSrtpPolicy,
        onAudioRouteChange = viewModel::setPreferredAudioRoute,
        onSipTraceChange = viewModel::setSipTraceEnabled,
        modifier = modifier,
    )
}

/** The stateless screen, so it can be previewed and tested with a literal state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onDtmfModeChange: (DtmfMode) -> Unit,
    onSrtpPolicyChange: (SrtpPolicy) -> Unit,
    onAudioRouteChange: (PreferredAudioRoute) -> Unit,
    onSipTraceChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("App settings") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(AppTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.large),
        ) {
            ChoiceGroup(
                title = "DTMF",
                description = "RFC 4733 sends digits in the media stream and survives " +
                    "transcoding. SIP INFO is a fallback for gateways that cannot.",
                options = DtmfMode.entries,
                selected = state.settings.dtmfMode,
                labelOf = { if (it == DtmfMode.RFC_4733) "RFC 4733" else "SIP INFO" },
                onSelect = onDtmfModeChange,
            )

            ChoiceGroup(
                title = "Default media encryption",
                description = "Applies to new accounts. Mandatory means a call fails " +
                    "rather than connecting without encryption.",
                options = SrtpPolicy.entries,
                selected = state.settings.defaultSrtpPolicy,
                labelOf = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                onSelect = onSrtpPolicyChange,
            )

            ChoiceGroup(
                title = "Audio route",
                description = "Where calls start. Automatic follows a connected headset.",
                options = PreferredAudioRoute.entries,
                selected = state.settings.preferredAudioRoute,
                labelOf = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                onSelect = onAudioRouteChange,
            )

            // Absent in release builds rather than disabled: a disabled control invites
            // someone to make it enableable.
            if (state.traceToggleAvailable) {
                SipTraceToggle(
                    enabled = state.settings.sipTraceEnabled,
                    onChange = onSipTraceChange,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceGroup(
    title: String,
    description: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.small)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.small)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(labelOf(option)) },
                )
            }
        }
    }
}

@Composable
private fun SipTraceToggle(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.small)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("SIP trace", style = MaterialTheme.typography.titleMedium)
            Switch(checked = enabled, onCheckedChange = onChange)
        }
        Text(
            // Says what is and is not written, because "enable logging" tells a user
            // nothing about what they are exposing.
            text = "Writes SIP signalling to the device log for diagnosis. Passwords and " +
                "authentication headers are always removed. Debug builds only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@ThemePreviews
@Composable
private fun SettingsScreenPreview() = PreviewSurface {
    SettingsScreen(
        state = SettingsUiState(AppSettings.DEFAULT, traceToggleAvailable = true),
        onDtmfModeChange = {},
        onSrtpPolicyChange = {},
        onAudioRouteChange = {},
        onSipTraceChange = {},
    )
}
