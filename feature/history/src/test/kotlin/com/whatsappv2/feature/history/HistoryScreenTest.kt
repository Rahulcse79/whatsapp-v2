package com.whatsappv2.feature.history

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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

    private fun entry(
        id: Long,
        media: MediaProfile,
        answered: Boolean = true,
        conference: Boolean = false,
    ) = CallLogEntry(
        id = CallLogId(id),
        accountId = AccountId("acct-1"),
        remote = remote,
        accountDomain = null,
        remoteDisplayName = "Echo",
        contactName = null,
        direction = CallDirection.INCOMING,
        startedAtEpochMillis = STARTED_AT + id,
        answeredAtEpochMillis = if (answered) STARTED_AT + id else null,
        endedAtEpochMillis = STARTED_AT + id + 6_000,
        reason = HangupReason.REMOTE_HANGUP,
        media = media,
        isConference = conference,
    )

    private val voice = entry(1, MediaProfile.AUDIO)
    private val video = entry(2, MediaProfile.AUDIO_VIDEO)
    private val missedVideo = entry(3, MediaProfile.AUDIO_VIDEO, answered = false)
    private val conference = entry(4, MediaProfile.AUDIO_VIDEO, conference = true)

    private fun setContent(
        state: HistoryUiState = HistoryUiState(),
        rows: List<HistoryRow> =
            listOf(voice, video, missedVideo, conference).map { HistoryRow.Call(it, "Echo") },
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
    fun `a conference is marked as one, in the glyph and in words`() {
        // A conference this device mixed writes one row per leg, so a merged three-way
        // arrived in history as two unrelated calls to two people with nothing joining
        // them up (TC15, 2026-09-15). The group glyph replaces the voice/video one —
        // "was this the conference" is the question the list could not answer at all,
        // while voice-versus-video is still in the label and in the swipe actions.
        setContent()

        compose.onNodeWithTag(mediaTag(conference), useUnmergedTree = true)
            .assertIsDisplayed()
            .assertContentDescriptionEquals("Video conference")
        // And in text, for anyone who does not read a group icon as a word.
        compose.onNode(
            hasText("Conference", substring = true) and hasAnyAncestor(hasTestTag(entryTag(conference))),
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun `an ordinary call is not marked as a conference`() {
        // The other half of the claim: the marker means something only if it is absent
        // from the rows that were not conferences.
        setContent()

        compose.onNodeWithTag(mediaTag(video), useUnmergedTree = true)
            .assertContentDescriptionEquals("Video call")
        compose.onNode(
            hasText("Conference", substring = true) and hasAnyAncestor(hasTestTag(entryTag(video))),
            useUnmergedTree = true,
        ).assertDoesNotExist()
    }

    @Test
    fun `the kind of call leads the row, where the avatar was`() {
        // Rahul, 2026-09-14: the glyph at the far end of the row was past the text people
        // scan, and the avatar it replaces said nothing here — no photos, no initials for
        // an extension. So the glyph sits first, and the title starts to its right.
        setContent()

        val glyph = compose.onNodeWithTag(mediaTag(voice), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val title = compose.onNode(
            hasText("Echo") and hasAnyAncestor(hasTestTag(entryTag(voice))),
            useUnmergedTree = true,
        ).getUnclippedBoundsInRoot()

        assertTrue(glyph.right <= title.left, "glyph $glyph should sit left of the title $title")
    }

    @Test
    fun `the gear opens settings, and is absent where there is nowhere to go`() {
        // Settings is not a tab, so it is reached from a top-level destination - and it is
        // on both of them now, not just Chats, so which tab you happen to be on when you
        // want it is not something to think about.
        var opened = 0
        setContent(actions = HistoryActions(onOpenSettings = { opened++ }))

        compose.onNodeWithTag(TAG_SETTINGS).assertIsDisplayed().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `no gear is drawn without somewhere for it to go`() {
        // The callback is nullable precisely so a preview, or a surface with no navigation
        // behind it, gets no gear rather than one that does nothing when pressed.
        setContent(actions = HistoryActions())

        compose.onNodeWithTag(TAG_SETTINGS).assertDoesNotExist()
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

    @Test
    fun `a conference is one entry, and its detail names every member`() {
        // The detail of one leg used to say "1005 · Voice call · Conference": one person
        // out of six. The entry is the conference, and the members are the detail.
        val legs = listOf("1001" to "Priya Nair", "1002" to null, "1005" to null).mapIndexed { index, (user, name) ->
            entry(id = 10L + index, media = MediaProfile.AUDIO, answered = index != 2, conference = true).copy(
                remote = SipUri.parse("sip:$user@sip.example.com").getOrNull()!!,
                remoteDisplayName = null,
                contactName = name,
                conferenceKey = "k1",
            )
        }
        val rows = legs.reversed().map { HistoryRow.Call(it, it.contactName ?: it.remote.user!!) }
        val row = groupConferences(rows).single()
        var deleted: HistoryRow.Call? = null
        setContent(
            state = HistoryUiState(openEntry = row),
            rows = listOf(row),
            actions = HistoryActions(onDelete = { deleted = it }),
        )

        compose.onNodeWithTag(entryTag(row.entry)).assertIsDisplayed()
        compose.onNodeWithTag(TAG_DETAIL).assertIsDisplayed()
        compose.onNodeWithTag(TAG_DETAIL_MEMBERS).onChildren().assertCountEquals(3)
        // The row is a plain layout, so its texts are its children rather than merged.
        fun member(index: Int) = compose.onNodeWithTag(memberTag(legs[index])).onChildren()
        member(0).filterToOne(hasText("Priya Nair")).assertIsDisplayed()
        member(0).filterToOne(hasText("1001")).assertIsDisplayed()
        member(1).filterToOne(hasText("1002")).assertIsDisplayed()
        member(2).filterToOne(hasText("Not answered")).assertIsDisplayed()

        compose.onNodeWithTag(TAG_DETAIL_DELETE).performClick()
        assertEquals(row, deleted, "deleting the conference deletes the conference, not one leg")
    }

    private companion object {
        const val STARTED_AT = 1_789_000_000_000L
    }
}
