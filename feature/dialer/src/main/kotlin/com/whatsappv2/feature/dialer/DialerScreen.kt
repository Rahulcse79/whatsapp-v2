package com.whatsappv2.feature.dialer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whatsappv2.core.designsystem.component.AppTopBar
import com.whatsappv2.core.designsystem.component.Avatar
import com.whatsappv2.core.designsystem.component.CallActionButton
import com.whatsappv2.core.designsystem.component.CallActionStyle
import com.whatsappv2.core.designsystem.component.StatusLabel
import com.whatsappv2.core.designsystem.component.StatusTone
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme
import com.whatsappv2.domain.contacts.SipContact
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId

/**
 * The dialer, wired to its ViewModel (Task 36).
 *
 * [onCallPlaced] is how the call screen is opened. The dialer does not know what that
 * screen is — it lives in another module and is hosted by an activity `:app` owns — which
 * is exactly the layering that keeps a feature independently testable.
 */
@Composable
fun DialerScreen(
    onCallPlaced: (CallId) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DialerViewModel = hiltViewModel(),
    /**
     * Runs a video call only after the camera has been asked for (Task 74).
     *
     * `:app` supplies the real one; the default proceeds straight through so a preview and
     * a test need no permission machinery. It gates the *prompt*, never the call — a
     * declined camera still places an audio call, which is `MediaProfile`'s rule.
     */
    videoGate: (proceed: () -> Unit) -> Unit = { it() },
    /**
     * Runs an audio call once the microphone has been asked for, and not at all if it is
     * refused: the stack cannot open a capture device without it, so the call would hang
     * at *Calling* rather than fail in a way anybody could act on.
     */
    callGate: (proceed: () -> Unit) -> Unit = { it() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is DialerEvent.CallPlaced -> onCallPlaced(event.callId)
                is DialerEvent.NoAccount ->
                    snackbarHostState.showSnackbar("Add an account before calling")
                is DialerEvent.InvalidTarget ->
                    snackbarHostState.showSnackbar("\"${event.input}\" is not a number or address")
                is DialerEvent.Refused -> snackbarHostState.showSnackbar(event.message)
                // The call was placed; this only says which kind it turned out to be.
                is DialerEvent.Notice -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    DialerScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        actions = DialerActions(
            onInputChanged = viewModel::onInputChanged,
            onDigit = viewModel::onDigitPressed,
            onBackspace = viewModel::onBackspace,
            onClear = viewModel::onClear,
            onAccountSelected = viewModel::onAccountSelected,
            onRecentSelected = viewModel::onRecentSelected,
            // Every path that starts a call goes through a gate, because every one of them
            // needs a microphone the user may not have granted yet.
            onContactSelected = { contact -> callGate { viewModel.onContactSelected(contact) } },
            onCall = { callGate { viewModel.onCall() } },
            // Both permissions, in the order they matter: without the microphone there is
            // no call to make, and without the camera there is still an audio one.
            onVideoCall = { callGate { videoGate { viewModel.onVideoCall() } } },
            onBack = onBack,
        ),
        modifier = modifier,
    )
}

/**
 * The stateless dialler.
 *
 * Separated from the ViewModel-bound version so it can be previewed and driven by a UI
 * test with nothing behind it — which is what Task 36's third done-when asks for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DialerScreen(
    state: DialerUiState,
    snackbarHostState: SnackbarHostState,
    actions: DialerActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        // A back arrow, because the dialler is a screen opened from Calls now rather than
        // a tab that is always there (Task 70).
        topBar = { DialerTopBar(onBack = actions.onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(AppTheme.spacing.large),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.hasChoiceOfAccounts) {
                AccountPicker(state = state, onAccountSelected = actions.onAccountSelected)
            }

            // Always, even with one account — see [AccountStatus].
            AccountStatus(account = state.selectedAccount)

            DialledNumber(state = state, actions = actions)

            Suggestions(state = state, actions = actions)

            Spacer(Modifier.weight(1f))

            Keypad(onDigit = actions.onDigit, onClear = actions.onClear)

            DialerCallButtons(state = state, actions = actions)
        }
    }
}

/**
 * The number being dialled.
 *
 * Its own composable so [DialerScreen] stays a layout, and because the styling here is the
 * point: a dialler shows the number, it does not ask for it in a form. The box and its
 * floating label are gone; what is left is the digits, large and centred, with the hint
 * standing in as the heading while the field is empty — which is also the only thing on
 * this screen that names what it takes.
 */
