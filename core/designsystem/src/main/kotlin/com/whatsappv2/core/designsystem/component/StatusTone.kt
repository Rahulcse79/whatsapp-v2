package com.whatsappv2.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme

/**
 * The four states a status dot can show (item 5.5).
 *
 * An enum in the design system rather than a colour parameter, so a screen says *what*
 * the state is and this file decides how that looks. A caller that passed a colour could
 * pass the wrong one; a caller that passes [FAILED] cannot.
 */
enum class StatusTone {
    /** Working: registered, connected, up. */
    ONLINE,

    /** In flight: registering, reconnecting, retrying. */
    CONNECTING,

    /** Broken, and somebody has to act. */
    FAILED,

    /** Deliberately off. */
    OFFLINE,
}

/**
 * A coloured dot, for a status that also has words beside it.
 *
 * Decorative in the accessibility tree on purpose. Colour must never be the only channel,
 * so the caller always places the state in words next to this — and announcing the dot as
 * well would read the same state twice. [StatusLabel] is the pairing done for you.
 */
@Composable
fun StatusDot(
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(AppTheme.sizing.statusDot)
            .background(tone.colour(), CircleShape)
            .clearAndSetSemantics { },
    )
}

/**
 * A dot and the state it stands for, in words, on one line.
 *
 * The words are the accessible content; the dot is how the eye finds the state in a bar
 * full of text. Both are always present, which is the rule this component exists to make
 * hard to break.
 */
@Composable
fun StatusLabel(
    tone: StatusTone,
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.extraSmall),
    ) {
        StatusDot(tone)
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
private fun StatusTone.colour(): Color = when (this) {
    StatusTone.ONLINE -> AppTheme.statusColors.online
    StatusTone.CONNECTING -> AppTheme.statusColors.connecting
    StatusTone.FAILED -> AppTheme.statusColors.failed
    StatusTone.OFFLINE -> AppTheme.statusColors.offline
}

@ThemePreviews
@Composable
private fun StatusLabelPreview() = PreviewSurface {
    Column {
        StatusLabel(StatusTone.ONLINE, "Registered")
        StatusLabel(StatusTone.CONNECTING, "Reconnecting…")
        StatusLabel(StatusTone.FAILED, "Check your details")
        StatusLabel(StatusTone.OFFLINE, "Offline")
    }
}
