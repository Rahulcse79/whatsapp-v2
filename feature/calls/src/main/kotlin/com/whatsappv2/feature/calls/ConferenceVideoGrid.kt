package com.whatsappv2.feature.calls

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Shape
import com.whatsappv2.core.designsystem.component.Avatar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import com.whatsappv2.core.designsystem.theme.AppTheme
import com.whatsappv2.domain.engine.VideoSize
import com.whatsappv2.domain.engine.VideoSizes

/**
 * One tile per participant, arranged by this app (Task 61, §2.2 option b).
 *
 * ## Why this can exist now
 *
 * It could not before. Every peer's decoder used to be composed into a single window by
 * `pjmedia`'s video bridge, so the screen received one picture and the arrangement inside
 * it was the mixer's: there was nothing to label, nothing to badge, and nothing to resize
 * when somebody left. `VideoMix` no longer builds that local canvas — each call renders
 * into a window of its own — so the arrangement is the screen's, which is what this is.
 *
 * The canvas each *peer* receives is still composed by the mixer, and deliberately so:
 * that is the half a remote handset cannot do for itself.
 *
 * ## The grid is measured, not assumed
 *
 * Tiles take an equal share of the space this composable is given, through `weight`, so
 * the same code is a full-bleed single tile, a two-up split, or a 2x2 — and a participant
 * joining or leaving is a re-measure rather than a different layout. That is also what
 * keeps it honest in landscape and in split-screen, where reading the display's size would
 * describe a rectangle this composable does not have.
 *
 * ## The tiles are `TextureView`s, and they have to be
 *
 * A `SurfaceView` is not drawn by the view hierarchy — it is its own window, composited by
 * SurfaceFlinger — so it **ignores every clip its parents set**. A tile sizes its picture
 * to *cover* its cell, which means the picture is deliberately larger than the cell and
 * the overflow is supposed to disappear at the tile's rounded corners. With SurfaceViews
 * it did not disappear: on a four-party call (2026-09-24) one participant's picture ran
 * out of its cell and straight across the neighbouring tile.
 *
 * `TextureView` is an ordinary view in the hierarchy, so `clip` applies to it. It costs a
 * GPU composite per tile, which at four tiles is a trade worth making for a grid whose
 * cells are actually cells.
 *
 * ## Every tile is sized by its own stream
 *
 * [VideoSizes.remoteFor], not one shared remote size. The handsets do not agree on a frame
 * shape — a TC15's camera, an M14's and a re-negotiated peer are three rectangles — and
 * laying every tile out on whichever of them decoded last is what made one tile a narrow
 * strip in a field of black while another overflowed.
 *
 * ## The self-view is not one of these tiles
 *
 * pjsua keeps **one** preview window per capture device, so a second surface showing this
 * camera would contend with the floating self-view for it. The self-view stays where it
 * is — its own movable box, drawn by [SelfPreview] — and the grid is the people on the
 * other end.
 */
