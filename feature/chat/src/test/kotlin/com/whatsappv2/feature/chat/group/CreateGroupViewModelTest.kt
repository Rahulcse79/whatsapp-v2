package com.whatsappv2.feature.chat.group

import com.whatsappv2.domain.chat.ChatContact
import com.whatsappv2.domain.chat.ChatGroup
import com.whatsappv2.domain.testing.FakeChatContactRepository
import com.whatsappv2.domain.testing.FakeChatGroupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Choosing who is in a group, and the cap that keeps it callable.
 *
 * The limit is this app's rather than the server's — chat-node takes a fifth member happily.
 * A group this app creates is capped at the size it can also ring, which is the four-party
 * ceiling the rest of the app holds rather than one the conference bridge imposes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateGroupViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val contacts = FakeChatContactRepository()
    private val groups = FakeChatGroupRepository()

    private fun contact(username: String) = ChatContact(
        id = username,
        username = username,
        displayName = username.removePrefix("mcx"),
        extension = username.removePrefix("mcx"),
        department = "coral-test",
        avatarUrl = null,
    )

    private fun viewModel() = CreateGroupViewModel(contacts, groups)

    @Test
    fun `the creator counts towards the limit, because they are on the call too`() = runTest(dispatcher) {
        val model = viewModel()

        assertEquals(1, model.state.value.memberCount, "a group of nobody still contains you")
        assertEquals("1 of ${ChatGroup.MAX_MEMBERS}", model.state.value.countLabel)

        model.toggle("mcx8101")
        assertEquals(2, model.state.value.memberCount)
    }

    @Test
    fun `a fifth person cannot be added, and the rows say so before being tapped`() = runTest(dispatcher) {
        val model = viewModel()
        model.toggle("mcx8101")
        model.toggle("mcx8102")
        model.toggle("mcx8103")

        assertEquals(ChatGroup.MAX_MEMBERS, model.state.value.memberCount)
        assertEquals(0, model.state.value.remaining)
        // Unselectable BEFORE the tap: learning the rule by tapping and getting nothing is
        // worse than a row that is visibly unavailable.
        assertFalse(model.state.value.isSelectable("mcx8104"))

        model.toggle("mcx8104")
        assertEquals(3, model.state.value.selected.size, "a fourth member slipped past the cap")
    }

    @Test
    fun `somebody already chosen can always be un-chosen, even at the cap`() = runTest(dispatcher) {
        val model = viewModel()
        model.toggle("mcx8101")
        model.toggle("mcx8102")
        model.toggle("mcx8103")

        assertTrue(model.state.value.isSelectable("mcx8101"), "a chosen row went dead at the cap")
        model.toggle("mcx8101")

        assertEquals(listOf("mcx8102", "mcx8103"), model.state.value.selected)
        assertTrue(model.state.value.isSelectable("mcx8104"), "a freed slot was not offered")
    }

    @Test
    fun `a group needs a name and somebody in it`() = runTest(dispatcher) {
        val model = viewModel()
        assertFalse(model.state.value.canCreate, "an empty form offered to create a group")

        model.setName("Ops")
        assertFalse(model.state.value.canCreate, "a group with nobody in it was offered")

        model.toggle("mcx8101")
        assertTrue(model.state.value.canCreate)
    }

    @Test
    fun `creating sends the designations, which is what chat-node resolves`() = runTest(dispatcher) {
        contacts.given(contact("mcx8101"))
        val model = viewModel()
        model.setName("  Ops  ")
        model.toggle("mcx8101")

        model.create()
        testScheduler.advanceUntilIdle()

        // Trimmed, and by designation rather than extension: the extension addresses nothing
        // on the chat server.
        assertEquals(listOf("Ops" to listOf("mcx8101")), groups.creates)
    }

    @Test
    fun `the new group's id is announced once, for navigation`() = runTest(dispatcher) {
        val model = viewModel()
        model.setName("Ops")
        model.toggle("mcx8101")

        // Collected on the test's OWN scope rather than `backgroundScope`:
        // `advanceUntilIdle` stops as soon as no FOREGROUND task is queued, so a background
        // collector's resumption after `send` is left sitting in the queue and the list
        // reads empty — which looks exactly like a channel that never fired.
        val opened = mutableListOf<String>()
        val job = launch { model.created.collect { opened += it.value } }
        model.create()
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(listOf("group-1"), opened, "the thread would be opened ${opened.size} times")
    }
}
