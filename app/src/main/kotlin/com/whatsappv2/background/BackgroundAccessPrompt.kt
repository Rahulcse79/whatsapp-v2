package com.whatsappv2.background

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag

/**
 * The [BackgroundAccess] for this composition, provided by the activity like the
 * permission coordinator is: a settings row deep in the tree needs it, and threading it
 * through every screen in between would couple them all to it.
 */
val LocalBackgroundAccess = staticCompositionLocalOf<BackgroundAccess?> { null }

/**
 * Asks, once, to be left running in the background.
 *
 * Mounted above the app's screens, so it appears wherever the user is when the first
 * account registers. "Allow" opens the system dialog — the decision is the platform's to
 * take, not this dialog's — and either button marks the question as asked.
 */
@Composable
fun BackgroundAccessPrompt(wantsRegistration: Boolean) {
    val access = LocalBackgroundAccess.current ?: return
    val context = LocalContext.current
    var dismissed by remember { mutableStateOf(false) }
    val show = !dismissed && BackgroundAccessPolicy.shouldAsk(
        wantsRegistration = wantsRegistration,
        exempt = access.isExempt(),
        askedBefore = access.hasBeenAsked(),
    )
    if (!show) return

    fun close() {
        access.markAsked()
        dismissed = true
    }

    AlertDialog(
        modifier = Modifier.testTag(TAG_BACKGROUND_ACCESS_PROMPT),
        onDismissRequest = ::close,
        title = { Text("Keep receiving calls in the background") },
        text = {
            Text(
                "This phone may stop the app when it is not on screen, and calls to your " +
                    "extension would then be missed until you open it again. Allowing the app " +
                    "to run in the background keeps your registration alive.",
            )
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag(TAG_BACKGROUND_ACCESS_ALLOW),
                onClick = {
                    close()
                    runCatching { context.startActivity(access.requestIntent()) }
                        .onFailure { runCatching { context.startActivity(access.settingsIntent()) } }
                },
            ) { Text("Allow") }
        },
        dismissButton = {
            TextButton(onClick = ::close) { Text("Not now") }
        },
    )
}

const val TAG_BACKGROUND_ACCESS_PROMPT = "background-access-prompt"
const val TAG_BACKGROUND_ACCESS_ALLOW = "background-access-allow"
