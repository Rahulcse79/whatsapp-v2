package com.whatsappv2.core.designsystem.window

import android.app.Activity
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Marks the current screen as secure: no screenshot, no screen recording, no thumbnail in
 * the recents list (Tasks 21 and 63, §7, DoD 12).
 *
 * ## Why it is a composable and not a manifest attribute
 *
 * `FLAG_SECURE` is a *window* flag, and this app has one window carrying many screens. Set
 * on the Activity it would cover the dialler and the call history too — which sounds
 * cautious and is not free: a user who cannot screenshot a call log cannot send a support
 * request, and a blanket flag teaches people the app is broken rather than careful.
 *
 * So it is scoped to the screens that actually show a credential. The `DisposableEffect`
 * is the whole mechanism: the flag goes on when the screen enters composition and comes
 * off when it leaves, including on a back press, a process pause, and a navigation the
 * screen did not initiate.
 *
 * ## What it does and does not stop
 *
 * It stops the *platform's* capture paths — `adb screencap`, the screenshot gesture, screen
 * recorders, and the recents thumbnail. It does not stop a photograph of the screen, and
 * it is not a substitute for the password never being displayed in the first place, which
 * is why the editor's password field is masked and blank on reopen.
 *
 * ## Not in `component/`
 *
 * It draws nothing, so it is not a component and has no preview to show. The architecture
 * rule that requires `@ThemePreviews` on design-system components is scoped to that
 * package, and putting a window effect there to satisfy it would mean writing a preview
 * of nothing.
 */
@Composable
fun SecureScreen() {
    val window = LocalContext.current.findActivity()?.window ?: return

    DisposableEffect(window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/**
 * The [Activity] behind a composition's context, or null.
 *
 * Unwrapped rather than cast: Compose hands out a `ContextWrapper` in several situations —
 * a themed context, a dialog window, an `AndroidView` — and a bare cast returns null in
 * exactly the cases where the flag matters. Null is still possible (a preview, a test
 * harness with no Activity), and it is treated as "nothing to secure" rather than as an
 * error, because a preview that crashed on a window flag would be a worse outcome than a
 * preview that is not secure.
 */
private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
