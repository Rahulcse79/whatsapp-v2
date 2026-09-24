package com.whatsappv2.feature.calls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.whatsappv2.core.designsystem.component.Avatar
import com.whatsappv2.core.designsystem.component.StatusLabel
import com.whatsappv2.core.designsystem.component.StatusTone
import com.whatsappv2.core.designsystem.theme.AppTheme
import com.whatsappv2.domain.model.CallId

/**
 * Who is in the conference (Task 60, §2.2, DoD 11), as a card that drops its list down.
 *
 * ## A dropdown, because the list does not fit and the controls must
 *
 * A conference of eight is nine rows including the user, and nine rows do not fit between
 * the title and the control grid on a handset. The header — a count and a chevron — is
 * what the screen shows by default; tapping it drops the list down beneath, bounded by
 * the space the controls leave and scrolling inside it. Collapsed, the screen reads as a
 * call; expanded, it reads as a room. The choice survives rotation.
 *
 * ## The empty case is three cases, and only one of them is "nobody"
 *
 * §13 and Task 60's third done-when are the same rule seen twice: a bridge that publishes
 * no roster must be *said* to publish none, not drawn as an empty room. So the header says
 * three different things —
 *
 * - **No roster at all**: that the server does not publish one. The call still works; the
 *   app simply does not know who is there, and claiming otherwise would be an invention.
 *   There is nothing to drop down, and the chevron is not offered.
 * - **A roster with only you in it**: you are the first to arrive. That is knowledge, and
 *   it is different from the line above.
 * - **A roster with other people**: the count, and the list on request.
 *
 * And a fourth, since 2026-09-21, which is the one this deployment actually produces: **no
 * roster, but the members this device merged into the room**. Named, not counted — "You,
 * 1004 and 1005" — because the names are what the device knows and the count is what it
 * does not; a member who accepted the transfer and never reached the room is in the names
 * and not in the room. The dropped-down list says where the names came from. See
 * [ConferenceUiState.fromMerge]. The list is also *stable*: it comes from the merge that
 * built the conference, not from the legs, which end the moment their transfers complete
 * — so the people the user merged do not vanish from the screen a second after Merge.
 *
 * ## Whose microphone the icon reports
 *
 * On the user's own row it is this device's microphone, which the user can fix with the
 * Mute button beside it. On everyone else's it is what the bridge said — the local mix
 * reports nobody, because it cannot know — and under a dial-in MCU this app cannot change
 * it, which is why the list offers no per-member controls.
 *
 * ## Over video it is a dark card, not a light one
 *
 * With [composedVideo] set the card is translucent black in white, because it is sitting
 * on top of a picture whose colours are unknown, and `onSurface` over somebody's face is a
 * paragraph you cannot read. That case is also where the sentence about the bridge
 * composing the picture belongs: somebody wondering why they cannot pin a speaker is
 * reading the participant list, not the middle of the picture.
 */
@Composable
internal fun ConferenceRoster(
    state: ConferenceUiState,
    composedVideo: Boolean = false,
    /**
     * Drops one member, when this device is the conference's focus and the row is backed
     * by a leg. Null everywhere else, which is what removes the control rather than
     * showing one that cannot work — see [ConferenceUiState.canRemoveParticipants].
     */
    onRemoveParticipant: ((CallId) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val palette = if (composedVideo) RosterPalette.overVideo() else RosterPalette.plain()
    val open = expanded && state.hasList

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppTheme.radius.large))
            .background(palette.container)
            .testTag(TAG_ROSTER),
    ) {
        RosterHeader(
            state = state,
            expanded = open,
            palette = palette,
            onToggle = { expanded = !expanded }.takeIf { state.hasList },
        )

        // Weighted so the list is what yields when the screen is short: the header always
        // fits, and the rows scroll inside whatever is left above the control grid.
        AnimatedVisibility(
            visible = open,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            ParticipantList(
                state = state,
                composedVideo = composedVideo,
                palette = palette,
                onRemoveParticipant = onRemoveParticipant,
            )
        }
    }
}

/**
 * The always-visible line: a group badge, "Participants", the count, and the chevron.
 *
 * One tap target rather than a chevron button beside static text: the whole header is the
 * affordance, and a 24 dp icon at the end of a 300 dp row is a small thing to find under a
 * thumb. Announced as a button whose state is "expanded" or "collapsed", so a screen
 * reader hears what a tap will do rather than a count beside a mystery icon.
 */
@Composable
private fun RosterHeader(
    state: ConferenceUiState,
    expanded: Boolean,
    palette: RosterPalette,
    onToggle: (() -> Unit)?,
) {
    val chevron by animateFloatAsState(targetValue = if (expanded) CHEVRON_OPEN else 0f, label = "chevron")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(toggleSemantics(expanded, onToggle))
            .padding(horizontal = AppTheme.spacing.medium, vertical = AppTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.medium),
    ) {
        GroupBadge(palette)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Participants",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = palette.primary,
            )
            Text(
                text = state.summary(),
                style = MaterialTheme.typography.bodySmall,
                color = palette.secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(TAG_ROSTER_SUMMARY),
            )
        }

        if (onToggle != null) {
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = palette.secondary,
                modifier = Modifier.rotate(chevron),
            )
        }
    }
}

