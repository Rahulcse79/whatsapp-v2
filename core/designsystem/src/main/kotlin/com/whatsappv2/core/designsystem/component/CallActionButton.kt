package com.whatsappv2.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemeAndFontPreviews
import com.whatsappv2.core.designsystem.theme.AppTheme

/**
 * A round in-call control.
 *
 * Three properties this deliberately guarantees:
 *  - [CallActionStyle.ANSWER] and [CallActionStyle.HANG_UP] take their colours from the
 *    fixed palette, never from dynamic colour. A wallpaper-derived "end call" button
 *    that is not recognisably red is a genuinely dangerous piece of UI.
 *  - A toggle announces its state through `stateDescription`, so a screen reader says
 *    "muted" rather than leaving the user to infer it from a tint they cannot see.
 *  - `enabled` is driven by call state, never by an ad-hoc boolean: the state machine
 *    already knows whether hold is legal right now (§4.4).
 *
 * [pending] is the fourth (Task 76). A control whose engine round trip is still running
 * shows a spinner in place of its icon and refuses a second press. It is deliberately not
 * the same thing as showing the new state early: this button must never say "muted" over
 * a live microphone, so the press is acknowledged and the *state* still waits for the
 * engine's answer.
 */
@Composable
fun CallActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: CallActionStyle = CallActionStyle.TOGGLE,
    enabled: Boolean = true,
    active: Boolean = false,
    activeStateDescription: String? = null,
    label: String? = null,
    pending: Boolean = false,
    pendingStateDescription: String = "Working",
) {
    val callColors = AppTheme.callColors
    val background = when {
        style == CallActionStyle.ANSWER -> callColors.answer
        style == CallActionStyle.HANG_UP -> callColors.hangUp
        active -> callColors.activeControl
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when {
        style == CallActionStyle.ANSWER -> callColors.onAnswer
        style == CallActionStyle.HANG_UP -> callColors.onHangUp
        active -> callColors.onActiveControl
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val accessibilityLabel = contentDescription
    val diameter = if (style == CallActionStyle.TOGGLE) {
        AppTheme.sizing.callActionButton
    } else {
        AppTheme.sizing.callPrimaryButton
    }

    Column(
        // Pending counts as unpressable: anything asking this node must be told the
        // control cannot be used right now, not merely that it looks busy.
        modifier = modifier.enabledState(enabled && !pending),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled && !pending,
            colors = IconButtonDefaults.iconButtonColors(contentColor = foreground),
            modifier = Modifier
                .size(diameter)
                .clip(CircleShape)
                .background(if (enabled) background else background.copy(alpha = DISABLED_ALPHA))
                .semantics {
                    role = Role.Button
                    // While the engine is answering, that is the state worth announcing:
                    // a screen reader saying "not muted" during a mute is telling the
                    // user the press did nothing.
                    if (pending) {
                        stateDescription = pendingStateDescription
                    } else if (style == CallActionStyle.TOGGLE && activeStateDescription != null) {
                        stateDescription = activeStateDescription
                    }
                },
        ) {
            if (pending) {
                // The content description stays on the spinner, so the control keeps its
                // name while it works rather than becoming an unlabelled busy circle.
                CircularProgressIndicator(
                    color = foreground,
                    modifier = Modifier
                        .size(AppTheme.sizing.callActionIcon)
                        .semantics { contentDescription = accessibilityLabel },
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(AppTheme.sizing.callActionIcon),
                )
            }
        }
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = AppTheme.spacing.extraSmall),
            )
        }
    }
}

/**
 * Publishes the control's enabled state on the node the caller tagged.
 *
 * The caller's modifier lands on the outer column, so that node is the control as far as
 * anything outside is concerned, while `enabled` reaches only the button inside it.
 * Without this, anything asking the published node is told a disabled button is fine to
 * press.
 */
private fun Modifier.enabledState(enabled: Boolean): Modifier =
    semantics { if (!enabled) disabled() }

private const val DISABLED_ALPHA = 0.38f

@ThemeAndFontPreviews
@Composable
private fun CallActionButtonRowPreview() = PreviewSurface {
    Row(
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.large),
        modifier = Modifier.padding(AppTheme.spacing.large),
    ) {
        CallActionButton(
            icon = Icons.Filled.MicOff,
            contentDescription = "Unmute microphone",
            activeStateDescription = "Muted",
            onClick = {},
            active = true,
            label = "Mute",
        )
        CallActionButton(
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = "Turn on speakerphone",
            activeStateDescription = "Off",
            onClick = {},
            label = "Speaker",
        )
        CallActionButton(
            icon = Icons.Filled.Mic,
            contentDescription = "Hold",
            onClick = {},
            enabled = false,
            label = "Hold",
        )
        CallActionButton(
            icon = Icons.Filled.Mic,
            contentDescription = "Mute microphone",
            onClick = {},
            label = "Mute",
            pending = true,
            pendingStateDescription = "Muting",
        )
    }
}

@ThemeAndFontPreviews
@Composable
private fun CallAnswerAndHangUpPreview() = PreviewSurface {
    Row(
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.extraLarge),
        modifier = Modifier.padding(AppTheme.spacing.large),
    ) {
        CallActionButton(
            icon = Icons.Filled.Call,
            contentDescription = "Answer call",
            onClick = {},
            style = CallActionStyle.ANSWER,
            label = "Answer",
        )
        CallActionButton(
            icon = Icons.Filled.CallEnd,
            contentDescription = "End call",
            onClick = {},
            style = CallActionStyle.HANG_UP,
            label = "End",
        )
    }
}
