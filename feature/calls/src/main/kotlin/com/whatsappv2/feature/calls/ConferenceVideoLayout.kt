package com.whatsappv2.feature.calls

import com.whatsappv2.domain.engine.SipConferenceController

/**
 * How a conference's video should be arranged (Task 61, §2.2).
 *
 * Sealed, because the four cases are genuinely different pictures rather than
 * parameterisations of one — and because the first of them is the one this app actually
 * ships today and the honest thing is for it to be visible in the type.
 */
sealed interface ConferenceVideoMode {

    /** The conference has no video at all. The roster is the whole screen. */
    data object AudioOnly : ConferenceVideoMode

    /**
     * One composed picture carrying everybody — a mixing MCU (ADR-003).
     *
     * **This is what the deployed FreeSWITCH bridge sends**, and it is a limitation worth
     * stating rather than papering over: the *server* decides who is on screen and how
     * they are arranged, and this app can neither change that layout nor pick out one
     * participant. A client-side grid drawn over a mixed stream would be a grid of
     * identical copies of the same picture.
     */
    data object MixedStream : ConferenceVideoMode

    /**
     * One tile per participant — an SFU sending separate streams (§2.2 option b).
     *
     * Not reachable with the current bridge. It is here because §2.2 asks the model to
     * survive the swap, and a layout that only knows how to draw one rectangle is a layout
     * that has to be rewritten for it.
     */
    data class Grid(val columns: Int, val rows: Int) : ConferenceVideoMode {
        /** How many tiles the grid holds. May exceed the participant count on the last row. */
        val capacity: Int get() = columns * rows
    }

    /**
     * The speaker large, everybody else as thumbnails — an SFU with too many participants
     * for a readable grid.
     *
     * A grid of sixteen faces on a phone is sixteen faces nobody can recognise; past a
     * point the useful question is "who is talking", which is what this answers.
     */
    data class ActiveSpeaker(val thumbnails: Int) : ConferenceVideoMode
}

/**
 * Choosing the arrangement (Task 61, DoD 11).
 *
 * Pure integer arithmetic, so "layout adapts to participant count and to rotation" is a
 * property a JVM test can enumerate rather than something to eyeball on a handset at four
 * different participant counts in two orientations.
 *
 * ## The mixed-stream case is not a degenerate grid
 *
 * It is tempting to treat one composed stream as a one-by-one grid and be done. That is
 * wrong in a way that matters: a grid implies this app chose the arrangement, and under an
 * MCU the *bridge* did. Keeping [ConferenceVideoMode.MixedStream] separate is what lets
 * the screen say so — Task 61's third done-when — instead of quietly presenting the
 * server's choice as its own.
 */
object ConferenceVideoLayout {

    /**
     * The mode for a conference of [participantCount] people.
     *
     * @param hasVideo whether video is negotiated on the leg at all.
     * @param perParticipantVideo whether the bridge sends a stream per participant (an
     *   SFU). False under the dial-in MCU, which is today's transport.
     * @param isLandscape which way the device is held. It changes the grid's proportions,
     *   not the number of tiles: rotating must not make somebody disappear.
     */
    fun of(
        participantCount: Int,
        hasVideo: Boolean,
        perParticipantVideo: Boolean,
        isLandscape: Boolean = false,
    ): ConferenceVideoMode = when {
        !hasVideo -> ConferenceVideoMode.AudioOnly
        !perParticipantVideo -> ConferenceVideoMode.MixedStream
        participantCount <= 0 -> ConferenceVideoMode.AudioOnly
        participantCount > GRID_LIMIT ->
            ConferenceVideoMode.ActiveSpeaker(thumbnails = ACTIVE_SPEAKER_THUMBNAILS)
        else -> gridFor(participantCount, isLandscape)
    }

    /**
     * The arrangement for [count] **remote** tiles, to the agreed reference.
     *
     * | tiles | portrait          | what it looks like                         |
     * |-------|-------------------|--------------------------------------------|
     * | 1     | 1 x 1             | full screen                                 |
     * | 2     | 1 col x 2 rows    | stacked, each full width, each half the area|
     * | 3     | 2 cols x 2 rows   | two across, then one full width             |
     * | 4     | 2 cols x 2 rows   | 2x2, each tile a quarter of the area        |
     *
     * ## Why two is a column and three is a grid
     *
     * It is not the tightest packing, and that is deliberate. Two people side by side on a
     * 9:20 screen are two narrow slivers; stacked, each gets the full width and half the
     * height, which is the larger face. From three on there is no arrangement that keeps
     * the full width, so the grid starts — and once it starts, the last row of an odd
     * count spreads across rather than leaving a hole beside it.
     *
     * It used to compute `columns = floor(sqrt(count))`, which made three tiles **one
     * column, three rows**: three full-width letterboxes stacked down the screen. Four came
     * out 2x2 by arithmetic accident, so the defect only showed at the sizes nobody had
     * screenshotted.
     *
     * Landscape is the same table transposed: the run that was down the screen goes across
     * it, so two people sit side by side and four are still 2x2.
     *
     * @param count how many **remote** tiles there are. Not the participant count: this
     *   device is a draggable self-view, never a tile, so a conference of four draws
     *   three. Passing the participant count is the off-by-one that made a two-person
     *   conference use the three-tile arrangement.
     */
    private fun gridFor(count: Int, isLandscape: Boolean): ConferenceVideoMode.Grid =
        if (isLandscape) {
            val rows = if (count <= FULL_WIDTH_LIMIT) 1 else COLUMNS
            ConferenceVideoMode.Grid(columns = ceilDiv(count, rows), rows = rows)
        } else {
            val columns = if (count <= FULL_WIDTH_LIMIT) 1 else COLUMNS
            ConferenceVideoMode.Grid(columns = columns, rows = ceilDiv(count, columns))
        }

    private fun ceilDiv(value: Int, by: Int): Int = (value + by - 1) / by

    /**
     * Up to this many tiles keep the full width of the screen and stack instead.
     *
     * Two. See the table above: below three tiles, stacking gives a bigger picture than
     * splitting the width does.
     */
    const val FULL_WIDTH_LIMIT = 2

    /**
     * Tiles across the short edge once the grid starts.
     *
     * Two, which with [SipConferenceController.MAX_VIDEO_CONFERENCE]'s four participants —
     * three remote tiles plus this device's own preview — makes every tile a quarter of
     * the conference area at the ceiling.
     */
    const val COLUMNS = 2

    /**
     * Above this many participants a grid stops being readable on a phone.
     *
     * Nine, because that is 3×3 — the last arrangement where a face on a 400dp-wide
     * screen is still about the size of a thumbnail photo.
     */
    const val GRID_LIMIT = 9

    /** How many thumbnails accompany the active speaker. One row's worth. */
    const val ACTIVE_SPEAKER_THUMBNAILS = 4
}
