package com.whatsappv2.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme

/**
 * The terms, and the one decision that gets past them (first-run step 1).
 *
 * ## Accept is enabled from the start, and the scroll is nudged instead
 *
 * Gating the button on reaching the bottom is a common pattern and a bad one: it teaches
 * people to fling to the end, which is the opposite of reading, and it strands anyone
 * using a screen reader or a large font behind a control they cannot reach for reasons
 * they cannot see. What this does instead is tell the truth about the page — while there
 * is more below, a quiet "more below" marker sits under the text and the Accept button
 * says what accepting means. The user can still decide immediately, which is their right.
 *
 * ## Only one way forward, and it is not hidden
 *
 * There is no Decline button, because declining is not a state this app has — the way to
 * decline is not to use it, and offering a button that closes the app is a worse answer
 * than saying so. The last section says exactly that: uninstalling removes everything.
 */
@Composable
internal fun TermsScreen(
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
    sections: List<TermsSection> = TermsText,
) {
    val scroll = rememberScrollState()
    val moreBelow by remember { derivedStateOf { scroll.value < scroll.maxValue } }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            TermsHeader(modifier = Modifier.padding(AppTheme.spacing.extraLarge))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scroll)
                    .padding(horizontal = AppTheme.spacing.extraLarge),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.large),
            ) {
                sections.forEach { section ->
                    TermsSectionBlock(section)
                }
                Text(
                    text = "Version ${FirstRunStore.TERMS_VERSION}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = AppTheme.spacing.large),
                )
            }

            AcceptBar(moreBelow = moreBelow, onAccept = onAccept)
        }
    }
}

@Composable
private fun TermsHeader(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.large),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
            Icon(
                imageVector = Icons.Filled.Gavel,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(AppTheme.spacing.medium).size(AppTheme.sizing.chipIcon),
            )
        }
        Column {
            Text("Terms & Conditions", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "Please read these before you start.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TermsSectionBlock(section: TermsSection) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.extraSmall)) {
        Text(section.heading, style = MaterialTheme.typography.titleMedium)
        Text(
            text = section.body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The button, and the honest statement of what pressing it does.
 *
 * On its own surface with a divider above it, so it does not float over a sentence it is
 * cutting in half — and so it is in the same place whether the text is two screens long or
 * twelve.
 */
@Composable
private fun AcceptBar(moreBelow: Boolean, onAccept: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = AppTheme.spacing.extraSmall) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            HorizontalDivider()
            Column(
                modifier = Modifier.fillMaxWidth().padding(AppTheme.spacing.extraLarge),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.medium),
            ) {
                if (moreBelow) {
                    FilledTonalButton(onClick = {}, enabled = false) {
                        Icon(
                            Icons.Filled.ArrowDownward,
                            contentDescription = null,
                            modifier = Modifier.size(AppTheme.sizing.chipIcon),
                        )
                        Text(
                            text = "More below",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = AppTheme.spacing.small),
                        )
                    }
                }
                Button(
                    onClick = onAccept,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_ACCEPT_TERMS),
                ) {
                    Text("Accept and continue")
                }
                Text(
                    text = "By continuing you agree to the terms above.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** The one control that gets past this screen, so a test presses it rather than a word. */
internal const val TAG_ACCEPT_TERMS = "terms-accept"

@ThemePreviews
@Composable
private fun TermsScreenPreview() = PreviewSurface { TermsScreen(onAccept = {}) }