@Composable
internal fun ConferenceVideoGrid(
    participants: List<ConferenceParticipantRow>,
    columns: Int,
    sizes: VideoSizes,
    onSurfaces: (Map<String, Any?>) -> Unit,
    modifier: Modifier = Modifier,
    onTileHeight: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val ids = participants.map { it.id }

    // One view per participant, kept across recompositions so a re-measure does not tear
    // the renderer's surface down and build it again — which reads as a black flash.
    val views = remember { mutableMapOf<String, TextureView>() }
    ids.forEach { id ->
        views.getOrPut(id) { TextureView(context).apply { keepScreenOn = true } }
    }
    // A participant who left takes their view with them, or the map grows for the life of
    // the call and holds surfaces the stack may still be handed.
    remember(ids) { views.keys.retainAll(ids.toSet()); ids }

    DisposableEffect(ids) {
        val live = mutableMapOf<String, Any?>()
        val held = mutableMapOf<String, Surface>()
        fun publish() = onSurfaces(live.toMap())

        fun attach(id: String, texture: SurfaceTexture) {
            held.remove(id)?.release()
            val surface = Surface(texture)
            held[id] = surface
            live[id] = surface
            publish()
        }

        ids.forEach { id ->
            val view = views.getValue(id)
            view.surfaceTextureListener = surfaceTextureListener(
                onAvailable = { texture -> attach(id, texture) },
                onDestroyed = {
                    live -= id
                    publish()
                    held.remove(id)?.release()
                },
            )
            // A texture that already existed is not announced again — the view outlives
            // this effect, so a re-measure would otherwise leave its tile black.
            view.surfaceTexture?.let { attach(id, it) }
        }
        publish()

        onDispose {
            ids.forEach { views[it]?.surfaceTextureListener = null }
            onSurfaces(emptyMap())
            held.values.forEach { it.release() }
            held.clear()
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().testTag(TAG_CONFERENCE_GRID)) {
        val rows = participants.chunked(columns.coerceAtLeast(1))

        // How tall one tile is, for the adaptive-quality display ceiling. Taken from the
        // constraints rather than measured per tile: the rows share the height evenly by
        // `weight(1f)`, so this is the same number the layout will arrive at, and it is
        // known before the tiles draw rather than one frame after. Reported only when it
        // actually changes -- a rotation or somebody joining -- because the receiver of
        // this is the media stack, not the composition.
        val tileHeightPx = if (rows.isEmpty()) 0 else constraints.maxHeight / rows.size
        LaunchedEffect(tileHeightPx) { onTileHeight(tileHeightPx) }
        Column(
            modifier = Modifier.fillMaxSize().padding(AppTheme.spacing.small),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.small),
        ) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.small),
                ) {
                    row.forEach { participant ->
                        ParticipantTile(
                            participant = participant,
                            view = views.getValue(participant.id),
                            // This participant's own decoded shape, never the conference's
                            // most recent one. See the class KDoc.
                            frame = sizes.remoteFor(participant.id),
                            // The last row of an odd count spreads across the width rather
                            // than leaving a hole beside it, which is the difference
                            // between a grid and a grid with a gap in it.
                            modifier = Modifier.weight(1f).fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * One participant: a card holding their picture, their name, and whether they are muted.
 *
 * ## Why it is a card and not a rectangle of video
 *
 * A conference tile is dark for most of its area — the picture is letterboxed to the
 * frame's own shape (see below), and the rest is the canvas showing through. Without a
 * surface and an edge, four of those on a black screen read as four holes rather than four
 * people: that is what the grid looked like before. So the tile paints a barely-there
 * surface, rounds itself at [Radius.large], and draws a hairline at its edge. None of it is
 * decoration for its own sake — each piece is what makes a tile's bounds visible when its
 * picture does not reach them.
 *
 * ## The border carries state
 *
 * [AppTheme.sizing.videoTileBorder] at rest, and the theme's primary at
 * [AppTheme.sizing.videoPreviewBorder] while this participant is speaking. It is animated
 * because an active speaker changes often enough that a hard switch flickers, and it is the
 * border rather than a badge because the border is already there — a badge would be one
 * more thing competing with the name for a corner.
 *
 * `isSpeaking` is plumbed from the roster and is false on a server that asserts no active
 * speaker, which is the common case here. That is the right behaviour rather than dead
 * code: the ring simply does not appear until something sets it.
 *
 * ## The picture
 *
 * The surface is given the *frame's own* shape, scaled uniformly to fit inside the tile.
 * PJSIP draws onto a full-screen quad with fixed texture coordinates and corrects no aspect
 * ratio whatever, so the shape of the view *is* the shape of the picture: a 16:9 frame in a
 * portrait cell is a face half again as tall as it should be unless the view is 16:9 too.
 *
 * `fit` rather than `cover`. Cover fills the cell edge to edge and clips the overflow, which
 * loses the sides of a 16:9 stream in a portrait tile — and in a conference every pixel is
 * somebody. Contain keeps the whole frame and letterboxes the remainder, which is the
 * requirement: preserve the entire original frame, never crop it merely to fill the cell.
 *
 * Neither distorts; the difference is what is lost. What *would* distort is giving the view
 * the tile's shape instead of the frame's, and that is what happens when the frame is
 * unknown — see the 16:9 fallback.
 */
@Composable
private fun ParticipantTile(
    participant: ConferenceParticipantRow,
    view: TextureView,
    frame: VideoSize,
    modifier: Modifier = Modifier,
) {
    val shape: Shape = RoundedCornerShape(AppTheme.radius.large)
    val speakingColour = MaterialTheme.colorScheme.primary
    val edge by animateColorAsState(
        targetValue = if (participant.isSpeaking) {
            speakingColour
        } else {
            Color.White.copy(alpha = TILE_EDGE_ALPHA)
        },
        label = "conference-tile-edge",
    )
    val edgeWidth =
        if (participant.isSpeaking) AppTheme.sizing.videoPreviewBorder else AppTheme.sizing.videoTileBorder

    Box(
        modifier = modifier
            .clip(shape)
            .background(Color.Black)
            .background(Color.White.copy(alpha = TILE_SURFACE_ALPHA))
            .border(BorderStroke(edgeWidth, edge), shape)
            .testTag("$TAG_TILE_PREFIX${participant.id}"),
        contentAlignment = Alignment.Center,
    ) {
        // Behind the picture, and only until there is one. A `TextureView` draws nothing
        // before its first frame, so this shows through the whole tile while a stream is
        // still coming up and is covered the moment it is not -- which is the difference
        // between "connecting" and "this call is broken" for anybody looking at the screen.
        if (!frame.isKnown) {
            TilePlaceholder(participant)
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val available = VideoSize(
                width = constraints.maxWidth.takeIf { it != Constraints.Infinity } ?: 0,
                height = constraints.maxHeight.takeIf { it != Constraints.Infinity } ?: 0,
            )
            // A tile whose stream has not yet reported a shape is letterboxed to 16:9 rather
            // than filled. Filling is what `videoBounds` does with an unknown box, and it
            // hands the renderer the cell's aspect ratio -- a stretch, for as long as it
            // takes the first frame to arrive. Every rung of every ladder this app offers is
            // 16:9 (`VideoQualityProfiles`), so the guess is right for our own peers and
            // wrong by less than a crop for anybody else.
            val pictureShape = if (frame.isKnown) frame else NEUTRAL_FRAME
            val box = VideoLayout.fit(pictureShape, available)
            AndroidView(
                factory = {
                    // The view outlives this composable; if a re-measure runs before the
                    // previous holder has let go, Compose's addView would meet a view
                    // that still has a parent. The self-view had exactly that crash.
                    view.also { (it.parent as? android.view.ViewGroup)?.removeView(it) }
                },
                modifier = Modifier.videoBounds(box, available, LocalDensity.current),
            )
        }

        NameChip(
            participant = participant,
            modifier = Modifier.align(Alignment.BottomStart).padding(AppTheme.spacing.small),
        )
    }
}

/**
 * What a tile shows before its stream has a picture: who you are waiting for.
 *
 * An initial circle and the name, centred — the same thing every other video product does
 * with a camera that has not arrived, and for the same reason. The alternative is a black
 * rectangle, which is indistinguishable from the failure modes this app has actually had.
 */
@Composable
private fun TilePlaceholder(participant: ConferenceParticipantRow) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.small),
    ) {
        Avatar(displayName = participant.label, size = AppTheme.sizing.videoTileAvatar)
        Text(
            text = participant.label,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = PLACEHOLDER_LABEL_ALPHA),
            maxLines = 1,
        )
    }
}

