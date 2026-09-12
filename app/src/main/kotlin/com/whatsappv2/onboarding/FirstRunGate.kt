package com.whatsappv2.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Runs the first-run sequence, then hands over to [content] for good.
 *
 * ## Why the state is one enum and not three booleans in the composition
 *
 * Because the three screens are mutually exclusive and the transition between them is a
 * single value changing, which is exactly what [AnimatedContent] wants. The alternative —
 * nested `if`s — makes the *order* implicit in the nesting, and the order is the part with
 * a reason behind it. Here it is [firstRunStep], in one place, testable without a screen.
 *
 * The step is re-read from the stores after each completion rather than advanced by one,
 * so a user who somehow arrives with terms accepted and no tour seen gets the tour, not
 * whatever the counter says next.
 */
@Composable
internal fun FirstRunGate(
    store: FirstRunStore,
    permissionsNeeded: () -> Boolean,
    onPermissionsFinished: () -> Unit,
    permissionScreen: @Composable (onFinished: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // Bumped by each completion. Its value means nothing; changing it is what re-asks the
    // stores, which are the actual source of truth.
    var completions by remember { mutableStateOf(0) }
    val step = remember(completions) {
        firstRunStep(
            termsAccepted = store.hasAcceptedTerms(),
            tourSeen = store.hasSeenTour(),
            permissionsNeeded = permissionsNeeded(),
        )
    }

    AnimatedContent(
        targetState = step,
        modifier = modifier,
        transitionSpec = {
            // Forward motion, because this flow only ever goes forward. A cross-fade would
            // say "these are alternatives"; a slide says "you have moved on one".
            (slideInHorizontally { width -> width } + fadeIn())
                .togetherWith(slideOutHorizontally { width -> -width } + fadeOut())
        },
        label = "first-run",
    ) { current ->
        when (current) {
            FirstRunStep.TERMS -> TermsScreen(
                onAccept = {
                    store.acceptTerms()
                    completions++
                },
            )

            FirstRunStep.TOUR -> FeatureTour(
                onFinish = {
                    store.markTourSeen()
                    completions++
                },
            )

            FirstRunStep.PERMISSIONS -> permissionScreen {
                onPermissionsFinished()
                completions++
            }

            FirstRunStep.READY -> content()
        }
    }
}