/** The header as a button, when there is a list to show; plain otherwise. */
private fun toggleSemantics(expanded: Boolean, onToggle: (() -> Unit)?): Modifier {
    if (onToggle == null) return Modifier
    return Modifier
        .clickable(onClick = onToggle, role = Role.Button)
        .semantics {
            contentDescription = if (expanded) "Hide participants" else "Show participants"
            stateDescription = if (expanded) "Expanded" else "Collapsed"
        }
        .testTag(TAG_ROSTER_TOGGLE)
}

/** The group glyph in a tinted circle, the size of a row's avatar so the header lines up with the rows. */
@Composable
private fun GroupBadge(palette: RosterPalette) {
    Box(
        modifier = Modifier
            .size(AppTheme.sizing.avatarSmall)
            .clip(CircleShape)
            .background(palette.badge),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Groups,
            contentDescription = null,
            tint = palette.onBadge,
            modifier = Modifier.size(AppTheme.sizing.listTrailingIcon),
        )
    }
}

@Composable
private fun ParticipantList(
    state: ConferenceUiState,
    composedVideo: Boolean,
    palette: RosterPalette,
    onRemoveParticipant: ((CallId) -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .testTag(TAG_ROSTER_LIST),
    ) {
        HorizontalDivider(color = palette.divider)

        state.participants.forEachIndexed { index, participant ->
            ParticipantRow(
                participant = participant,
                palette = palette,
                // Only a member with a leg of their own, and only on the focus. The local
                // user's row has no call to end, and ending the conference is the big red
                // button's job rather than a small one beside your own name.
                onRemove = onRemoveParticipant
                    ?.takeIf { state.canRemoveParticipants && !participant.isSelf }
                    ?.let { remove -> participant.callId?.let { leg -> { remove(leg) } } },
            )
            if (index < state.participants.lastIndex) {
                HorizontalDivider(
                    color = palette.divider,
                    modifier = Modifier.padding(start = AppTheme.sizing.listDividerInset),
                )
            }
        }

        if (state.fromMerge) {
            Text(
                text = "Members as merged from this phone. The bridge does not publish who is in the room.",
                style = MaterialTheme.typography.bodySmall,
                color = palette.secondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppTheme.spacing.medium, vertical = AppTheme.spacing.small)
                    .testTag(TAG_MERGED_NOTE),
            )
        }

        if (composedVideo) {
            Text(
                text = "The conference server composes this picture, so who is on screen " +
                    "and how they are arranged is decided there.",
                style = MaterialTheme.typography.bodySmall,
                color = palette.secondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppTheme.spacing.medium, vertical = AppTheme.spacing.small)
                    .testTag(TAG_MIXED_STREAM_NOTE),
            )
        }
    }
}

/**
 * One member: their face or initials, their name over their extension, and whatever is
 * true of them right now — that they are you, that they are held, that they are speaking,
 * that their microphone is off.
 *
 * The name carries the weight and the extension sits under it in the quieter colour,
 * because "who" is read first and "which extension" is read when somebody has to be
 * dialled again. The state lives at the end of the row, where a glance down the list
 * finds every member who is not simply present.
 */
@Composable
private fun ParticipantRow(
    participant: ConferenceParticipantRow,
    palette: RosterPalette,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.spacing.medium, vertical = AppTheme.spacing.small)
            .testTag(participantRowTag(participant.id)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.medium),
    ) {
        Avatar(displayName = participant.label, photoUri = participant.photoUri)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = participant.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = palette.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            participant.detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (participant.isSelf) {
            Tag(text = "You", palette = palette)
        }
        participant.status?.let {
            StatusLabel(tone = it.tone(), text = it.label, color = palette.secondary)
        }
        if (participant.isSpeaking) {
            Icon(
                imageVector = Icons.Filled.GraphicEq,
                contentDescription = "Speaking",
                tint = palette.accent,
                modifier = Modifier.size(AppTheme.sizing.listTrailingIcon),
            )
        }
        if (participant.isMuted) {
            Icon(
                imageVector = Icons.Filled.MicOff,
                // Whose mute this is depends on whose row it is: the user's row reports
                // this device's own microphone, and everyone else's reports what the
                // bridge said. Saying "muted by the bridge" over your own row would name
                // the wrong culprit for the one case the user can actually fix.
                contentDescription = if (participant.isSelf) "Your microphone is off" else "Muted by the bridge",
                tint = palette.secondary,
                modifier = Modifier.size(AppTheme.sizing.listTrailingIcon),
            )
        }
        onRemove?.let { remove ->
            // Red, and the handset-down glyph, because it does to one person exactly what
            // the big red button does to everybody — and a control that ends somebody's
            // call should look like one wherever it appears. Named with the member, so a
            // screen reader announces which of six people is about to be dropped rather
            // than six identical "End call" buttons.
            IconButton(
                onClick = remove,
                modifier = Modifier.testTag(removeParticipantTag(participant.id)),
            ) {
                Icon(
                    imageVector = Icons.Filled.CallEnd,
                    contentDescription = "Remove ${participant.label} from the conference",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(AppTheme.sizing.listTrailingIcon),
                )
            }
        }
    }
}