/**
 * The name plate: a pill carrying the mute state, the extension and the name.
 *
 * One pill rather than a label in one corner and a mute badge in the other. A four-party
 * grid has four tiles and eight corners in play, and the two marks belong to the same
 * person — putting them together is what stops a tile looking like a dashboard.
 *
 * Two text lines rather than one string, because they answer different questions and
 * deserve different weight: the extension is how somebody is addressed on this system and
 * the name is who they are. [ConferenceParticipantRow.detail] is null when the two would
 * say the same thing, and then the label is the only line — a chip reading "1003" over
 * "1003" is noise.
 */
@Composable
private fun NameChip(participant: ConferenceParticipantRow, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(AppTheme.radius.full))
            .background(Color.Black.copy(alpha = CHIP_SCRIM))
            .padding(horizontal = AppTheme.spacing.small, vertical = AppTheme.spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.extraSmall),
    ) {
        if (participant.isMuted) {
            Icon(
                imageVector = Icons.Filled.MicOff,
                contentDescription = "${participant.label} is muted",
                tint = Color.White,
                modifier = Modifier
                    .size(AppTheme.sizing.videoPreviewControl)
                    .testTag("$TAG_MUTE_PREFIX${participant.id}"),
            )
        }
        Column {
            val extension = participant.detail ?: participant.label
            Text(
                text = extension,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
            )
            if (participant.detail != null) {
                Text(
                    text = participant.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Dark enough to read white text over any frame, light enough to see the picture through. */
private const val CHIP_SCRIM = 0.55f

/**
 * The tile's own surface, over the black canvas.
 *
 * Barely there on purpose: enough that the letterboxed bands read as part of a card rather
 * than as the screen behind it, and not so much that a dark picture looks washed out.
 */
private const val TILE_SURFACE_ALPHA = 0.06f

/** The resting hairline. Visible against black, invisible against a bright picture. */
private const val TILE_EDGE_ALPHA = 0.14f

/** The waiting name, quieter than the name plate because it is a placeholder. */
private const val PLACEHOLDER_LABEL_ALPHA = 0.7f

/**
 * The shape a tile assumes before its own stream has reported one.
 *
 * 16:9, because every resolution this app negotiates is — see `VideoQualityProfiles`. It is
 * used only to letterbox, never to scale: an unknown frame must not be given the tile's own
 * proportions, which is how a picture gets stretched before anybody has seen it.
 */
private val NEUTRAL_FRAME = VideoSize(16, 9)

internal const val TAG_CONFERENCE_GRID = "conference-grid"
internal const val TAG_TILE_PREFIX = "conference-tile-"
internal const val TAG_MUTE_PREFIX = "conference-mute-"
