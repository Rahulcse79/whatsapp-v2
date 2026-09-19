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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.whatsappv2.feature.settings.BackgroundAccessLink

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

/**
 * The settings row's view of battery optimisation, re-read every time the app comes back.
 *
 * ## Why a lifecycle effect and not just a read
 *
 * This used to be `access.isExempt()` evaluated inline in the nav graph, with a comment
 * saying the state was re-read "each time the screen composes, so coming back from the
 * system dialog shows the new answer". Composition is not the same thing as resumption,
 * and that is exactly where it failed: granting the exemption sends the user to a system
 * activity and back, and returning re-*starts* this app's activity without invalidating a
 * composition that never changed. Nothing recomposed, nothing re-read, and the row went on
 * saying *Restricted* over a phone that had just been told to allow it. The only way to
 * see the truth was to leave the screen and come back, which is the one thing a user who
 * has just done what the row asked has no reason to do.
 *
 * `ON_RESUME` is the moment the answer can have changed and the only moment it can: the
 * setting is not ours to write, so nothing in this process moves it, and every path that
 * does — the dialog, the full battery-optimisation list, the system settings app — leaves
 * this activity and comes back through here. The first read is the composition's; every
 * one after it is a return.
 *
 * Returns null when there is no [BackgroundAccess] in the composition, which is how a
 * preview and a test get a settings screen with no row rather than one wired to nothing.
 */
@Composable
fun rememberBackgroundAccessLink(): BackgroundAccessLink? {
    val access = LocalBackgroundAccess.current ?: return null
    val context = LocalContext.current

    return rememberBackgroundAccessLink(
        isExempt = access::isExempt,
        onOpen = {
            // The per-package dialog first, because it is one tap and it is the one the
            // user is being asked for. The full list is the fallback for a handset whose
            // dialog is missing or whose OEM refuses the direct request.
            runCatching { context.startActivity(access.requestIntent()) }
                .onFailure { runCatching { context.startActivity(access.settingsIntent()) } }
        },
    )
}

/**
 * The rule on its own, with the two Android questions passed in.
 *
 * Split out so the refresh can be asserted without a `PowerManager`, a system dialog, or
 * an `Activity` to come back to — a test moves a lifecycle owner to `ON_RESUME` and reads
 * what [isExempt] now returns, which is precisely the sequence the defect broke.
 *
 * [isExempt] is called once when this enters the composition and once per resume after
 * that, never per recomposition: it is a system call, and the screen it feeds is otherwise
 * static.
 */
@Composable
internal fun rememberBackgroundAccessLink(
    isExempt: () -> Boolean,
    onOpen: () -> Unit,
): BackgroundAccessLink {
    // Deliberately unkeyed. `isExempt` is a fresh lambda on every composition, so keying
    // on it would reset the state each time and re-read exactly what this avoids.
    var allowed by remember { mutableStateOf(isExempt()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = isExempt() }

    return BackgroundAccessLink(allowed = allowed, onOpen = onOpen)
}

const val TAG_BACKGROUND_ACCESS_PROMPT = "background-access-prompt"
const val TAG_BACKGROUND_ACCESS_ALLOW = "background-access-allow"
