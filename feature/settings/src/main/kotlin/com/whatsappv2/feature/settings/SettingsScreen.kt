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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whatsappv2.core.designsystem.component.AppDropdownField
import com.whatsappv2.core.designsystem.component.AppTopBar
import com.whatsappv2.core.designsystem.component.ConfirmDialog
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme
import com.whatsappv2.domain.model.AppSettings
import com.whatsappv2.domain.model.CallHistoryRetention
import com.whatsappv2.domain.model.DtmfMode
import com.whatsappv2.domain.model.PreferredAudioRoute
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.ThemeMode
import com.whatsappv2.domain.video.VideoFrameRate

/**
 * App preferences, wired to the ViewModel — and the way into the account list (Task 69).
 *
 * Settings stopped being a tab and became the screen behind the gear at the top right of
 * Chats. The accounts list moved with it, because both are "set the app up" rather than
 * "make a call", and neither earns a permanent tab on a phone whose primary job is the
 * latter.
 *
 * ## The chat account card left and came back
 *
 * It was removed when the login became the Chats tab's own (decision D4), on the grounds
 * that this screen is reached from the **Calls** tab too. That argument settles where the
 * login *gate* lives; it does not settle where its exit should be findable. "Settings" is
 * the first place a person looks to sign out of anything. So the card is back, and Sign out
 * now exists in two places that call the same repository — here, and the Chats header's
 * overflow menu.
 *
 * Signing *in* is still the Chats tab's alone: the card is absent with no session, so this
 * screen never asks for credentials. Every SIP control this screen had, it still has.
 */
@Composable
fun SettingsScreen(
    onOpenAccounts: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Null on a build that has no background-access switch to show. */
    backgroundAccess: BackgroundAccessLink? = null,
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
            onVerifyTlsChange = viewModel::setVerifyTlsCertificates,
            onRetentionChange = viewModel::setCallHistoryRetention,
            onVideoFrameRateChange = viewModel::setVideoFrameRate,
            onChatSignOut = { viewModel.signOutOfChat() },
        )
    }

    SettingsScreen(
        voiceProfile = { VoiceProfileSection() },
        state = state,
        actions = actions,
        links = SettingsLinks(onOpenAccounts = onOpenAccounts, backgroundAccess = backgroundAccess),
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
    val onVerifyTlsChange: (Boolean) -> Unit,
    val onRetentionChange: (CallHistoryRetention) -> Unit,
    val onVideoFrameRateChange: (VideoFrameRate) -> Unit,
    /** Confirmed first by the screen — see [ChatAccountRow]. */
    val onChatSignOut: () -> Unit = {},
) {
    companion object {
        /** For previews and tests that are not about what a change does. */
        val NONE = SettingsActions({}, {}, {}, {}, {}, {}, {}, {})
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
    /** Absent on a build with nothing to show for it, and the row is absent with it. */
    val backgroundAccess: BackgroundAccessLink? = null,
)

/**
 * The platform's battery-optimisation switch for this app, as the settings screen sees it.
 *
 * Read by the app module (it is a `PowerManager` question) and opened by it (it is a
 * system screen); this row only shows the answer and forwards the tap. Shown as a row
 * rather than a switch because the app cannot set it — only the system dialog can — and
 * a switch that opens another screen instead of switching is a lie in miniature.
 */
data class BackgroundAccessLink(
    /** True when the app is exempt from battery optimisation. */
    val allowed: Boolean,
    val onOpen: () -> Unit,
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
    /**
     * The voice-profile card. Empty by default, which is what keeps this overload
     * stateless: the card owns a ViewModel, and a `hiltViewModel()` reached from here
     * would make every preview and every Compose test of this screen need a Hilt graph.
     * The stateful overload above supplies the real one.
     */
    voiceProfile: @Composable ColumnScope.() -> Unit = {},
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
            voiceProfile = voiceProfile,
        )
    }
}

/**
 * The scrolling body, split out so the screen above it stays a layout.
 *
 * [voiceProfile] is a slot and not a call, because the voice card owns a ViewModel of its
 * own (enrolment holds the microphone for two minutes, which is a lifecycle this screen's
 * state has no business carrying). A `hiltViewModel()` inside this tree would also make
 * every Compose test of this screen need a Hilt graph it does not otherwise want — which
 * is exactly what it did, and `SettingsScreenTest` said so.
 */
