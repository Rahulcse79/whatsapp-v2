package com.whatsappv2.feature.chat.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.core.designsystem.component.AppTopBar
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme
import com.whatsappv2.domain.chat.ChatAuthError
import com.whatsappv2.domain.chat.ChatUrlViolation

/**
 * Sign in: server, username, password.
 *
 * ## It is the application's login, not the Chats tab's
 *
 * It began as the latter and is mounted above everything now — `SignInGate` in `:app` draws
 * it instead of the app until there is a session. That is why [onBack] is nullable: there is
 * nowhere behind a gate, and a back arrow that pops to nothing is a control that lies.
 *
 * ## Why the URL is asked here and not in Settings
 *
 * It is an identity, not a preference. In Settings it would survive a sign-out and could be
 * edited into a state where the saved session no longer matches the server it came from —
 * a failure that looks like a broken account. Asked here, it is captured with the
 * credentials it belongs to and saved only when they work.
 *
 * ## `http://` gets its own message, and an offer to fix it
 *
 * This app refuses cleartext in **every** build, with no debug override. A user who types
 * `http://` and is merely told "invalid" will get an opaque network error at runtime and
 * has no way to learn why — so [ChatUrlViolation.Cleartext] names the reason and offers the
 * `https://` form, which for this deployment is always the right correction.
 */
@Composable
fun ChatSignInScreen(
    state: ChatSignInUiState,
    onServerUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (Secret) -> Unit,
    onTogglePasswordVisible: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    /** Null when this is the app's gate, where there is nothing behind it to go back to. */
    onBack: (() -> Unit)? = null,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AppTopBar(
                title = "Sign in",
                navigationIcon = {
                    // Absent rather than disabled: a greyed-out arrow still invites a tap.
                    onBack?.let { back ->
                        IconButton(onClick = back) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(AppTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.medium),
        ) {
            state.banner?.let { error ->
                ChatErrorBanner(error = error, onRetry = onSubmit.takeIf { error.isRetryable })
            }

            ServerUrlField(state = state, onChange = onServerUrlChange)
            UsernameField(state = state, onChange = onUsernameChange)
            PasswordField(
                state = state,
                onChange = onPasswordChange,
                onToggleVisible = onTogglePasswordVisible,
            )
            SubmitButton(state = state, onSubmit = onSubmit)
        }
    }
}

/**
 * The address, with its violation and — when there is exactly one right correction — the
 * offer to apply it.
 *
 * The correction is a button rather than something applied silently: changing what somebody
 * typed without telling them is how they learn nothing and type it again next time.
 */
@Composable
private fun ServerUrlField(state: ChatSignInUiState, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.extraSmall)) {
        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = onChange,
            label = { Text("Server URL") },
            isError = state.urlError != null,
            enabled = !state.isSubmitting,
            singleLine = true,
            supportingText = { Text(state.urlError?.describe() ?: "The address of your Coral server.") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TAG_SERVER_URL),
        )

        state.urlError?.suggestion?.let { suggestion ->
            TextButton(
                onClick = { onChange(suggestion) },
                modifier = Modifier.testTag(TAG_URL_SUGGESTION),
            ) {
                Text("Use $suggestion")
            }
        }
    }
}

@Composable
private fun UsernameField(state: ChatSignInUiState, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = state.username,
        onValueChange = onChange,
        label = { Text("Username") },
        isError = state.usernameError,
        enabled = !state.isSubmitting,
        singleLine = true,
        supportingText = if (state.usernameError) ({ Text("Enter your username.") }) else null,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TAG_USERNAME),
    )
}

/**
 * The password, masked, with a reveal toggle.
 *
 * The single `reveal()` in this feature is here, where somebody has asked to look at what
 * they typed. Everywhere else the value travels as a [Secret].
 */
@Composable
private fun PasswordField(
    state: ChatSignInUiState,
    onChange: (Secret) -> Unit,
    onToggleVisible: () -> Unit,
) {
    OutlinedTextField(
        value = state.password.reveal(),
        onValueChange = { onChange(Secret(it)) },
        label = { Text("Password") },
        isError = state.passwordError != null,
        enabled = !state.isSubmitting,
        singleLine = true,
        visualTransformation = if (state.isPasswordVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            IconButton(onClick = onToggleVisible, modifier = Modifier.testTag(TAG_PASSWORD_REVEAL)) {
                Icon(
                    imageVector = if (state.isPasswordVisible) {
                        Icons.Filled.VisibilityOff
                    } else {
                        Icons.Filled.Visibility
                    },
                    contentDescription = if (state.isPasswordVisible) "Hide password" else "Show password",
                )
            }
        },
        supportingText = state.passwordError?.let { { Text(it.describe()) } },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TAG_PASSWORD),
    )
}

