package com.whatsappv2.feature.calls

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
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
 * One participant: their picture, their name and extension, and whether they are muted.
 *
 * The picture is clipped to the tile and fills it. PJSIP stretches whatever it decodes to
 * the bounds of the view it is given (`opengl_dev.c` sets the viewport to the whole
 * surface and corrects nothing), so a tile is only the right shape if it is *given* the
 * right shape — here that is [frame]'s aspect ratio scaled to cover the cell, with the
 * overflow clipped by the tile's rounded corners. A `TextureView` is what makes that clip
 * real; see the grid's KDoc for the SurfaceView that ignored it.
 */
@Composable
private fun ParticipantTile(
    participant: ConferenceParticipantRow,
    view: TextureView,
    frame: VideoSize,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(AppTheme.radius.medium))
            .background(Color.Black)
            .testTag("$TAG_TILE_PREFIX${participant.id}"),
        contentAlignment = Alignment.Center,
    ) {
        // The surface is given the *frame's* shape, scaled to cover the tile, and the
        // overflow is clipped by the tile's own rounded corners. PJSIP draws onto a
        // full-screen quad with fixed texture coordinates and corrects no aspect ratio
        // whatever: a 16:9 frame in a portrait cell is a face half again as tall as it
        // should be, which is what these tiles were until now. `cover` rather than `fit`
        // so a tile is filled edge to edge rather than letterboxed inside an already
        // small cell.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val available = VideoSize(
                width = constraints.maxWidth.takeIf { it != Constraints.Infinity } ?: 0,
                height = constraints.maxHeight.takeIf { it != Constraints.Infinity } ?: 0,
            )
            val box = VideoLayout.cover(frame, available)
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

        if (participant.isMuted) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(AppTheme.spacing.small)
                    .clip(RoundedCornerShape(AppTheme.radius.full))
                    .background(Color.Black.copy(alpha = CHIP_SCRIM))
                    .padding(AppTheme.spacing.extraSmall)
                    .testTag("$TAG_MUTE_PREFIX${participant.id}"),
            ) {
                Icon(
                    imageVector = Icons.Filled.MicOff,
                    contentDescription = "${participant.label} is muted",
                    tint = Color.White,
                    modifier = Modifier.size(AppTheme.sizing.videoPreviewControlMinimised),
                )
            }
        }
    }
}

/**
 * The extension over the name, as the reference has it.
 *
 * Two lines rather than one string because they answer different questions and deserve
 * different weight: the extension is how somebody is addressed on this system and the
 * name is who they are. [ConferenceParticipantRow.detail] is null when the two would say
 * the same thing, and then the label is the only line — a chip reading "1003" over "1003"
 * is noise.
 */
@Composable
private fun NameChip(participant: ConferenceParticipantRow, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AppTheme.radius.small))
            .background(Color.Black.copy(alpha = CHIP_SCRIM))
            .padding(horizontal = AppTheme.spacing.small, vertical = AppTheme.spacing.extraSmall),
    ) {
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

/** Dark enough to read white text over any frame, light enough to see the picture through. */
private const val CHIP_SCRIM = 0.55f

internal const val TAG_CONFERENCE_GRID = "conference-grid"
internal const val TAG_TILE_PREFIX = "conference-tile-"
internal const val TAG_MUTE_PREFIX = "conference-mute-"