@Composable
private fun SettingsContent(
    state: SettingsUiState,
    actions: SettingsActions,
    links: SettingsLinks?,
    modifier: Modifier = Modifier,
    voiceProfile: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AppTheme.spacing.large),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.large),
    ) {
        IdentityCards(chatAccount = state.chatAccount, links = links, actions = actions)

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

        CallCards(state = state, actions = actions)

        // Beside the call settings, because that is what it changes. Its own ViewModel:
        // enrolment holds the microphone for up to two minutes and that is a lifecycle
        // `SettingsViewModel` has no business owning - see VoiceProfileViewModel.
        SettingsCard { voiceProfile() }

        SettingsCard {
            TlsVerificationToggle(
                enabled = state.settings.verifyTlsCertificates,
                onChange = actions.onVerifyTlsChange,
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
 * The three cards that govern what a call does: digits, encryption, and where it comes out.
 *
 * Grouped because they are one subject, and because the body above them is a list of cards
 * whose length is the only thing that makes it hard to read.
 */
@Composable
private fun CallCards(state: SettingsUiState, actions: SettingsActions) {
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
        VideoFrameRateGroup(
            selected = state.settings.videoFrameRate,
            onSelect = actions.onVideoFrameRateChange,
        )
    }
}

/**
 * Who this app is signed in as, above the preferences.
 *
 * The SIP accounts row and the chat account row answer the same question about two
 * different services, so they sit together. The chat one is absent when nobody is signed
 * in: its only action is Sign out, and a Sign out with no account cannot do anything.
 */
@Composable
private fun IdentityCards(
    chatAccount: ChatAccountUiState?,
    links: SettingsLinks?,
    actions: SettingsActions,
) {
    LinkCards(links)
    chatAccount?.let { account ->
        SettingsCard { ChatAccountRow(account = account, onSignOut = actions.onChatSignOut) }
    }
}

/**
 * The signed-in chat identity, and the one thing that can be done about it.
 *
 * ## It confirms, and the confirmation is here rather than in the ViewModel
 *
 * Signing out clears the session and the token, and the only way back is to type a
 * password again — so it is guarded. Whether to confirm is a UI decision, which is why
 * `ConfirmDialog` is raised here and `signOutOfChat()` is called only on the way out of it.
 *
 * The Chats header's overflow menu raises its own dialog over the same repository call.
 * Two dialogs rather than one shared composable because the wording differs — that one can
 * say the tab you are standing in is about to empty — and because a shared confirm would
 * couple `:feature:settings` to `:feature:chat` for the sake of four lines.
 *
 * ## The server address is shown and cannot be edited
 *
 * Decision D1: the URL is an identity, not a preference. It is captured on the sign-in
 * screen beside the credentials it belongs to and saved only when they work. Editable
 * here, it could be changed to a host the stored session never came from, and the result
 * would look to a user like an account that had broken by itself.
 */
@Composable
private fun ChatAccountRow(account: ChatAccountUiState, onSignOut: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppTheme.spacing.small)
            .testTag(TAG_CHAT_ACCOUNT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.medium),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Chat,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text("Chat account", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${account.identity} · ${account.serverOrigin}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { confirming = true }, modifier = Modifier.testTag(TAG_CHAT_SIGN_OUT)) {
            Text("Sign out")
        }
    }

    if (confirming) {
        ConfirmDialog(
            title = "Sign out of chat?",
            message = "You will need your password to sign in again. Calls are not affected.",
            confirmLabel = "Sign out",
            destructive = true,
            onConfirm = {
                confirming = false
                onSignOut()
            },
            onDismiss = { confirming = false },
        )
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
    links.backgroundAccess?.let { SettingsCard { BackgroundAccessRow(it) } }
}

/**
 * Whether the phone will leave the app running to receive calls, and the way to change it.
 *
 * Restricted is the state that loses calls: the registration service is ended when the
 * app is off screen and nothing re-registers when the network returns. The text says so
 * plainly rather than naming the setting, because "battery optimisation" does not sound
 * like something that stops a phone ringing.
 *
 * ## The chevron is only there while there is something to do
 *
 * A `>` is a promise that tapping leads somewhere worth going. While the app is
 * *Restricted* that is exactly true — the row's own last words are "Tap to allow", and the
 * chevron is what makes them look like a control rather than a complaint. Once the phone
 * says *Allowed* there is nothing left to ask for: the row has become a statement of fact,
 * and a chevron beside a settled state reads as an unfinished errand, which is the one
 * thing this row must not imply on a phone that is already set up correctly.
 *
 * The row stays tappable either way. Turning background access back *off* is a thing
 * somebody may legitimately want, and the system screen is the only place it can be done —
 * removing the tap would make the app the only route to a setting it then refused to
 * offer. What goes away is the invitation, not the door.
 */
@Composable
private fun BackgroundAccessRow(link: BackgroundAccessLink) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = link.onOpen)
            .padding(vertical = AppTheme.spacing.small)
            .testTag(TAG_BACKGROUND_ACCESS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.medium),
    ) {
        Icon(
            imageVector = if (link.allowed) Icons.Filled.BatteryFull else Icons.Filled.BatteryAlert,
            contentDescription = null,
            tint = if (link.allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text("Run in the background", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (link.allowed) {
                    "Allowed. The phone will keep this app registered and able to receive calls " +
                        "while it is not on screen."
                } else {
                    "Restricted. The phone may stop this app when it is not on screen, and " +
                        "calls to your extension would be missed. Tap to allow."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!link.allowed) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TAG_BACKGROUND_ACCESS_CHEVRON),
            )
        }
    }
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
 * The frame rate outgoing video is encoded at, chosen from a list.
 *
 * ## Why the uneven rates are offered rather than hidden
 *
 * The camera delivers 30 fps whatever is asked of it and the encoder keeps a subset, so a
 * rate that does not divide 30 can only be reached on an uneven pattern — 20 fps is 33 ms,
 * 67 ms, 33 ms, 67 ms, and that is visible as judder even when no frame is lost. Hiding
 * them would be deciding for the user; 25 fps on a link that carries it still looks better
 * than 15 to most people. So they are listed with the trade named in the field, which is
 * what [VideoFrameRate.dividesCameraRate] is for.
 *
 * A dropdown rather than a chip row because six options do not fit on a row — the same
 * reason [RetentionGroup] uses one.
 */
