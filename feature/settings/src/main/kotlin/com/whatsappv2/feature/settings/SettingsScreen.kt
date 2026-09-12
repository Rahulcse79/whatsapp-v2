package com.whatsappv2.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whatsappv2.core.designsystem.component.AppDropdownField
import com.whatsappv2.core.designsystem.component.AppTopBar
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme
import com.whatsappv2.domain.model.AppSettings
import com.whatsappv2.domain.model.CallHistoryRetention
import com.whatsappv2.domain.model.DtmfMode
import com.whatsappv2.domain.model.PreferredAudioRoute
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.ThemeMode

/**
 * App preferences, wired to the ViewModel — and the way into the account list (Task 69).
 *
 * Settings stopped being a tab and became the screen behind the gear at the top right of
 * Chats. The accounts list moved with it, because both are "set the app up" rather than
 * "make a call", and neither earns a permanent tab on a phone whose primary job is the
 * latter. Nothing here was removed: every control this screen had, it still has.
 */
@Composable
fun SettingsScreen(
    onOpenAccounts: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel) {
        SettingsActions(
            onDtmfModeChange = viewModel::setDtmfMode,
            onSrtpPolicyChange = viewModel::setDefaultSrtpPolicy,
            onAudioRouteChange = viewModel::setPreferredAudioRoute,
            onThemeModeChange = viewModel::setThemeMode,
            onSipTraceChange = viewModel::setSipTraceEnabled,
            onRetentionChange = viewModel::setCallHistoryRetention,
        )
    }

    SettingsScreen(
        state = state,
        actions = actions,
        links = SettingsLinks(onOpenAccounts = onOpenAccounts),
        onBack = onBack,
        modifier = modifier,
    )
}

/** Everything a setting can be changed to, in one value, so a preview and a test hand over one thing. */
data class SettingsActions(
    val onDtmfModeChange: (DtmfMode) -> Unit,
    val onSrtpPolicyChange: (SrtpPolicy) -> Unit,
    val onAudioRouteChange: (PreferredAudioRoute) -> Unit,
    val onThemeModeChange: (ThemeMode) -> Unit,
    val onSipTraceChange: (Boolean) -> Unit,
    val onRetentionChange: (CallHistoryRetention) -> Unit,
) {
    companion object {
        /** For previews and tests that are not about what a change does. */
        val NONE = SettingsActions({}, {}, {}, {}, {}, {})
    }
}

/**
 * The screens reachable from Settings.
 *
 * One value rather than one parameter each: they are absent together (a preview has
 * nowhere to go) and present together, and the content composable was one parameter
 * from the limit that keeps its signature readable.
 */
data class SettingsLinks(
    val onOpenAccounts: () -> Unit,
)

/** The stateless screen, so it can be previewed and tested with a literal state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    /** Where the accounts and recordings rows lead. Null in a preview, where there is nowhere to go. */
    links: SettingsLinks? = null,
    onBack: (() -> Unit)? = null,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AppTopBar(
                title = "Settings",
                navigationIcon = {
                    onBack?.let { back ->
                        IconButton(onClick = back, modifier = Modifier.testTag(TAG_BACK)) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        SettingsContent(
            state = state,
            actions = actions,
            links = links,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

/** The scrolling body, split out so the screen above it stays a layout. */
@Composable
private fun SettingsContent(
    state: SettingsUiState,
    actions: SettingsActions,
    links: SettingsLinks?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AppTheme.spacing.large),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.large),
    ) {
        LinkCards(links)

        Text("App settings", style = MaterialTheme.typography.titleLarge)

        // First among the app settings, because it is the one everybody understands and
        // the one whose effect is visible the instant a chip is pressed.
        SettingsCard {
            ChoiceGroup(
                title = "Appearance",
                description = "System follows the phone's own light and dark schedule.",
                options = ThemeMode.entries,
                selected = state.settings.themeMode,
                labelOf = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                onSelect = actions.onThemeModeChange,
                chipTag = ::themeChipTag,
            )
        }

        SettingsCard {
            ChoiceGroup(
                title = "DTMF",
                description = "RFC 4733 sends digits in the media stream and survives " +
                    "transcoding. SIP INFO is a fallback for gateways that cannot.",
                options = DtmfMode.entries,
                selected = state.settings.dtmfMode,
                labelOf = { if (it == DtmfMode.RFC_4733) "RFC 4733" else "SIP INFO" },
                onSelect = actions.onDtmfModeChange,
            )
        }

        SettingsCard {
            EncryptionGroup(selected = state.settings.defaultSrtpPolicy, onSelect = actions.onSrtpPolicyChange)
        }

        SettingsCard {
            ChoiceGroup(
                title = "Audio route",
                description = "Where calls start. Automatic follows a connected headset.",
                options = PreferredAudioRoute.entries,
                selected = state.settings.preferredAudioRoute,
                labelOf = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                onSelect = actions.onAudioRouteChange,
            )
        }

        SettingsCard {
            RetentionGroup(selected = state.settings.callHistoryRetention, onSelect = actions.onRetentionChange)
        }

        // Absent in release builds rather than disabled: a disabled control invites
        // someone to make it enableable.
        if (state.traceToggleAvailable) {
            SettingsCard {
                SipTraceToggle(
                    enabled = state.settings.sipTraceEnabled,
                    onChange = actions.onSipTraceChange,
                )
            }
        }

        // Last, and not on a card. It is not a setting — nothing here can be changed — and
        // putting it on one would invite a tap. Bottom of the scroll is where every app
        // this one sits beside keeps it, which is where somebody writing a bug report
        // already knows to look.
        AppVersionFooter()
    }
}

/**
 * The two places reachable from here, or nothing in a preview.
 *
 * Accounts first, because it is the one thing without which the app cannot do anything
 * at all.
 */
@Composable
private fun LinkCards(links: SettingsLinks?) {
    links ?: return
    SettingsCard { AccountsRow(onClick = links.onOpenAccounts) }
}

/**
 * One group of settings, on its own surface.
 *
 * The screen used to be a flat column separated by rules. Rules say "these are different";
 * a card says "these belong together", which is what a settings group actually is — and it
 * gives the eye somewhere to stop on a screen that is otherwise a wall of radio buttons.
 */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(AppTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.small),
            content = content,
        )
    }
}