@Composable
private fun SubmitButton(state: ChatSignInUiState, onSubmit: () -> Unit) {
    Button(
        onClick = onSubmit,
        enabled = state.canSubmit,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AppTheme.spacing.small)
            .testTag(TAG_SUBMIT),
    ) {
        if (state.isSubmitting) {
            CircularProgressIndicator(
                strokeWidth = AppTheme.sizing.videoTileBorder,
                modifier = Modifier.size(AppTheme.sizing.chipIcon),
            )
        } else {
            Text("Sign in")
        }
    }
}

/** The wording for each URL violation. The parser decides what is wrong; this decides how to say it. */
internal fun ChatUrlViolation.describe(): String = when (this) {
    ChatUrlViolation.Blank -> "Enter your chat server's address."
    is ChatUrlViolation.NotAbsolute -> "Add https:// in front of the address."
    is ChatUrlViolation.Cleartext ->
        "This app will not send your password over an unencrypted connection. Use https://."
    is ChatUrlViolation.UnsupportedScheme -> "$scheme:// is not a web address. Use https://."
    ChatUrlViolation.Malformed -> "That is not a server address."
}

/** The wording for a platform failure, wherever it is shown. */
internal fun ChatAuthError.describe(): String = when (this) {
    ChatAuthError.InvalidCredentials -> "That username and password were not accepted."
    ChatAuthError.SessionExpired -> "Your chat session has expired. Sign in again."
    ChatAuthError.Network -> "Could not reach the server. Check your connection."
    is ChatAuthError.Server -> message ?: "The server returned an error ($httpStatus)."
    // Never "wrong password". A build with no key cannot sign anybody in, and sending the
    // user to change a credential that was correct is the failure this case exists to stop.
    ChatAuthError.CryptoUnavailable -> "Chat sign-in is not configured in this build."
    ChatAuthError.NotConfigured -> "No chat server has been set up yet."
}

@Composable
private fun ChatErrorBanner(error: ChatAuthError, onRetry: (() -> Unit)?) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TAG_BANNER),
    ) {
        Column(modifier = Modifier.padding(AppTheme.spacing.large)) {
            Text(text = error.describe(), style = MaterialTheme.typography.bodyMedium)
            onRetry?.let {
                TextButton(onClick = it, modifier = Modifier.testTag(TAG_BANNER_RETRY)) {
                    Text("Try again")
                }
            }
        }
    }
}

internal const val TAG_SERVER_URL = "chat-signin-url"
internal const val TAG_URL_SUGGESTION = "chat-signin-url-suggestion"
internal const val TAG_USERNAME = "chat-signin-username"
internal const val TAG_PASSWORD = "chat-signin-password"
internal const val TAG_PASSWORD_REVEAL = "chat-signin-password-reveal"
internal const val TAG_SUBMIT = "chat-signin-submit"
internal const val TAG_BANNER = "chat-signin-banner"
internal const val TAG_BANNER_RETRY = "chat-signin-banner-retry"

@ThemePreviews
@Composable
private fun ChatSignInPreview() = PreviewSurface {
    ChatSignInScreen(
        state = ChatSignInUiState(
            serverUrl = "https://gujlogin.coraltele.com",
            username = "sample-user",
            password = Secret("not-a-real-password"),
        ),
        onServerUrlChange = {},
        onUsernameChange = {},
        onPasswordChange = {},
        onTogglePasswordVisible = {},
        onSubmit = {},
        onBack = {},
    )
}

@ThemePreviews
@Composable
private fun ChatSignInRejectedPreview() = PreviewSurface {
    ChatSignInScreen(
        state = ChatSignInUiState(
            serverUrl = "http://gujlogin.coraltele.com",
            username = "sample-user",
            password = Secret("not-a-real-password"),
            urlError = ChatUrlViolation.Cleartext("https://gujlogin.coraltele.com"),
            banner = ChatAuthError.Network,
        ),
        onServerUrlChange = {},
        onUsernameChange = {},
        onPasswordChange = {},
        onTogglePasswordVisible = {},
        onSubmit = {},
        onBack = {},
    )
}
