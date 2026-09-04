package com.whatsappv2.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemeAndFontPreviews
import com.whatsappv2.core.designsystem.theme.AppTheme

/** What a call button does, which decides how it must look. */
enum class CallActionStyle {
    /** A toggle such as mute or speaker. Tinted when active. */
    TOGGLE,

    /** Answering a call. Always green, never re-tinted by dynamic colour. */
    ANSWER,

    /** Ending or rejecting a call. Always red, and larger. */
    HANG_UP,
}

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
    val diameter = if (style == CallActionStyle.TOGGLE) {
        AppTheme.sizing.callActionButton
    } else {
        AppTheme.sizing.callPrimaryButton
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(contentColor = foreground),
            modifier = Modifier
                .size(diameter)
                .clip(CircleShape)
                .background(if (enabled) background else background.copy(alpha = DISABLED_ALPHA))
                .semantics {
                    role = Role.Button
                    if (style == CallActionStyle.TOGGLE && activeStateDescription != null) {
                        stateDescription = activeStateDescription
                    }
                },
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(AppTheme.sizing.callActionIcon),
            )
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
            icon = Icons.Filled.VolumeUp,
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