/** The default SRTP policy, and what choosing Mandatory actually costs. */
@Composable
private fun EncryptionGroup(selected: SrtpPolicy, onSelect: (SrtpPolicy) -> Unit) {
    ChoiceGroup(
        title = "Default media encryption",
        description = "Applies to new accounts. Existing accounts keep their own. " +
            "Optional is refused by FreeSWITCH; use Mandatory where the server has SRTP.",
        options = SrtpPolicy.entries,
        selected = selected,
        labelOf = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
        onSelect = onSelect,
    )
    if (selected == SrtpPolicy.MANDATORY) {
        // The consequence is a failed call, not a warning banner, so it is stated in the
        // same words the account editor uses (DoD 13).
        Text(
            text = "Calls will fail rather than connect without encryption.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * How long the call log is kept, chosen from a list.
 *
 * A dropdown rather than a chip row, because nine options do not fit on a row — see
 * `AppDropdownField`. The description names both kinds of call on purpose: a user who
 * has just made a video call and wonders whether "call history" means that too should
 * find the answer here, not by waiting to see whether it disappears.
 */
@Composable
private fun RetentionGroup(selected: CallHistoryRetention, onSelect: (CallHistoryRetention) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.small)) {
        Text("Call history", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Calls older than this are removed automatically. Audio and video " +
                "calls are kept for the same time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AppDropdownField(
            label = "Keep call history for",
            options = CallHistoryRetention.PRESETS,
            selected = selected,
            labelOf = ::retentionLabel,
            onSelect = onSelect,
            optionTag = ::retentionOptionTag,
            modifier = Modifier.testTag(TAG_RETENTION),
        )
    }
}

/**
 * The words for one retention, in the dropdown and in the field.
 *
 * Plain "days" throughout rather than "3 weeks" or "1 year": the list is a scale, and a
 * scale whose units change halfway down is one the eye has to convert to compare.
 */
internal fun retentionLabel(retention: CallHistoryRetention): String = when {
    retention.keepsEverything -> "Forever"
    retention.days == 1 -> "1 day"
    else -> "${retention.days} days"
}

/**
 * The way through to the SIP accounts (Task 69).
 *
 * A row rather than the list inlined here: an account has a detail screen and an editor
 * behind it, and folding all three into a settings page would make one screen that does
 * four jobs. This is the entry point, and the list is still its own destination.
 */
@Composable
private fun AccountsRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AppTheme.spacing.small)
            .testTag(TAG_ACCOUNTS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.medium),
    ) {
        Icon(
            imageVector = Icons.Filled.AccountCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text("SIP accounts", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Add, edit and check the accounts this app registers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal const val TAG_ACCOUNTS = "settings-accounts"
internal const val TAG_BACK = "settings-back"
internal const val TAG_RETENTION = "settings-retention"

/** Identifies one retention option, so a test picks the length it means. */
internal fun retentionOptionTag(retention: CallHistoryRetention) = "settings-retention-${retention.days}"

/** Identifies one appearance chip, so a test presses the mode it means. */
internal fun themeChipTag(mode: ThemeMode) = "settings-theme-${mode.name.lowercase()}"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceGroup(
    title: String,
    description: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    /** A tag per chip, for the groups a test presses; null leaves the chips untagged. */
    chipTag: ((T) -> String)? = null,
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
                    modifier = chipTag?.let { Modifier.testTag(it(option)) } ?: Modifier,
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
        actions = SettingsActions.NONE,
    )
}