@Composable
private fun DialledNumber(state: DialerUiState, actions: DialerActions) {
    // The caret is kept at the end of the text, deliberately, and this is why the field
    // takes a `TextFieldValue` rather than a `String`.
    //
    // The `String` overload owns the selection itself and does not move it when the value
    // changes from somewhere other than the keyboard. Every digit here arrives from the
    // keypad, so the text grew while the selection stayed at index 0 — the caret blinked
    // against the left edge and each new digit appeared to its right, which read as typing
    // backwards into the middle of the number. Holding the selection at `input.length`
    // puts the caret after the last digit, where a dialler's caret belongs.
    val field = TextFieldValue(text = state.input, selection = TextRange(state.input.length))

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = AppTheme.spacing.medium),
    ) {
        // A spacer the same width as the backspace button, so the number is centred on the
        // screen rather than on the space the button leaves. As a `trailingIcon` the
        // button was inside the text field, and "centred" then meant centred in what was
        // left over — the number sat visibly left of centre whenever the button was there
        // and jumped right when it went away.
        Spacer(Modifier.size(AppTheme.sizing.callActionButton))

        TextField(
            value = field,
            onValueChange = { actions.onInputChanged(it.text) },
            singleLine = true,
            placeholder = {
                Text(
                    text = "Number or SIP address",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            textStyle = MaterialTheme.typography.displaySmall.copy(textAlign = TextAlign.Center),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.weight(1f).testTag(TAG_INPUT),
        )

        // Outside the field and always occupying its slot, so nothing reflows when the
        // first digit is typed. Invisible rather than absent while there is nothing to
        // delete — `alpha` keeps the layout, and it is not clickable when there is nothing
        // to remove, so a screen reader is not offered a control that does nothing.
        IconButton(
            onClick = actions.onBackspace,
            enabled = state.input.isNotEmpty(),
            modifier = Modifier
                .size(AppTheme.sizing.callActionButton)
                .alpha(if (state.input.isEmpty()) 0f else 1f)
                .testTag(TAG_BACKSPACE),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Backspace,
                contentDescription = "Delete last character",
            )
        }
    }
}

/**
 * Which extension this call goes out on, and whether it can go out at all.
 *
 * ## Why it is always on screen
 *
 * It was not shown at all with one account: [AccountPicker] is hidden below two, on the
 * reasoning that a picker with one entry is a control that cannot be used. That is right
 * about the *picker* and wrong about the *information* — the one thing a dialler has to
 * answer before the call button is pressed is "am I reachable, and as whom", and a single
 * account is still an account that can be offline. So the identity and its state are here
 * unconditionally, and choosing between accounts stays the picker's job.
 *
 * The dot and the word carry the same fact twice on purpose. Colour alone fails for the
 * ~8% of men with a red/green deficiency, and this is exactly the pairing where that
 * matters: the two states are red and green and nothing else distinguishes them.
 */
@Composable
private fun AccountStatus(account: DialerAccount?) {
    account ?: return

    val tone = if (account.isRegistered) StatusTone.ONLINE else StatusTone.FAILED
    val colour = if (account.isRegistered) {
        AppTheme.statusColors.online
    } else {
        AppTheme.statusColors.failed
    }

    // `StatusLabel` rather than a dot and a Text of this file's own: it is the design
    // system's pairing and it is the thing that makes "never colour alone" hard to break.
    // The extension is a second Text beside it because the identity is not the *state* —
    // it takes the state's colour so the eye reads them as one thing, while the word after
    // it is what a screen reader and a colour-blind user actually rely on.
    Row(
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = AppTheme.spacing.small)
            .testTag(TAG_ACCOUNT_STATUS),
    ) {
        Text(
            text = account.identity,
            style = MaterialTheme.typography.labelLarge,
            color = colour,
        )
        StatusLabel(
            tone = tone,
            text = if (account.isRegistered) "Registered" else "Unregistered",
        )
    }
}

