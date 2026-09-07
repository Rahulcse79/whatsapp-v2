package com.whatsappv2.feature.calls

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
     * The tightest grid that holds [count] tiles, in the shape the screen is.
     *
     * The shorter run is the square root rounded **down** and the longer one follows from
     * the count, which is what makes two people one-wide-and-two-tall in portrait rather
     * than side by side. Rounding up instead gives the short run the larger number — two
     * people come out 2×1 — and the names stop describing the values.
     *
     * In landscape the two are swapped, so four people are 2×2 either way but three are
     * 1 wide × 3 tall in portrait and 3 wide × 1 tall in landscape — the difference
     * between three readable faces and three letterboxes.
     */
    private fun gridFor(count: Int, isLandscape: Boolean): ConferenceVideoMode.Grid {
        val short = floorSqrt(count)
        val long = ceilDiv(count, short)

        // The longer run goes across the longer edge of the screen.
        return if (isLandscape) {
            ConferenceVideoMode.Grid(columns = long, rows = short)
        } else {
            ConferenceVideoMode.Grid(columns = short, rows = long)
        }
    }

    /** The largest `n` with `n * n <= value`, without floating point. */
    private fun floorSqrt(value: Int): Int {
        var n = 1
        while ((n + 1) * (n + 1) <= value) n++
        return n
    }

    private fun ceilDiv(value: Int, by: Int): Int = (value + by - 1) / by

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
