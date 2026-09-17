package com.whatsappv2.feature.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.whatsappv2.core.designsystem.theme.AppTheme
import com.whatsappv2.domain.engine.VideoSizes

/**
 * A conference's video (Task 61, §2.2, DoD 11).
 *
 * ## One picture, and now it really does hold everybody
 *
 * Under the dial-in MCU this app ships against (ADR-003), the bridge composes every
 * participant into **one** picture and sends that. So there is one video surface here,
 * not a tile per person — the arrangement inside it is the *server's*.
 *
 * Until 2026-09-15 that arrangement was a lie by omission. Extension 3000 landed in
 * FreeSWITCH's `default` conference profile, which sets no `video-mode`, and
 * mod_conference's default is passthrough: it forwards the floor holder's camera and
 * nobody else's. Three handsets joined and all three saw the same single face. The room
 * now uses a `whatsapp-video` profile with `video-mode=mux` and a portrait layout group,
 * so the one picture is a grid of everybody — which is what this composable was always
 * drawing, and at last what the bridge is sending.
 *
 * ## Why this fills the screen and says almost nothing
 *
 * It used to put a grey paragraph under the video explaining that the server chose the
 * layout. That sentence was honest and it was also the reason the picture was a letterbox
 * in the top two-thirds of the screen with an explanation beneath it, which is not what a
 * video call looks like to anybody. The limitation is still stated — it moved into the
 * roster, which is where somebody wondering "why can't I pin a speaker" is looking — and
 * the picture got the screen.
 *
 * [RemoteVideoScaling.Fit], never Cover: the picture is a grid of people, and cropping it
 * to fill a 9:20 handset would cut the outer column off somebody's conference.
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
    sizes: VideoSizes = VideoSizes.UNKNOWN,
    onPictureTap: (() -> Unit)? = null,
    pictureTapLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    // Measured from the space this composable actually has, not from the screen. A
    // `LocalConfiguration` read would describe the display even when the video sits in half
    // of it, and would be wrong on a foldable or in split-screen — which is precisely where
    // "adapts to rotation" stops being a rotation question.
    BoxWithConstraints(modifier = modifier.fillMaxSize().testTag(TAG_CONFERENCE_VIDEO)) {
        val mode = ConferenceVideoLayout.of(
            participantCount = conference.participants.size,
            // Either picture counts. On `showsRemoteVideo` alone this fell to AudioOnly —
            // which draws nothing — for the whole window between joining the room and the
            // bridge's first composed frame, taking the local preview down with it. A
            // conference the user has just joined with their camera on must show them
            // their own camera while the canvas is still on its way.
            hasVideo = call.showsAnyVideo,
            // False today, and read from the roster rather than assumed: the model carries
            // it so the transport can change without this composable being rewritten.
            perParticipantVideo = false,
            isLandscape = maxWidth > maxHeight,
        )

        when (mode) {
            // Audio conference. The roster is the screen, and it is drawn by the caller.
            is ConferenceVideoMode.AudioOnly -> Unit

            // Black behind the picture, not the surface colour: a composed grid is
            // letterboxed by definition on a handset, and the bars should read as the
            // edge of the video rather than as a gap in the app.
            is ConferenceVideoMode.MixedStream,
            // Unreachable with a mixing bridge. Rendered as the mixed stream rather than
            // as an empty box, so a future SFU shows *something* before its tiles are
            // built rather than a black rectangle nobody can explain.
            is ConferenceVideoMode.Grid,
            is ConferenceVideoMode.ActiveSpeaker,
            -> Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                CallVideo(
                    call = call,
                    actions = actions,
                    sizes = sizes,
                    scaling = RemoteVideoScaling.Fit,
                    onPictureTap = onPictureTap,
                    pictureTapLabel = pictureTapLabel,
                )
                ConferenceBadge(
                    conference = conference,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .systemBarsPadding()
                        .padding(AppTheme.spacing.large),
                )
            }
        }
    }
}

/**
 * How many people are in the picture, over the top-left corner of it.
 *
 * The count belongs on the video rather than only in the roster below, because on a grid
 * of four small faces "am I seeing everybody?" is the first question, and the composed
 * stream cannot answer it — a participant who has not turned their camera on is in the
 * conference and not in the picture. A number the bridge published, against a picture it
 * composed, is how the two are reconciled.
 *
 * Absent entirely when the bridge publishes no roster. A badge reading "0" over four
 * visible faces would be worse than no badge, and [ConferenceRoster] is where the app
 * says that it cannot see the list.
 */
@Composable
private fun ConferenceBadge(conference: ConferenceUiState, modifier: Modifier = Modifier) {
    val count = conference.count ?: return

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(AppTheme.radius.full))
            .background(Color.Black.copy(alpha = BADGE_SCRIM))
            .padding(horizontal = AppTheme.spacing.medium, vertical = AppTheme.spacing.small)
            .semantics { contentDescription = "$count people in this conference" }
            .testTag(TAG_CONFERENCE_BADGE),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.small),
    ) {
        Icon(
            imageVector = Icons.Filled.Groups,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(AppTheme.sizing.chipIcon),
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
    }
}

/** Dark enough to read white text over any frame, light enough to see the picture through. */
private const val BADGE_SCRIM = 0.45f

internal const val TAG_CONFERENCE_VIDEO = "conference-video"
internal const val TAG_CONFERENCE_BADGE = "conference-badge"