/** A back arrow, because the dialler is a screen opened from Calls now, not a tab (Task 70). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialerTopBar(onBack: () -> Unit) {
    AppTopBar(
        title = "Dialler",
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.testTag(TAG_BACK)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to calls",
                )
            }
        },
    )
}

/**
 * Two ways to place the same call (Task 74).
 *
 * Both are enabled by the same rule, because whether video is possible is not this
 * screen's decision: a device with no usable camera places an audio call and is told,
 * rather than being shown a dead button it cannot explain.
 */
@Composable
private fun DialerCallButtons(state: DialerUiState, actions: DialerActions) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.extraLarge),
        modifier = Modifier.padding(
            top = AppTheme.spacing.large,
            bottom = AppTheme.spacing.large,
        ),
    ) {
        CallActionButton(
            icon = Icons.Filled.Call,
            contentDescription = "Place call",
            onClick = actions.onCall,
            style = CallActionStyle.ANSWER,
            enabled = state.canPlaceCall,
            label = "Call",
            modifier = Modifier.testTag(TAG_CALL),
        )
        CallActionButton(
            icon = Icons.Filled.Videocam,
            contentDescription = "Place video call",
            onClick = actions.onVideoCall,
            style = CallActionStyle.ANSWER,
            enabled = state.canPlaceCall,
            label = "Video",
            modifier = Modifier.testTag(TAG_VIDEO_CALL),
        )
    }
}

/**
 * The per-call account override.
 *
 * Shown only with more than one account, because a picker with one entry is a control that
 * cannot be used. The selected account's identity is shown beneath it: a bare extension is
 * completed against that domain, so which account is chosen decides where `1001` goes.
 */
@Composable
private fun AccountPicker(
    state: DialerUiState,
    onAccountSelected: (AccountId) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.selectedAccount

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag(TAG_ACCOUNT),
        ) {
            Text(selected?.label ?: "Choose an account")
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = "Choose the account to call from",
            )
        }
        selected?.let {
            Text(
                text = it.identity,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.accounts.forEach { account ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(account.label)
                            Text(
                                // The status is here because it changes what will happen:
                                // an unregistered account cannot place a call, and the
                                // refusal is easier to understand before it arrives.
                                text = if (account.isRegistered) account.identity else "${account.identity} · offline",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        onAccountSelected(account.id)
                        expanded = false
                    },
                    modifier = Modifier.testTag(accountTag(account.id)),
                )
            }
        }
    }
}

/**
 * The two rows of shortcuts above the keypad: what was dialled, and who can be dialled.
 *
 * Together in one composable because they are one idea — ways to avoid typing an address —
 * and because each is hidden when it is empty, so a screen with neither shows the keypad
 * and nothing else, exactly as it did before either existed.
 */
@Composable
private fun Suggestions(state: DialerUiState, actions: DialerActions) {
    if (state.recent.isNotEmpty()) {
        Recents(recent = state.recent, onRecentSelected = actions.onRecentSelected)
    }
    if (state.contacts.isNotEmpty()) {
        Contacts(contacts = state.contacts, onContactSelected = actions.onContactSelected)
    }
}

/**
 * Contacts with a SIP address matching what has been typed (Task 50).
 *
 * A row of chips like the recents above it, and hidden entirely when there is nothing to
 * show — which is the same thing the screen does when READ_CONTACTS was declined, so a
 * user who said no is never shown an empty space where a list should be.
 */
@Composable
private fun Contacts(contacts: List<SipContact>, onContactSelected: (SipContact) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.small),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AppTheme.spacing.small)
            .testTag(TAG_CONTACTS),
    ) {
        items(contacts) { match ->
            AssistChip(
                onClick = { onContactSelected(match) },
                label = { Text(match.contact.displayName) },
                leadingIcon = {
                    Avatar(
                        displayName = match.contact.displayName,
                        photoUri = match.contact.photoUri,
                        size = AppTheme.sizing.avatarSmall,
                    )
                },
                modifier = Modifier.testTag(contactTag(match)),
            )
        }
    }
}

@Composable
private fun Recents(recent: List<String>, onRecentSelected: (String) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.small),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AppTheme.spacing.small)
            .testTag(TAG_RECENTS),
    ) {
        items(recent) { target ->
            AssistChip(
                onClick = { onRecentSelected(target) },
                label = { Text(target) },
                modifier = Modifier.testTag(recentTag(target)),
            )
        }
    }
}

