package com.whatsappv2.feature.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import com.whatsappv2.core.designsystem.theme.AppTheme

/**
 * Who else is in the conference (Task 60, §2.2, DoD 11).
 *
 * ## The empty case is three cases, and only one of them is "nobody"
 *
 * §13 and Task 60's third done-when are the same rule seen twice: a bridge that publishes
 * no roster must be *said* to publish none, not drawn as an empty room. So this renders
 * three different things —
 *
 * - **No roster at all**: a line saying the server does not publish one. The call still
 *   works; the app simply does not know who is there, and claiming otherwise would be an
 *   invention.
 * - **A roster with only you in it**: you are the first to arrive. That is knowledge, and
 *   it is different from the line above.
 * - **A roster with other people**: the list.
 *
 * A single "no participants" state would collapse the first two, and the collapse is the
 * dishonest direction: it turns "we cannot see" into "there is nobody".
 *
 * ## Mute is the bridge's, not ours
 *
 * The mute icon means the *bridge* reports that participant as muted. The local mute
 * button is a different thing entirely and lives with the call controls; showing them in
 * one list would suggest this app can mute other people, which under a dial-in MCU it
 * cannot.
 *
 * ## Over video it is a card, not a column of text
 *
 * With [composedVideo] set the roster is drawn on a dark scrim in white, because it is
 * sitting on top of a picture whose colours are unknown, and `onSurface` over somebody's
 * face is a paragraph you cannot read. The scrim is also what stops it looking like the
 * app has printed a list across the call.
 *
 * That flag is also the honest place for the thing the screen used to say in grey under
 * the video: that the bridge, not this app, decides the arrangement. Somebody wondering
 * why they cannot pin a speaker is reading the participant list, not the middle of the
 * picture — so the sentence lives here now, and the video got its screen back.
 */
@Composable
internal fun ConferenceRoster(
    state: ConferenceUiState,
    composedVideo: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val primary = if (composedVideo) Color.White else MaterialTheme.colorScheme.onSurface
    val secondary =
        if (composedVideo) Color.White.copy(alpha = SCRIM_TEXT) else MaterialTheme.colorScheme.onSurfaceVariant
    val surface = if (composedVideo) {
        Modifier
            .clip(RoundedCornerShape(AppTheme.radius.large))
            .background(Color.Black.copy(alpha = SCRIM))
            .padding(AppTheme.spacing.medium)
    } else {
        Modifier
    }

    Column(
        modifier = modifier.fillMaxWidth().then(surface).testTag(TAG_ROSTER),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.extraSmall),
    ) {
        Text(
            text = state.heading(),
            style = MaterialTheme.typography.titleSmall,
            color = secondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        if (!state.rosterAvailable) {
            Text(
                text = "This bridge does not publish a participant list, so the app cannot " +
                    "say who else is here.",
                style = MaterialTheme.typography.bodySmall,
                color = secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag(TAG_NO_ROSTER),
            )
            return@Column
        }

        state.participants.forEach { participant ->
            ParticipantRow(participant, primary = primary, secondary = secondary)
        }

        if (composedVideo) {
            Text(
                text = "The conference server composes this picture, so who is on screen " +
                    "and how they are arranged is decided there.",
                style = MaterialTheme.typography.bodySmall,
                color = secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AppTheme.spacing.small)
                    .testTag(TAG_MIXED_STREAM_NOTE),
            )
        }
    }
}

@Composable
private fun ParticipantRow(
    participant: ConferenceParticipantRow,
    primary: Color,
    secondary: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = AppTheme.spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.small),
    ) {
        Text(
            text = if (participant.isSelf) "${participant.label} (you)" else participant.label,
            style = MaterialTheme.typography.bodyMedium,
            color = primary,
            modifier = Modifier.weight(1f),
        )

        if (participant.isSpeaking) {
            Icon(
                imageVector = Icons.Filled.GraphicEq,
                contentDescription = "Speaking",
                tint = primary,
            )
        }
        if (participant.isMuted) {
            Icon(
                imageVector = Icons.Filled.MicOff,
                contentDescription = "Muted by the bridge",
                tint = secondary,
            )
        }
    }
}

/**
 * What to call the section, given what is actually known.
 *
 * A count only when there is a roster to count. "In this conference" with no number is the
 * honest heading for a bridge that does not say — better than "0 participants", which is a
 * claim, and better than omitting the heading, which hides that this is a conference.
 */
private fun ConferenceUiState.heading(): String = when {
    !rosterAvailable -> "In this conference"
    participants.isEmpty() -> "In this conference — just you so far"
    else -> "In this conference — ${participants.size}"
}

/** Dark enough for white text over any frame; light enough to see the call through. */
private const val SCRIM = 0.55f

/** Secondary text, dimmed the way `onSurfaceVariant` is against `onSurface`. */
private const val SCRIM_TEXT = 0.75f

internal const val TAG_ROSTER = "conference-roster"
internal const val TAG_NO_ROSTER = "conference-no-roster"
internal const val TAG_MIXED_STREAM_NOTE = "conference-mixed-stream-note"
