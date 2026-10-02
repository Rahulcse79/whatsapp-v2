package com.whatsappv2.onboarding

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Nothing in the app until somebody is signed in.
 *
 * ## This reverses decision D4, deliberately
 *
 * D4 said sign-in gates the **Chats tab** and not the application, so that a signed-out
 * user kept a fully working Calls tab: "placing a call must not require a chat account."
 * That is no longer the product. The Coral login is now the app's login — it is also what
 * provisions the SIP extension this app calls from
 * ([com.whatsappv2.domain.usecase.EnsureChatExtensionUseCase]), so in practice a signed-out
 * user had no account to call with anyway. The two halves of the old argument had already
 * collapsed into one.
 *
 * What that costs, stated rather than discovered later: a user who is signed out cannot
 * reach the dialler, the call log, Settings or the account list. Incoming calls are not
 * affected — they arrive through `SipConnectionService` and `CallActivity`, which are not
 * behind this composable — but a device with no session has no provisioned account to be
 * reached on, so that is theory rather than a path anybody walks.
 *
 * ## Why it is not a step in [FirstRunGate]
 *
 * Because the two are different shapes. Terms and the tour are answered once and read from
 * a store, which is why that gate re-reads on a counter. A session comes and goes: it
 * expires, it is signed out of, and the screen has to follow it **reactively**. Folding
 * this into [FirstRunStep] would gate on a value that is only re-read when some other step
 * completes, so signing out would leave the app on screen until something else happened.
 *
 * It sits *inside* the first-run gate, so terms are accepted before anybody is asked for a
 * password.
 *
 * ## A pending destination survives the gate
 *
 * `MainActivity` holds the route an outside intent asked for in its own state and hands it
 * to `AppRoot`, whose `LaunchedEffect` navigates there on first composition. While this
 * gate is closed `AppRoot` is not composed at all, so such a request is **deferred rather
 * than dropped**: it is still in the activity's state and is honoured the moment signing in
 * composes the shell. Verified on a device only to the point of being held — the far side
 * needs a session.
 *
 * ## The third state is the one worth having
 *
 * [SignInGateState.Unknown] exists so the form does not flash on every cold start. The
 * session is read from disk, and a gate that assumed "signed out" until told otherwise
 * would show a login screen for a frame or two to somebody who is signed in — which looks
 * exactly like having been signed out.
 */
@Composable
internal fun SignInGate(
    modifier: Modifier = Modifier,
    viewModel: SignInGateViewModel = hiltViewModel(),
    signInScreen: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Crossfade rather than the first-run gate's slide: this is not forward motion through
    // a sequence, it is the same app appearing and disappearing behind one fact.
    Crossfade(targetState = state, modifier = modifier, label = "sign-in-gate") { current ->
        when (current) {
            // Deliberately empty and the right colour, not a spinner. The disk read takes
            // a frame or two; a spinner that appears and vanishes that fast reads as a flash.
            SignInGateState.Unknown -> Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxSize(),
            ) { Box(Modifier.fillMaxSize()) }

            SignInGateState.SignedOut -> signInScreen()
            SignInGateState.SignedIn -> content()
        }
    }
}
