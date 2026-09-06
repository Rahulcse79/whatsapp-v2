package com.whatsappv2.feature.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.whatsappv2.core.designsystem.theme.AppTheme

/**
 * The recording indicator (Task 58, §2.6, §7).
 *
 * ## Why it is a banner and not an icon
 *
 * Task 58's second done-when is that a visible indicator is present for the **entire**
 * duration of any recording. An icon in a control row satisfies the letter of that and not
 * its purpose: it is small, it sits among seven other icons, and the person most likely to
 * miss it is the one holding the phone.
 *
 * It renders from [RecordingUiState.isRecording], which is derived from
 * `CallRecorder.active` — the recorder's own account of what it is writing. There is no
 * way for a recording to be running with this absent, because nothing else can put a
 * recording into that set.
 *
 * `liveRegion` so a screen reader announces it when it appears. A recording that starts
 * silently for a blind user is a silent recorder, which is the thing §2.6 forbids.
 */
@Composable
internal fun RecordingBanner(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppTheme.radius.small))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(AppTheme.spacing.small)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(TAG_RECORDING_BANNER),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.small),
    ) {
        Icon(
            imageVector = Icons.Filled.FiberManualRecord,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(AppTheme.spacing.large),
        )
        Text(
            text = "Recording this call",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/**
 * The other call, waiting (Task 56, §5.2).
 *
 * Shown whenever there is a second call, and tappable to swap to it. Its presence is what
 * makes "exactly one active, one held" visible to the person it protects: a user who
 * cannot see the held call has no way to tell a successful hold from a dropped one.
 *
 * The held call's own name, not "1 other call" — the user is choosing between two people,
 * and a count does not help them do that.
 */
@Composable
internal fun HeldCallBanner(
    other: CallDisplay,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppTheme.radius.small))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onSwap)
            .padding(AppTheme.spacing.small)
            .testTag(TAG_HELD_BANNER),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.small),
    ) {
        Icon(
            imageVector = Icons.Filled.SwapVert,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(AppTheme.spacing.large),
        )
        Text(
            text = "${other.title} is on hold — tap to swap",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal const val TAG_RECORDING_BANNER = "call-recording-banner"
internal const val TAG_HELD_BANNER = "call-held-banner"
