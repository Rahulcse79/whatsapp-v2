package com.whatsappv2.feature.calls

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import com.whatsappv2.core.designsystem.theme.AppTheme

/**
 * A conference's video (Task 61, §2.2, DoD 11).
 *
 * ## What is actually on screen, and what is not
 *
 * Under the dial-in MCU this app ships against (ADR-003), the bridge composes every
 * participant into **one** picture and sends that. So there is one video surface here, not
 * a tile per person — and the arrangement inside it is the *server's*, not this app's.
 *
 * That limitation is stated on screen rather than hidden, which is Task 61's third
 * done-when and §13's rule generally. The alternative — drawing a client-side grid over a
 * mixed stream — produces a grid of identical copies of the same picture, which looks like
 * a feature and is a lie.
 *
 * [ConferenceVideoLayout] already knows how to arrange per-participant tiles for the day
 * the transport becomes an SFU; [ConferenceVideoMode.Grid] and
 * [ConferenceVideoMode.ActiveSpeaker] are unreachable today and say so in their own
 * documentation rather than being left as dead code nobody can explain.
 */
@Composable
internal fun ConferenceVideo(
    call: CallDisplay,
    conference: ConferenceUiState,
    actions: CallActions,
    modifier: Modifier = Modifier,
) {
    // Measured from the space this composable actually has, not from the screen. A
    // `LocalConfiguration` read would describe the display even when the video sits in half
    // of it, and would be wrong on a foldable or in split-screen — which is precisely where
    // "adapts to rotation" stops being a rotation question.
    BoxWithConstraints(modifier = modifier.fillMaxSize().testTag(TAG_CONFERENCE_VIDEO)) {
        val mode = ConferenceVideoLayout.of(
            participantCount = conference.participants.size,
            hasVideo = call.showsRemoteVideo,
            // False today, and read from the roster rather than assumed: the model carries
            // it so the transport can change without this composable being rewritten.
            perParticipantVideo = false,
            isLandscape = maxWidth > maxHeight,
        )

        when (mode) {
            // Audio conference. The roster is the screen, and it is drawn by the caller.
            is ConferenceVideoMode.AudioOnly -> Unit

            is ConferenceVideoMode.MixedStream -> Column(modifier = Modifier.fillMaxSize()) {
                CallVideo(call = call, actions = actions, modifier = Modifier.weight(1f))
                MixedStreamNote()
            }

            // Unreachable with a mixing bridge. Rendered as the mixed stream rather than
            // as an empty box, so a future SFU shows *something* before its tiles are
            // built rather than a black rectangle nobody can explain.
            is ConferenceVideoMode.Grid,
            is ConferenceVideoMode.ActiveSpeaker,
            -> CallVideo(call = call, actions = actions)
        }
    }
}

/**
 * The sentence that keeps the mixed-stream model honest.
 *
 * Small and permanent rather than a dismissible tip: somebody joining a conference on this
 * app should not have to work out why they cannot pin a speaker, and the answer is not a
 * missing feature — it is where the composition happens.
 */
@Composable
private fun MixedStreamNote(modifier: Modifier = Modifier) {
    Text(
        text = "The conference server composes this video. Who appears, and how they are " +
            "arranged, is chosen there rather than on this device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(AppTheme.spacing.small)
            .testTag(TAG_MIXED_STREAM_NOTE),
    )
}

internal const val TAG_CONFERENCE_VIDEO = "conference-video"
internal const val TAG_MIXED_STREAM_NOTE = "conference-mixed-stream-note"
