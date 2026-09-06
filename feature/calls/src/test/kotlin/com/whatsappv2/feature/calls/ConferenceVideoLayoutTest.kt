package com.whatsappv2.feature.calls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Task 61's second done-when: the layout adapts to participant count and to rotation.
 *
 * Enumerated here rather than eyeballed on a handset, because "adapts" is a claim about
 * every count in both orientations and a device shows you one of them at a time.
 */
class ConferenceVideoLayoutTest {

    private fun mode(
        count: Int,
        hasVideo: Boolean = true,
        perParticipant: Boolean = true,
        landscape: Boolean = false,
    ) = ConferenceVideoLayout.of(count, hasVideo, perParticipant, landscape)

    @Test
    fun `a conference with no video has no video layout`() {
        assertIs<ConferenceVideoMode.AudioOnly>(mode(count = 4, hasVideo = false))
    }

    @Test
    fun `a mixing bridge is one composed picture, not a one-by-one grid`() {
        // The distinction Task 61's third done-when turns on: a grid implies this app
        // chose the arrangement, and under an MCU the bridge did.
        assertIs<ConferenceVideoMode.MixedStream>(mode(count = 5, perParticipant = false))
    }

    @Test
    fun `a mixing bridge stays one picture however many people are in the room`() {
        listOf(1, 3, 9, 40).forEach { count ->
            assertIs<ConferenceVideoMode.MixedStream>(
                mode(count = count, perParticipant = false),
                "expected one composed stream for $count participants",
            )
        }
    }

    @Test
    fun `one participant is a single tile`() {
        assertEquals(ConferenceVideoMode.Grid(columns = 1, rows = 1), mode(1))
    }

    @Test
    fun `two people stack in portrait and sit side by side in landscape`() {
        assertEquals(ConferenceVideoMode.Grid(columns = 1, rows = 2), mode(2))
        assertEquals(ConferenceVideoMode.Grid(columns = 2, rows = 1), mode(2, landscape = true))
    }

    @Test
    fun `four people are two by two whichever way the phone is held`() {
        assertEquals(ConferenceVideoMode.Grid(columns = 2, rows = 2), mode(4))
        assertEquals(ConferenceVideoMode.Grid(columns = 2, rows = 2), mode(4, landscape = true))
    }

    @Test
    fun `rotation changes the shape but never the number of tiles`() {
        // The bug this prevents: a rotation that drops somebody off the screen.
        (1..ConferenceVideoLayout.GRID_LIMIT).forEach { count ->
            val portrait = assertIs<ConferenceVideoMode.Grid>(mode(count))
            val landscape = assertIs<ConferenceVideoMode.Grid>(mode(count, landscape = true))

            assertTrue(portrait.capacity >= count, "portrait grid too small for $count")
            assertTrue(landscape.capacity >= count, "landscape grid too small for $count")
            assertEquals(portrait.capacity, landscape.capacity, "rotation changed the tile count")
        }
    }

    @Test
    fun `every grid is the tightest one that holds everybody`() {
        (1..ConferenceVideoLayout.GRID_LIMIT).forEach { count ->
            val grid = assertIs<ConferenceVideoMode.Grid>(mode(count))

            // Tight: removing a row would not fit, so no whole row is empty.
            assertTrue(
                grid.capacity - count < maxOf(grid.columns, grid.rows),
                "a whole row or column is empty for $count participants: $grid",
            )
        }
    }

    @Test
    fun `past the grid limit the speaker goes large with thumbnails`() {
        // Sixteen faces on a phone is sixteen faces nobody can recognise; past a point
        // the useful question is who is talking.
        val speaker = assertIs<ConferenceVideoMode.ActiveSpeaker>(mode(ConferenceVideoLayout.GRID_LIMIT + 1))

        assertEquals(ConferenceVideoLayout.ACTIVE_SPEAKER_THUMBNAILS, speaker.thumbnails)
    }

    @Test
    fun `nine is still a grid, because three by three is the last readable one`() {
        assertEquals(ConferenceVideoMode.Grid(columns = 3, rows = 3), mode(9))
    }

    @Test
    fun `an empty room with video negotiated draws nothing rather than a zero-tile grid`() {
        assertIs<ConferenceVideoMode.AudioOnly>(mode(0))
    }
}
