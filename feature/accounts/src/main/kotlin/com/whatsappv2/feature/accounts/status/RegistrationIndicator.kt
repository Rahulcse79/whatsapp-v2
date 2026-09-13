package com.whatsappv2.feature.accounts.status

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whatsappv2.core.designsystem.component.AppTopBar
import com.whatsappv2.core.designsystem.component.StatusDot
import com.whatsappv2.core.designsystem.component.StatusLabel
import com.whatsappv2.core.designsystem.component.StatusTone
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.feature.accounts.list.AccountStatus
import com.whatsappv2.feature.accounts.list.label

/**
 * The registration indicator, wired to its ViewModel (item 5.5).
 *
 * Lives in `:feature:accounts` and is *placed* by `:app` in the Chats top bar: the bar
 * belongs to the shell, the knowledge of what an account's state means belongs here, and
 * neither should have to import the other's screens to meet.
 */
@Composable
fun RegistrationIndicatorRoute(
    /** Opens the account list — the way to add the first account, or to fix a failing one. */
    onManageAccounts: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RegistrationStatusViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RegistrationIndicator(
        state = state,
        onSetDefault = viewModel::setDefault,
        onManageAccounts = onManageAccounts,
        modifier = modifier,
    )
}

/**
 * The default account's extension and its registration state, as a coloured dot with the
 * state in words — and, behind a tap, every account with the same, plus the choice of
 * which one is the default.
 *
 * ## Why the default, and why it can be changed here
 *
 * The default account is the one an outgoing call leaves on, so it is the one whose
 * registration answers "can I call right now". Someone with a work and a home extension
 * switches between them more often than they edit either, and the account list is three
 * taps away behind Settings; the bar is where they are looking already.
 *
 * ## Colour is never the only channel
 *
 * The dot is green, orange or red, and beside it the state is written down —
 * "Registered", "Reconnecting…", "Check your details", "Offline" — in the same words the
 * account list uses, from the same function, so the bar and the list cannot describe one
 * state two ways. Two states share red and the words tell them apart, which is why the
 * words are not optional. The dot itself is decorative in the accessibility tree; the chip reads
 * as one sentence.
 */
@Composable
fun RegistrationIndicator(
    state: RegistrationStatusUiState,
    onSetDefault: (AccountId) -> Unit,
    onManageAccounts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        // Nothing yet, and nothing to promise. A spinner in a top bar reads as the app
        // being slow; the store answers in milliseconds and the chip simply appears.
        RegistrationStatusUiState.Loading -> Unit

        RegistrationStatusUiState.NoAccounts -> IndicatorChip(
            tone = StatusTone.OFFLINE,
            headline = "No account",
            detail = "Tap to set up",
            description = "No SIP account. Tap to set one up",
            onClick = onManageAccounts,
            modifier = modifier.testTag(TAG_INDICATOR),
        )

        is RegistrationStatusUiState.Content -> AccountsIndicator(
            state = state,
            onSetDefault = onSetDefault,
            onManageAccounts = onManageAccounts,
            modifier = modifier,
        )
    }
}

