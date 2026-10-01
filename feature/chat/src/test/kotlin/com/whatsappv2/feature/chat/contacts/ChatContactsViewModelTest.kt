package com.whatsappv2.feature.chat.contacts

import com.whatsappv2.domain.chat.ChatAuthError
import com.whatsappv2.domain.chat.ChatContact
import com.whatsappv2.domain.testing.FakeChatContactRepository
import com.whatsappv2.domain.testing.FakeChatRepository
import com.whatsappv2.domain.usecase.OpenConversationUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The directory's four states, and the one rule that shapes them all: a failed refresh
 * must not blank a list that was already useful.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatContactsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeChatContactRepository()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val chat = FakeChatRepository()

    /** The use case is real; only its repository is a fake. Opening is tested through it. */
    private fun viewModel() = ChatContactsViewModel(repository, OpenConversationUseCase(chat))

    private fun contact(id: String, name: String, extension: String? = null) =
        ChatContact(id = id, displayName = name, extension = extension, department = "coral-test", avatarUrl = null)

    @Test
    fun `the directory is loaded on open`() = runTest(dispatcher) {
        repository.given(contact("1001", "Rahul Singh"))
        val model = viewModel()

        testScheduler.advanceUntilIdle()

        assertEquals(listOf("Rahul Singh"), model.state.value.contacts.map { it.displayName })
        assertFalse(model.state.value.isLoading)
    }

    @Test
    fun `opening the screen issues exactly one request`() = runTest(dispatcher) {
        // The query flow and the initial refresh both want to fire. Without the drop(1)
        // in the ViewModel that is two identical HTTP calls every time the screen opens.
        viewModel()

        testScheduler.advanceUntilIdle()

        assertEquals(listOf<String?>(null), repository.refreshQueries)
    }

    @Test
    fun `an empty directory is the empty state, not an error`() = runTest(dispatcher) {
        val model = viewModel()

        testScheduler.advanceUntilIdle()

        assertTrue(model.state.value.isEmpty)
        assertNull(model.state.value.error)
    }

    @Test
    fun `a failed refresh is an error, and is not the empty state`() = runTest(dispatcher) {
        repository.givenRefreshFails(ChatAuthError.Network)
        val model = viewModel()

        testScheduler.advanceUntilIdle()

        assertEquals(ChatAuthError.Network, model.state.value.error)
        assertFalse(model.state.value.isEmpty, "a failure was reported as 'no contacts yet'")
    }

    @Test
    fun `a failed refresh keeps the rows that were already there`() = runTest(dispatcher) {
        repository.given(contact("1001", "Rahul Singh"))
        val model = viewModel()
        testScheduler.advanceUntilIdle()

        repository.givenRefreshFails(ChatAuthError.Network)
        model.refresh()
        testScheduler.advanceUntilIdle()

        assertEquals(1, model.state.value.contacts.size, "a useful list was blanked to show an error")
        assertEquals(ChatAuthError.Network, model.state.value.error)
    }

    @Test
    fun `retry after a failure clears the error and reloads`() = runTest(dispatcher) {
        repository.given(contact("1001", "Rahul Singh"))
        repository.givenRefreshFails(ChatAuthError.Network)
        val model = viewModel()
        testScheduler.advanceUntilIdle()

        repository.nextResult = null
        model.refresh()
        testScheduler.advanceUntilIdle()

        assertNull(model.state.value.error)
        assertEquals(1, model.state.value.contacts.size)
    }

    @Test
    fun `the query goes to the server, debounced into one request`() = runTest(dispatcher) {
        repository.given(contact("1001", "Rahul Singh"))
        repository.given(contact("1005", "Priya Nair"))
        val model = viewModel()
        testScheduler.advanceUntilIdle()

        // Four keystrokes. One request, or the directory is re-fetched per character.
        model.setQuery("P")
        model.setQuery("Pr")
        model.setQuery("Pri")
        model.setQuery("Priya")
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(null, "Priya"), repository.refreshQueries)
        assertEquals(listOf("Priya Nair"), model.state.value.contacts.map { it.displayName })
    }

    @Test
    fun `a blank query is sent as no filter rather than as an empty string`() = runTest(dispatcher) {
        val model = viewModel()
        testScheduler.advanceUntilIdle()

        model.setQuery("   ")
        testScheduler.advanceUntilIdle()

        assertEquals(listOf<String?>(null, null), repository.refreshQueries)
    }

    @Test
    fun `search appears only once the list can exceed a screen`() = runTest(dispatcher) {
        repeat(12) { repository.given(contact("100$it", "Person $it")) }
        val model = viewModel()

        testScheduler.advanceUntilIdle()

        assertTrue(model.state.value.isSearchable)
    }

    @Test
    fun `search stays visible once a query narrows the list to nothing`() = runTest(dispatcher) {
        // Otherwise the box that produced the empty result disappears with the rows, and
        // there is no way to clear the query that caused it.
        repeat(12) { repository.given(contact("100$it", "Person $it")) }
        val model = viewModel()
        testScheduler.advanceUntilIdle()

        model.setQuery("nobody")
        testScheduler.advanceUntilIdle()

        assertTrue(model.state.value.contacts.isEmpty())
        assertTrue(model.state.value.isSearchable)
    }

    @Test
    fun `choosing somebody opens the conversation and then announces where to go`() = runTest(dispatcher) {
        // Opening BEFORE navigating: the thread's id belongs to the chat server and does
        // not exist until it says so. Navigating first would land on an id-less screen.
        chat.givenConnected()
        val model = viewModel()
        testScheduler.advanceUntilIdle()

        model.openConversationWith(contact("1001", "Rahul Singh", extension = "1001"))
        testScheduler.advanceUntilIdle()

        // The extension, not the directory's numeric id: chat-node keys an identity by
        // the PPDR username and knows nothing about a UC database row.
        assertEquals(listOf("1001"), chat.opened)
        assertEquals("conv-1001", model.opened.first().value)
    }
}
