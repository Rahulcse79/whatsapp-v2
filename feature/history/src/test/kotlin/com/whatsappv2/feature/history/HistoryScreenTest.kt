package com.whatsappv2.feature.history

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.designsystem.theme.WhatsAppV2Theme
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallLogEntry
import com.whatsappv2.domain.model.CallLogId
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.repository.CallDirectionFilter
import com.whatsappv2.domain.repository.CallLogQuery
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The call log, rendered from literal rows (item 5.3).
 *
 * No ViewModel and no store: the rows are a `PagingData` built from a list, so what is
 * under test is what the screen shows for a given state and what pressing things reports.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [HISTORY_ROBOLECTRIC_SDK])
class HistoryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val remote = requireNotNull(SipUri.parse("sip:9196@sip.example.com").getOrNull())

    private fun entry(id: Long, media: MediaProfile, answered: Boolean = true) = CallLogEntry(
        id = CallLogId(id),
        accountId = AccountId("acct-1"),
        remote = remote,
        remoteDisplayName = "Echo",
        contactName = null,
        direction = CallDirection.INCOMING,
        startedAtEpochMillis = STARTED_AT + id,
        answeredAtEpochMillis = if (answered) STARTED_AT + id else null,
        endedAtEpochMillis = STARTED_AT + id + 6_000,
        reason = HangupReason.REMOTE_HANGUP,
        media = media,
    )

    private val voice = entry(1, MediaProfile.AUDIO)
    private val video = entry(2, MediaProfile.AUDIO_VIDEO)
    private val missedVideo = entry(3, MediaProfile.AUDIO_VIDEO, answered = false)

    private fun setContent(
        state: HistoryUiState = HistoryUiState(),
        rows: List<HistoryRow> = listOf(voice, video, missedVideo).map { HistoryRow.Call(it, "Echo") },
        actions: HistoryActions = HistoryActions(),
    ) {
        compose.setContent {
            WhatsAppV2Theme {
                HistoryScreen(
                    state = state,
                    rows = flowOf(PagingData.from(rows)).collectAsLazyPagingItems(),
                    actions = actions,
                    snackbarHostState = SnackbarHostState(),
                    zone = zone,
                )
            }
        }
    }

    @Test
    fun `every row says whether it was a voice or a video call`() {
        // The log recorded which; the row did not show it, so a missed video call and a
        // missed audio call looked identical. Now each carries a glyph with words behind it.
        setContent()

        compose.onNodeWithTag(mediaTag(voice), useUnmergedTree = true)
            .assertIsDisplayed()
            .assertContentDescriptionEquals("Voice call")
        compose.onNodeWithTag(mediaTag(video), useUnmergedTree = true)
            .assertContentDescriptionEquals("Video call")
        compose.onNodeWithTag(mediaTag(missedVideo), useUnmergedTree = true)
            .assertContentDescriptionEquals("Video call")
    }

    @Test
    fun `the filter row is hidden at rest and the funnel reveals it`() {
        // A resting log is a list, not a form. The funnel is how the filters are found.
        var toggledTo: Boolean? = null
        setContent(actions = HistoryActions(onFiltersToggled = { toggledTo = it }))

        compose.onNodeWithTag(TAG_FILTER_ROW).assertDoesNotExist()
        compose.onNodeWithTag(TAG_FILTERS).performClick()
        compose.waitForIdle()

        assertEquals(true, toggledTo)
    }

    @Test
    fun `the filter row is shown once the funnel has been pressed`() {
        setContent(state = HistoryUiState(filtersOpen = true))

        compose.onNodeWithTag(TAG_FILTER_ROW).assertIsDisplayed()
        compose.onNodeWithTag(TAG_DATE_CHIP).assertIsDisplayed()
    }

    @Test
    fun `the filter row is shown while something is narrowed, funnel or not`() {
        setContent(state = HistoryUiState(query = CallLogQuery(direction = CallDirectionFilter.OUTGOING)))

        compose.onNodeWithTag(TAG_FILTER_ROW).assertIsDisplayed()
        compose.onNodeWithContentDescription("Filters, 1 active").assertIsDisplayed()
    }

    @Test
    fun `an applied date range is named on its chip and can be dropped from it`() {
        var cleared = false
        // 12 Sep 2026 in Kolkata, midnight to midnight.
        val from = java.time.LocalDate.of(2026, 9, 12).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = java.time.LocalDate.of(2026, 9, 13).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        setContent(
            state = HistoryUiState(query = CallLogQuery(fromEpochMillis = from, toEpochMillis = to)),
            actions = HistoryActions(onDateRangeChanged = { f, t -> cleared = f == null && t == null }),
        )

        // Locale-independent: the day number is the same in every dictionary.
        compose.onNodeWithTag(TAG_DATE_CHIP).assertIsDisplayed().assertTextContains("12", substring = true)
        compose.onNodeWithTag(TAG_DATE_CLEAR, useUnmergedTree = true).performClick()
        compose.waitForIdle()

        assertTrue(cleared)
    }

    @Test
    fun `the date chip opens the range picker`() {
        setContent(state = HistoryUiState(filtersOpen = true))

        compose.onNodeWithTag(TAG_DATE_CHIP).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(TAG_DATE_DIALOG).assertIsDisplayed()
    }

    private companion object {
        const val STARTED_AT = 1_789_000_000_000L
    }
}
