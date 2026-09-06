package com.whatsappv2.feature.history

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallLogEntry
import com.whatsappv2.domain.model.CallLogId
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.repository.CallLogFilter
import com.whatsappv2.domain.testing.FakeCallLogRepository
import com.whatsappv2.domain.testing.FakeSipAccountRepository
import com.whatsappv2.domain.testing.FakeSipEngine
import com.whatsappv2.domain.usecase.PlaceCallUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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

    private val repository = FakeCallLogRepository()
    private val accounts = FakeSipAccountRepository()
    private val engine = FakeSipEngine()
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = HistoryViewModel(repository, PlaceCallUseCase(accounts, engine))

    @Test
    fun `the filter starts on everything, because that is what a log is for`() = runTest {
        assertEquals(CallLogFilter.ALL, viewModel().uiState.value.filter)
    }

    @Test
    fun `choosing the missed tab changes what is asked for`() = runTest {
        val viewModel = viewModel()

        viewModel.onFilterChanged(CallLogFilter.MISSED)
        runCurrent()

        assertEquals(CallLogFilter.MISSED, viewModel.uiState.value.filter)
    }

    @Test
    fun `opening an entry shows its detail, and dismissing closes it`() = runTest {
        val entry = repository.record(entry())
        val viewModel = viewModel()

        viewModel.onEntryOpened(entry)
        runCurrent()
        assertEquals(entry, viewModel.uiState.value.openEntry)

        viewModel.onDetailDismissed()
        runCurrent()
        assertNull(viewModel.uiState.value.openEntry)
    }

    @Test
    fun `deleting the open entry closes the detail that was showing it`() = runTest {
        val entry = repository.record(entry())
        val viewModel = viewModel()
        viewModel.onEntryOpened(entry)
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
        viewModel.onEntryOpened(open)
        runCurrent()

        viewModel.onDelete(other.id)
        runCurrent()

        assertEquals(open, viewModel.uiState.value.openEntry)
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

    private fun entry() = CallLogEntry(
        id = CallLogId.UNSAVED,
        accountId = AccountId("acct-1"),
        remote = REMOTE,
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
    }
}
