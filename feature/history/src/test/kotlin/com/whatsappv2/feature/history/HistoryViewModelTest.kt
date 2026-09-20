package com.whatsappv2.feature.history

import androidx.paging.PagingData
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.engine.CameraAvailability
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallLogEntry
import com.whatsappv2.domain.model.CallLogId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.repository.CallDirectionFilter
import com.whatsappv2.domain.repository.CallLogFilter
import com.whatsappv2.domain.repository.CallLogQuery
import com.whatsappv2.domain.testing.FakeCallLogRepository
import com.whatsappv2.domain.testing.FakeContactRepository
import com.whatsappv2.domain.testing.FakeSipAccountRepository
import com.whatsappv2.domain.testing.FakeSipEngine
import com.whatsappv2.domain.usecase.CallLogTitles
import com.whatsappv2.domain.usecase.ConferenceJoinCoordinator
import com.whatsappv2.domain.usecase.PlaceCallUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the history screen holds besides the list (Task 48).
 *
 * The paged list is asserted in [CallLogPagingSourceTest]; what is here is the state a
 * `PagingData` stream cannot carry — the filter, the open detail, and the confirmation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    /** A device that can capture, so a video request stays a video request. */
    private object CameraPresent : CameraAvailability {
        override fun isCameraUsable(): Boolean = true
    }

    private val repository = FakeCallLogRepository()
    private val contacts = FakeContactRepository()
    private val accounts = FakeSipAccountRepository()
    private val engine = FakeSipEngine()
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(camera: CameraAvailability = CameraPresent) =
        HistoryViewModel(
            repository,
            PlaceCallUseCase(accounts, engine, camera, engine),
            camera,
            CallLogTitles(contacts),
            ConferenceJoinCoordinator(engine, engine),
        )

    /** The row as the list would have built it, title already resolved. */
    private fun row(entry: CallLogEntry) = HistoryRow.Call(entry, entry.remote.label())

    @Test
    fun `the filter starts on everything, because that is what a log is for`() = runTest {
        assertEquals(CallLogQuery.MATCH_ALL, viewModel().uiState.value.query)
    }

    @Test
    fun `choosing the missed tab changes what is asked for`() = runTest {
        val viewModel = viewModel()

        viewModel.onFilterChanged(CallLogFilter.MISSED)
        runCurrent()

        // The tab IS the direction now: one axis, one place (item 1's advanced search).
        assertEquals(CallDirectionFilter.MISSED, viewModel.uiState.value.query.direction)
        assertEquals(CallLogFilter.MISSED, viewModel.uiState.value.query.tabFilter)
    }

    @Test
    fun `searching narrows the list in the store, not on the page`() = runTest {
        val viewModel = viewModel()

        viewModel.onSearchToggled(open = true)
        viewModel.onSearchTextChanged("rahul")
        runCurrent()

        assertEquals("rahul", viewModel.uiState.value.query.text)
        assertTrue(viewModel.uiState.value.searching)
    }

    @Test
    fun `closing the search clears what was typed, because that is what Back means`() = runTest {
        val viewModel = viewModel()
        viewModel.onSearchToggled(open = true)
        viewModel.onSearchTextChanged("rahul")
        runCurrent()

        viewModel.onSearchToggled(open = false)
        runCurrent()

        assertEquals(CallLogQuery.MATCH_ALL, viewModel.uiState.value.query)
    }

    @Test
    fun `clearing the filters keeps the text the user is still typing`() = runTest {
        val viewModel = viewModel()
        viewModel.onSearchTextChanged("rahul")
        viewModel.onDirectionChanged(CallDirectionFilter.OUTGOING)
        runCurrent()

        viewModel.onFiltersCleared()
        runCurrent()

        assertEquals("rahul", viewModel.uiState.value.query.text)
        assertEquals(CallDirectionFilter.ANY, viewModel.uiState.value.query.direction)
    }

    @Test
    fun `opening an entry shows its detail, and dismissing closes it`() = runTest {
        val entry = repository.record(entry())
        val viewModel = viewModel()

        viewModel.onEntryOpened(row(entry))
        runCurrent()
        assertEquals(entry, viewModel.uiState.value.openEntry?.entry)

        viewModel.onDetailDismissed()
        runCurrent()
        assertNull(viewModel.uiState.value.openEntry)
    }

    @Test
    fun `deleting the open entry closes the detail that was showing it`() = runTest {
        val entry = repository.record(entry())
        val viewModel = viewModel()
        viewModel.onEntryOpened(row(entry))
        runCurrent()

        viewModel.onDelete(entry.id)
        runCurrent()

        assertNull(viewModel.uiState.value.openEntry)
        assertTrue(repository.recorded.isEmpty())
    }

    @Test
    fun `deleting a different entry leaves the open detail alone`() = runTest {
        // Deleting from the list behind an open sheet must not shut the sheet on a call
        // the user is still reading.
        val open = repository.record(entry())
        val other = repository.record(entry())
        val viewModel = viewModel()
        viewModel.onEntryOpened(row(open))
        runCurrent()

        viewModel.onDelete(other.id)
        runCurrent()

        assertEquals(open, viewModel.uiState.value.openEntry?.entry)
    }

    @Test
    fun `clearing everything is confirmed first, and then empties the log`() = runTest {
        repository.record(entry())
        val viewModel = viewModel()

        viewModel.onClearAllRequested()
        runCurrent()
        assertTrue(viewModel.uiState.value.confirmingClearAll)
        // Nothing is gone until it is confirmed: this cannot be undone.
        assertFalse(repository.recorded.isEmpty())

        viewModel.onClearAllConfirmed()
        runCurrent()

        assertFalse(viewModel.uiState.value.confirmingClearAll)
        assertTrue(repository.recorded.isEmpty())
    }

    @Test
    fun `dismissing the confirmation keeps the history`() = runTest {
        repository.record(entry())
        val viewModel = viewModel()

        viewModel.onClearAllRequested()
        viewModel.onClearAllDismissed()
        runCurrent()

        assertFalse(viewModel.uiState.value.confirmingClearAll)
        assertFalse(repository.recorded.isEmpty())
    }

    // ------------------------------------------------------------- refreshing

    @Test
    fun `a call back dials the extension on the server's address now, not the one in the row`() = runTest {
        // The server moved between the call and the call back — a laptop-hosted PBX on a
        // new Wi-Fi network, 2026-09-14. The row's address is the old one; the account's
        // domain is the new one; the extension is the same person on both.
        accounts.given(work(domain = "192.168.0.101"))
        engine.givenRegistered(work(domain = "192.168.0.101"))
        val entry = repository.record(
            entry(remote = "sip:1003@192.168.2.196", accountDomain = "192.168.2.196"),
        )

        viewModel().onCallBack(entry)
        advanceUntilIdle()

        assertEquals("sip:1003@192.168.0.101", lastPlacedCall())
    }

    @Test
    fun `a call back to another domain dials that domain`() = runTest {
        // Not the account's server, so not the account's business to rewrite.
        accounts.given(work(domain = "192.168.0.101"))
        engine.givenRegistered(work(domain = "192.168.0.101"))
        val entry = repository.record(
            entry(remote = "sip:carol@other.example.com", accountDomain = "192.168.0.101"),
        )

        viewModel().onCallBack(entry)
        advanceUntilIdle()

        assertEquals("sip:carol@other.example.com", lastPlacedCall())
    }

    @Test
    fun `deleting a conference removes every leg, not the one the entry was built on`() = runTest {
        val first = repository.record(leg("sip:1001@sip.example.com"))
        val second = repository.record(leg("sip:1002@sip.example.com"))
        val unrelated = repository.record(entry(remote = "sip:1003@sip.example.com"))
        val conference = groupConferences(listOf(row(second), row(first))).single()
        val viewModel = viewModel()
        viewModel.onEntryOpened(conference)
        runCurrent()

        viewModel.onDelete(conference)
        runCurrent()

        assertEquals(listOf(unrelated), repository.recorded, "both legs gone, the other call untouched")
        assertNull(viewModel.uiState.value.openEntry)
    }

    @Test
    fun `calling a conference back dials every member once and asks for each to join`() = runTest {
        // Called back as one, the way a group call is anywhere else: one INVITE per
        // distinct member — 1005 was dialled twice in the original — and each mixed in
        // as they answer, which is the join coordinator's job from here.
        accounts.given(work(domain = "sip.example.com"))
        engine.givenRegistered(work(domain = "sip.example.com"))
        val legs = listOf("1001", "1005", "1005", "1002").mapIndexed { index, user ->
            repository.record(
                entry(remote = "sip:$user@sip.example.com").copy(
                    isConference = true,
                    conferenceKey = "k1",
                    startedAtEpochMillis = STARTED_AT + index,
                ),
            )
        }
        val conference = groupConferences(legs.reversed().map(::row)).single()

        viewModel().onCallBack(conference)
        advanceUntilIdle()

        assertEquals(
            listOf("sip:1001@sip.example.com", "sip:1005@sip.example.com", "sip:1002@sip.example.com"),
            engine.activeCalls.value.map { it.remote.render() },
        )
    }

    @Test
    fun `a call recorded while the screen is open reaches the list`() = runTest {
        // The regression test for Task 71, and it fails on the parent commit.
        //
        // `CallLogRepository.changes()` was implemented in :data:calllog, documented here
        // and in CallLogPagingSource as the thing that keeps this list live, and collected
        // by nothing at all — so a call that ended while the screen was open never showed
        // up. A new PagingData is what a reload looks like from outside the ViewModel.
        val viewModel = viewModel()

        val reloads = mutableListOf<PagingData<HistoryRow>>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.rows.toList(reloads)
        }
        advanceUntilIdle()
        val before = reloads.size

        repository.record(entry())
        advanceUntilIdle()

        assertTrue(reloads.size > before, "the list did not reload when the call log changed")
        collector.cancel()
    }

    @Test
    fun `returning to the screen reloads the list`() = runTest {
        // The other half of Task 71: the write happened while this ViewModel's collector
        // was stopped, so the signal that would have invalidated the source was never
        // delivered. Coming back has to ask.
        val viewModel = viewModel()

        val reloads = mutableListOf<PagingData<HistoryRow>>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.rows.toList(reloads)
        }
        advanceUntilIdle()
        val before = reloads.size

        viewModel.refresh()
        advanceUntilIdle()

        assertTrue(reloads.size > before, "refresh() did not reload the list")
        collector.cancel()
    }

    /** What the engine was last asked to dial, rendered — the URI after completion. */
    private fun lastPlacedCall(): String =
        engine.invocations.last { it.operation == FakeSipEngine.Operation.PLACE_CALL }.detail

    /** One leg of the conference "k1", as the recorder would have written it. */
    private fun leg(remote: String) = entry(remote = remote).copy(isConference = true, conferenceKey = "k1")

    private fun entry(
        remote: String = REMOTE.render(),
        accountDomain: String? = null,
    ) = CallLogEntry(
        id = CallLogId.UNSAVED,
        accountId = AccountId("acct-1"),
        remote = checkNotNull(SipUri.parse(remote).getOrNull()) { "bad fixture: $remote" },
        accountDomain = accountDomain,
        remoteDisplayName = null,
        contactName = null,
        direction = CallDirection.OUTGOING,
        startedAtEpochMillis = STARTED_AT,
        answeredAtEpochMillis = null,
        endedAtEpochMillis = STARTED_AT,
        reason = HangupReason.LOCAL_HANGUP,
        media = MediaProfile.AUDIO,
    )

    private companion object {
        val REMOTE: SipUri = SipUri.parse("sip:bob@sip.example.com").getOrNull()!!
        const val STARTED_AT = 1_700_000_000_000L

        /** The account every fixture entry is on, with whatever domain it has *now*. */
        fun work(domain: String) = SipAccount(
            id = AccountId("acct-1"),
            label = "Work",
            username = "1002",
            extension = null,
            authUsername = null,
            password = Secret("hunter22"),
            displayName = null,
            domain = domain,
            registrar = null,
            outboundProxy = null,
            port = null,
            transport = Transport.UDP,
            registrationExpirySeconds = 3_600,
            stunServer = null,
            turn = null,
            natPolicy = NatPolicy.DEFAULT,
            srtpPolicy = SrtpPolicy.DISABLED,
            codecs = CodecPreferences.DEFAULT,
            isDefault = true,
        )
    }
}
