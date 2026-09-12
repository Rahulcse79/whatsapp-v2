package com.whatsappv2.feature.history

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onChildren
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
    fun `clearing the whole log is behind the overflow, not a button in the bar`() {
        // Deleting everything was one tap from the bar it shares with Search. It is a
        // once-a-year action, so it lives where once-a-year actions live.
        var requested = false
        setContent(actions = HistoryActions(onClearAllRequested = { requested = true }))

        compose.onNodeWithTag(TAG_CLEAR_ALL).assertDoesNotExist()
        compose.onNodeWithTag(TAG_OVERFLOW).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TAG_CLEAR_ALL).performClick()
        compose.waitForIdle()

        assertTrue(requested)
    }

    @Test
    fun `at rest there is one filter control and no chips`() {
        // A resting log is a list, not a form: the direction and date filters live behind
        // the one icon, and the strip of active filters is absent until one is on.
        setContent()

        compose.onNodeWithTag(TAG_FILTERS).assertIsDisplayed()
        compose.onNodeWithTag(TAG_ACTIVE_FILTERS).assertDoesNotExist()
        compose.onNodeWithTag(TAG_FILTER_MENU).assertDoesNotExist()
    }

    @Test
    fun `the filter icon opens a menu with every direction, the one in force ticked`() {
        setContent(state = HistoryUiState(query = CallLogQuery(direction = CallDirectionFilter.OUTGOING)))

        compose.onNodeWithTag(TAG_FILTERS).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(TAG_FILTER_MENU).assertIsDisplayed()
        CallDirectionFilter.entries.forEach { compose.onNodeWithTag(directionItemTag(it)).assertIsDisplayed() }
        // Exactly one tick, on the direction that is on — the "clearly highlighted" rule.
        compose.onAllNodesWithContentDescription("Selected").assertCountEquals(1)
        compose.onNodeWithTag(directionItemTag(CallDirectionFilter.OUTGOING), useUnmergedTree = true)
            .onChildren()
            .filterToOne(hasContentDescription("Selected"))
            .assertExists()
    }

    @Test
    fun `choosing a direction in the menu reports it and closes the menu`() {
        var chosen: CallDirectionFilter? = null
        setContent(actions = HistoryActions(onDirectionChanged = { chosen = it }))

        compose.onNodeWithTag(TAG_FILTERS).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(directionItemTag(CallDirectionFilter.INCOMING)).performClick()
        compose.waitForIdle()

        assertEquals(CallDirectionFilter.INCOMING, chosen)
        compose.onNodeWithTag(TAG_FILTER_MENU).assertDoesNotExist()
    }

    @Test
    fun `a filter in force is counted on the icon and shown as a chip with a way off`() {
        var cleared: CallDirectionFilter? = null
        setContent(
            state = HistoryUiState(query = CallLogQuery(direction = CallDirectionFilter.INCOMING)),
            actions = HistoryActions(onDirectionChanged = { cleared = it }),
        )

        compose.onNodeWithContentDescription("Filters, 1 active").assertIsDisplayed()
        compose.onNodeWithTag(TAG_ACTIVE_FILTERS).assertIsDisplayed()
        compose.onNodeWithTag(activeDirectionTag(CallDirectionFilter.INCOMING)).performClick()
        compose.waitForIdle()

        assertEquals(CallDirectionFilter.ANY, cleared)
    }

    @Test
    fun `missed is the tab's job, so it is neither counted nor chipped`() {
        // Missed is a direction and a tab because it is one axis. With the Missed tab lit,
        // a badge saying "1" and a chip saying "Missed" would both be the tab, repeated.
        setContent(state = HistoryUiState(query = CallLogQuery.MISSED))

        compose.onNodeWithContentDescription("Filters").assertIsDisplayed()
        compose.onNodeWithTag(TAG_ACTIVE_FILTERS).assertDoesNotExist()
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
        compose.onNodeWithTag(TAG_ACTIVE_DATE).assertIsDisplayed().assertTextContains("12", substring = true)
        compose.onNodeWithTag(TAG_ACTIVE_DATE).performClick()
        compose.waitForIdle()

        assertTrue(cleared)
    }

    @Test
    fun `the date row in the menu opens the range picker`() {
        setContent()

        compose.onNodeWithTag(TAG_FILTERS).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TAG_DATE_ITEM).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(TAG_DATE_DIALOG).assertIsDisplayed()
    }

    @Test
    fun `the menu offers nothing to reset while nothing is narrowed`() {
        // A reset row on a log that is showing everything is a control with no effect.
        setContent()

        compose.onNodeWithTag(TAG_FILTERS).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(TAG_FILTER_MENU).assertIsDisplayed()
        compose.onNodeWithTag(TAG_RESET_FILTERS).assertDoesNotExist()
    }

    @Test
    fun `reset appears once something is narrowed, and resets`() {
        var reset = false
        setContent(
            state = HistoryUiState(query = CallLogQuery(direction = CallDirectionFilter.OUTGOING)),
            actions = HistoryActions(onFiltersCleared = { reset = true }),
        )

        compose.onNodeWithTag(TAG_FILTERS).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TAG_RESET_FILTERS).performClick()
        compose.waitForIdle()

        assertTrue(reset)
    }

    private companion object {
        const val STARTED_AT = 1_789_000_000_000L
    }
}