@Composable
private fun VideoFrameRateGroup(selected: VideoFrameRate, onSelect: (VideoFrameRate) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.small)) {
        Text("Video quality", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "The frame rate outgoing video aims for. A poor connection may still " +
                "reduce it. Takes effect on the next call, or the next time video is " +
                "switched on during one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AppDropdownField(
            label = "Frame rate",
            options = VideoFrameRate.entries,
            selected = selected,
            labelOf = ::frameRateLabel,
            onSelect = onSelect,
            optionTag = ::frameRateOptionTag,
            modifier = Modifier.testTag(TAG_FRAME_RATE),
        )
        if (!selected.dividesCameraRate) {
            // Stated where the consequence is, in the same voice the encryption warning
            // uses: this is not an error, it is the cost of the choice just made.
            Text(
                text = "${selected.fps} fps does not divide the camera's 30 fps evenly, so " +
                    "frames arrive unevenly spaced. It may look less smooth than 15 or 30.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The words for one frame rate.
 *
 * The default is named as such because a user who has changed it and wants to undo that
 * should not have to remember which one it was.
 */
internal fun frameRateLabel(rate: VideoFrameRate): String =
    if (rate == VideoFrameRate.DEFAULT) "${rate.fps} fps (default)" else "${rate.fps} fps"

internal const val TAG_FRAME_RATE = "settings-frame-rate"

internal fun frameRateOptionTag(rate: VideoFrameRate) = "settings-frame-rate-${rate.fps}"

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

internal const val TAG_CHAT_ACCOUNT = "settings-chat-account"
internal const val TAG_CHAT_SIGN_OUT = "settings-chat-sign-out"
internal const val TAG_ACCOUNTS = "settings-accounts"
internal const val TAG_BACKGROUND_ACCESS = "settings-background-access"

/** The row's trailing chevron, which exists only while there is something to go and do. */
internal const val TAG_BACKGROUND_ACCESS_CHEVRON = "settings-background-access-chevron"
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

/**
 * Whether a TLS registrar's certificate is checked.
 *
 * The supporting text names the trade rather than the feature. "Verify certificates" on
 * its own reads as a tidiness option somebody can leave alone; what is actually being
 * chosen is whether an encrypted connection is also an authenticated one, and the only
 * honest way to offer that is to say what is lost when it is off.
 */
@Composable
private fun TlsVerificationToggle(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.small)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Verify TLS certificates", style = MaterialTheme.typography.titleMedium)
            Switch(checked = enabled, onCheckedChange = onChange)
        }
        Text(
            text = if (enabled) {
                "On. A TLS registrar must present a certificate this phone trusts, issued " +
                    "for the address being dialled. A server whose certificate is " +
                    "self-signed, expired, or issued for another name will not register."
            } else {
                "Off. TLS still encrypts, but the server is not checked, so anyone who can " +
                    "reach the connection can present any certificate and read or change " +
                    "the signalling. Turn this on unless your server's certificate cannot " +
                    "pass the check."
            },
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