/** A small rounded label — "You" — in the card's accent, so it reads as a tag and not as text. */
@Composable
private fun Tag(text: String, palette: RosterPalette) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = palette.onBadge,
        modifier = Modifier
            .clip(RoundedCornerShape(AppTheme.radius.full))
            .background(palette.badge)
            .padding(horizontal = AppTheme.spacing.small, vertical = AppTheme.spacing.extraSmall),
    )
}

/**
 * What the header says under "Participants", given what is actually known.
 *
 * A count only when there is a roster to count. "This bridge does not publish a
 * participant list" is the honest line for a server that does not say — better than
 * "0 participants", which is a claim, and better than silence, which hides that this is a
 * conference. The members this device merged are named rather than counted, for the
 * reason the class comment gives.
 */
private fun ConferenceUiState.summary(): String = when {
    fromMerge -> participants.map { if (it.isSelf) "You" else it.label }.spokenList()
    !rosterAvailable -> "This bridge does not publish a participant list"
    participants.size <= 1 -> "Just you so far"
    else -> "${participants.size} in this call"
}

/** "You", "You and 1004", "You, 1004 and 1005". */
private fun List<String>.spokenList(): String = when (size) {
    0 -> ""
    1 -> single()
    else -> dropLast(1).joinToString() + " and " + last()
}

/** The dot beside a member's state: held is deliberately off; the rest are on their way. */
private fun ParticipantStatus.tone(): StatusTone = when (this) {
    ParticipantStatus.ON_HOLD -> StatusTone.OFFLINE
    ParticipantStatus.REJOINING, ParticipantStatus.CONNECTING -> StatusTone.CONNECTING
}

/**
 * The card's colours, in one place, so the plain and over-video variants cannot drift.
 *
 * Over video everything is white on translucent black; on a plain surface it is the
 * theme's container roles, so the card sits on the call screen like any other Material
 * surface rather than as a thing painted on it.
 */
private data class RosterPalette(
    val container: Color,
    val primary: Color,
    val secondary: Color,
    val divider: Color,
    val badge: Color,
    val onBadge: Color,
    val accent: Color,
) {
    companion object {
        @Composable
        fun plain() = RosterPalette(
            container = MaterialTheme.colorScheme.surfaceContainer,
            primary = MaterialTheme.colorScheme.onSurface,
            secondary = MaterialTheme.colorScheme.onSurfaceVariant,
            divider = MaterialTheme.colorScheme.outlineVariant,
            badge = MaterialTheme.colorScheme.primaryContainer,
            onBadge = MaterialTheme.colorScheme.onPrimaryContainer,
            accent = MaterialTheme.colorScheme.primary,
        )

        @Composable
        fun overVideo() = RosterPalette(
            container = Color.Black.copy(alpha = SCRIM),
            primary = Color.White,
            secondary = Color.White.copy(alpha = SCRIM_TEXT),
            divider = Color.White.copy(alpha = SCRIM_LINE),
            badge = Color.White.copy(alpha = SCRIM_BADGE),
            onBadge = Color.White,
            accent = Color.White,
        )
    }
}

/** Dark enough for white text over any frame; light enough to see the call through. */
private const val SCRIM = 0.55f

/** Secondary text, dimmed the way `onSurfaceVariant` is against `onSurface`. */
private const val SCRIM_TEXT = 0.75f

/** A divider that separates without drawing a line across somebody's face. */
private const val SCRIM_LINE = 0.15f

/** The badge behind the group glyph and the "You" tag, over video. */
private const val SCRIM_BADGE = 0.2f

/** The chevron points down when closed and up when open. */
private const val CHEVRON_OPEN = 180f

internal const val TAG_ROSTER = "conference-roster"
internal const val TAG_ROSTER_TOGGLE = "conference-roster-toggle"
internal const val TAG_ROSTER_SUMMARY = "conference-roster-summary"
internal const val TAG_ROSTER_LIST = "conference-roster-list"
internal const val TAG_MIXED_STREAM_NOTE = "conference-mixed-stream-note"
internal const val TAG_MERGED_NOTE = "conference-merged-note"

/** The test tag of one member's row, by the row's id. */
internal fun participantRowTag(id: String): String = "conference-participant-$id"

/** The test tag of one member's End button, by the row's id. */
internal fun removeParticipantTag(id: String): String = "conference-remove-$id"