/**
 * The keypad.
 *
 * A grid of characters rather than twelve composables: the layout is the same for every
 * key, and writing it twelve times is twelve chances for one of them to drift.
 */
@Composable
private fun Keypad(onDigit: (Char) -> Unit, onClear: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.small),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        KEYPAD_ROWS.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.large),
            ) {
                row.forEach { key -> KeypadKey(key = key, onDigit = onDigit) }
            }
        }
        TextButton(onClick = onClear, modifier = Modifier.testTag(TAG_CLEAR)) {
            Text("Clear")
        }
    }
}

/**
 * One key: a filled circle, the digit, and the letters under it.
 *
 * ## Why a circle and not a `TextButton`
 *
 * The keys were bare text in a square hit area, which left the commonest control on the
 * screen with nothing to aim at — the ripple was a rectangle around a glyph and there was
 * no resting shape at all, so the keypad read as a list of numbers rather than as buttons.
 * A tinted circle gives the thumb a target it can see without looking, which on a keypad
 * is the whole job.
 *
 * ## The letters
 *
 * `2 ABC` and the rest, because a SIP address is often given as a word and because every
 * phone keypad since the rotary dial has carried them — their absence is the kind of thing
 * that reads as unfinished without anybody being able to say why. `1`, `*`, `0` and `#`
 * have none, and get a blank line of the same height so the twelve keys stay on one grid
 * instead of four rows of two different heights.
 */
@Composable
private fun KeypadKey(key: Char, onDigit: (Char) -> Unit) {
    Box(
        modifier = Modifier
            .size(AppTheme.sizing.callActionButton)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable { onDigit(key) }
            .testTag(keyTag(key)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = key.toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                // A space, not an empty string: an empty Text collapses to nothing and the
                // keys without letters would then sit a few pixels higher than the rest.
                text = KEYPAD_LETTERS[key] ?: " ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** What each key carries under its digit. The four without letters are absent, not blank. */
private val KEYPAD_LETTERS = mapOf(
    '2' to "ABC",
    '3' to "DEF",
    '4' to "GHI",
    '5' to "JKL",
    '6' to "MNO",
    '7' to "PQRS",
    '8' to "TUV",
    '9' to "WXYZ",
)

private val KEYPAD_ROWS = listOf(
    listOf('1', '2', '3'),
    listOf('4', '5', '6'),
    listOf('7', '8', '9'),
    listOf('*', '0', '#'),
)

internal const val TAG_INPUT = "dialer-input"
internal const val TAG_CALL = "dialer-call"
internal const val TAG_VIDEO_CALL = "dialer-video-call"
internal const val TAG_BACK = "dialer-back"
internal const val TAG_BACKSPACE = "dialer-backspace"
internal const val TAG_CLEAR = "dialer-clear"
internal const val TAG_ACCOUNT = "dialer-account"
internal const val TAG_ACCOUNT_STATUS = "dialer-account-status"
internal const val TAG_RECENTS = "dialer-recents"
internal const val TAG_CONTACTS = "dialer-contacts"

internal fun keyTag(key: Char): String = "dialer-key-$key"
internal fun recentTag(target: String): String = "dialer-recent-$target"

internal fun contactTag(match: SipContact): String = "dialer-contact-${match.address.render()}"
internal fun accountTag(id: AccountId): String = "dialer-account-${id.value}"

@ThemePreviews
@Composable
private fun DialerPreview() = PreviewSurface {
    DialerScreen(
        state = DialerUiState(
            input = "1001",
            accounts = listOf(PREVIEW_WORK, PREVIEW_HOME),
            selectedAccount = PREVIEW_WORK,
            recent = listOf("1002", "sip:carol@example.com"),
        ),
        snackbarHostState = remember { SnackbarHostState() },
        actions = DialerActions(),
    )
}

private val PREVIEW_WORK = DialerAccount(
    id = AccountId("work"),
    label = "Work",
    identity = "alice@sip.example.com",
    isRegistered = true,
)

private val PREVIEW_HOME = DialerAccount(
    id = AccountId("home"),
    label = "Home",
    identity = "alice@home.example.com",
    isRegistered = false,
)