@Composable
private fun AccountsIndicator(
    state: RegistrationStatusUiState.Content,
    onSetDefault: (AccountId) -> Unit,
    onManageAccounts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Saveable so a rotation with the menu open does not quietly close it.
    var open by rememberSaveable { mutableStateOf(false) }
    val default = state.default

    Box(modifier = modifier) {
        IndicatorChip(
            tone = default.status.tone(),
            headline = default.extension,
            detail = default.status.label(),
            description = "Account ${default.extension}, ${default.status.label()}. " +
                "Default account; tap to change",
            onClick = { open = true },
            modifier = Modifier.testTag(TAG_INDICATOR),
        )

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.accounts.forEach { account ->
                DropdownMenuItem(
                    text = { AccountMenuLine(account) },
                    onClick = {
                        open = false
                        if (!account.isDefault) onSetDefault(account.id)
                    },
                    trailingIcon = {
                        if (account.isDefault) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Default account",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    modifier = Modifier
                        .testTag(accountMenuTag(account.id))
                        .semantics {
                            contentDescription = buildString {
                                append("${account.label}, ${account.extension}, ${account.status.label()}")
                                if (account.isDefault) append(", default account") else append(". Make default")
                            }
                        },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Manage accounts") },
                onClick = {
                    open = false
                    onManageAccounts()
                },
                leadingIcon = { Icon(Icons.Filled.ManageAccounts, contentDescription = null) },
                modifier = Modifier.testTag(TAG_MANAGE_ACCOUNTS),
            )
        }
    }
}

/**
 * One account in the menu: its label and extension, and its state as a dot with words.
 *
 * An account labelled with its own extension — "1004", say — is named once, not
 * "1004 · 1004".
 */
@Composable
private fun AccountMenuLine(account: AccountIndicatorRow) {
    Column {
        Text(
            text = if (account.label == account.extension) {
                account.label
            } else {
                "${account.label} · ${account.extension}"
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        StatusLabel(tone = account.status.tone(), text = account.status.label())
    }
}

/**
 * The chip itself: dot, headline, detail, one tap.
 *
 * At least the minimum touch target tall even though its text is two small lines, because
 * this is the one thing in the bar that is pressed on purpose.
 */
@Composable
private fun IndicatorChip(
    tone: StatusTone,
    headline: String,
    detail: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Drawn on the header, so every colour is the header's (`BarColors`): the content
    // colour the bar provides, its secondary tone, and a dot palette chosen against the
    // bar rather than against a white page — a green dot on a green bar is no dot.
    val bar = AppTheme.barColors
    // The states that cannot take a call bring their own ground; see BarColors.statusAlert
    // for why a darker red needs one. Registered stays a bare chip, which is what keeps
    // the ordinary state quiet and this one loud.
    val alerting = tone == StatusTone.FAILED
    val shape = MaterialTheme.shapes.small

    Row(
        modifier = modifier
            .heightIn(min = AppTheme.sizing.minimumTouchTarget)
            .clip(shape)
            .then(if (alerting) Modifier.background(bar.statusAlert.container, shape) else Modifier)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
            }
            .padding(horizontal = AppTheme.spacing.medium, vertical = AppTheme.spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.small),
    ) {
        if (alerting) {
            StatusDot(tone, colors = bar.status.copy(failed = bar.statusAlert.dot))
        } else {
            StatusDot(tone, colors = bar.status)
        }
        Column {
            Text(
                text = headline,
                style = MaterialTheme.typography.labelLarge,
                color = if (alerting) bar.statusAlert.onContainer else LocalContentColor.current,
                maxLines = 1,
            )
            Text(
                // "Offline", and in the same red as the extension above it — the sentence
                // and the dot say one thing rather than the dot saying it alone.
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = if (alerting) bar.statusAlert.onContainer else bar.onTopVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * Which colour a status is (item 5.5): green registered, orange trying, red not reachable.
 *
 * "Reconnecting…" is orange rather than red on purpose: the app is doing something about
 * it and nobody has to. Red is for the states where somebody does.
 *
 * ## Offline is red, not grey
 *
 * It was grey, on the reasoning that being unregistered is a state rather than a fault.
 * That reads the indicator as a description of the *account* — but it sits in the Chats
 * bar to answer one question, "can I be called right now", and the answer while offline is
 * no. Grey says "nothing to see"; the truth is that every call to that extension is being
 * missed, and the user is the only one who can do anything about it. The words beside the
 * dot still say "Offline" rather than "Check your details", so the two red states stay
 * distinguishable to anyone who reads them — which is the point of never letting colour be
 * the only channel.
 *
 * It costs a red dot for the second or so between process start and the first REGISTER, a
 * window in which the app genuinely cannot take a call. That is honest, and it is shorter
 * than the time it takes to look at it.
 *
 * `StatusTone.OFFLINE` keeps its grey and its use: the "No account" chip, where there is
 * no extension to be unreachable and the next step is setup rather than repair.
 */
internal fun AccountStatus.tone(): StatusTone = when (this) {
    AccountStatus.REGISTERED -> StatusTone.ONLINE
    AccountStatus.REGISTERING, AccountStatus.FAILED_RETRYING -> StatusTone.CONNECTING
    AccountStatus.FAILED_NEEDS_ATTENTION, AccountStatus.OFFLINE -> StatusTone.FAILED
}

/** The chip, so a test presses it rather than a word that may appear twice. */
const val TAG_INDICATOR = "registration-indicator"
internal const val TAG_MANAGE_ACCOUNTS = "registration-manage-accounts"

/** One account's menu line, so a test picks the account it means. */
internal fun accountMenuTag(id: AccountId) = "registration-account-${id.value}"

@ThemePreviews
@Composable
private fun RegistrationIndicatorPreview() = PreviewSurface {
    val local = AccountIndicatorRow(AccountId("1"), "Local", "1001", AccountStatus.REGISTERED, isDefault = true)
    val office = AccountIndicatorRow(AccountId("2"), "Office", "7001", AccountStatus.FAILED_RETRYING, isDefault = false)
    val failing = office.copy(status = AccountStatus.FAILED_NEEDS_ATTENTION, isDefault = true)
    val offline = local.copy(status = AccountStatus.OFFLINE)

    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.small)) {
        // On the header it is designed for, so the colours are judged where they are used.
        AppTopBar(
            title = "Chats",
            actions = {
                RegistrationIndicator(
                    state = RegistrationStatusUiState.Content(accounts = listOf(local, office), default = local),
                    onSetDefault = {},
                    onManageAccounts = {},
                )
            },
        )
        AppTopBar(
            title = "Chats",
            actions = {
                RegistrationIndicator(
                    state = RegistrationStatusUiState.Content(accounts = listOf(failing), default = failing),
                    onSetDefault = {},
                    onManageAccounts = {},
                )
            },
        )
        AppTopBar(
            title = "Chats",
            actions = {
                RegistrationIndicator(
                    state = RegistrationStatusUiState.Content(accounts = listOf(offline), default = offline),
                    onSetDefault = {},
                    onManageAccounts = {},
                )
            },
        )
        AppTopBar(
            title = "Chats",
            actions = {
                RegistrationIndicator(
                    state = RegistrationStatusUiState.NoAccounts,
                    onSetDefault = {},
                    onManageAccounts = {},
                )
            },
        )
    }
}
