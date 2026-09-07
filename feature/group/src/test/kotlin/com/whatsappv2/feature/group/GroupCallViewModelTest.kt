package com.whatsappv2.feature.group

import app.cash.turbine.test
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.contacts.Contact
import com.whatsappv2.domain.contacts.SipContact
import com.whatsappv2.domain.engine.CameraAvailability
import com.whatsappv2.domain.engine.NoCameraAvailable
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.testing.FakeContactRepository
import com.whatsappv2.domain.testing.FakeSipAccountRepository
import com.whatsappv2.domain.testing.FakeSipEngine
import com.whatsappv2.domain.usecase.JoinConferenceUseCase
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Building a group and dialling the bridge it meets on (Task 78).
 *
 * The conference itself is [JoinConferenceUseCaseTest]'s and Task 60's. What is asserted
 * here is the page's own contract: the member list is local, the address is what gets
 * called, and a video join with no camera still joins.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupCallViewModelTest {

    private val accounts = FakeSipAccountRepository()
    private val engine = FakeSipEngine()
    private val contacts = FakeContactRepository()
    private val dispatcher = StandardTestDispatcher()

    /** A device that can capture, so a video request stays a video request. */
    private object CameraPresent : CameraAvailability {
        override fun isCameraUsable(): Boolean = true
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(camera: CameraAvailability = CameraPresent) = GroupCallViewModel(
        joinConference = JoinConferenceUseCase(accounts, engine, camera),
        contacts = contacts,
        camera = camera,
    )

    @Test
    fun `the conference address is what gets dialled, not the group name`() = runTest {
        // The point of the DECIDE this page was built under: there is no server-side group
        // to call, so the name is a label and the address is the call (ADR-003, §2.2).
        given()
        val viewModel = viewModel()

        viewModel.onNameChanged("Monday standup")
        viewModel.onAddressChanged("3000")
        runCurrent()
        viewModel.onStartAudioCall()
        runCurrent()

        val dialled = engine.invocations.last {
            it.operation == FakeSipEngine.Operation.JOIN_CONFERENCE
        }.detail
        assertTrue(dialled.contains("3000"), "dialled $dialled")
    }

    @Test
    fun `a group with no members still joins, because a conference does not need them`() = runTest {
        given()
        val viewModel = viewModel()

        viewModel.onAddressChanged("3000")
        runCurrent()

        assertTrue(viewModel.uiState.value.canJoin)
    }

    @Test
    fun `without an address there is nothing to call`() = runTest {
        given()
        val viewModel = viewModel()

        viewModel.onNameChanged("Monday standup")
        runCurrent()

        assertFalse(viewModel.uiState.value.canJoin)
    }

    @Test
    fun `the same person cannot be added to a group twice`() = runTest {
        given()
        val viewModel = viewModel()

        viewModel.onAddMember(contact("Bob", "sip:bob@sip.example.com"))
        viewModel.onAddMember(contact("Bob", "sip:bob@sip.example.com"))
        runCurrent()

        assertEquals(1, viewModel.uiState.value.members.size)
    }

    @Test
    fun `a member can be taken back off the list`() = runTest {
        given()
        val viewModel = viewModel()
        val bob = contact("Bob", "sip:bob@sip.example.com")

        viewModel.onAddMember(bob)
        viewModel.onRemoveMember(bob)
        runCurrent()

        assertTrue(viewModel.uiState.value.members.isEmpty())
    }

    @Test
    fun `a video group call joins as audio when the camera cannot be used, and says so`() = runTest {
        // Downgrade, never refuse (Task 51's second done-when), reported rather than
        // silent — the same rule the dialler's video button follows (Task 74).
        given()
        val viewModel = viewModel(camera = NoCameraAvailable)

        viewModel.onAddressChanged("3000")
        runCurrent()

        viewModel.events.test {
            viewModel.onStartVideoCall()
            runCurrent()

            assertIs<GroupCallEvent.CallPlaced>(awaitItem())
            assertIs<GroupCallEvent.Notice>(awaitItem())
        }

        assertEquals(MediaProfile.AUDIO, engine.activeCalls.value.single().media)
    }

    @Test
    fun `a video group call keeps its video when there is a camera`() = runTest {
        given()
        val viewModel = viewModel()

        viewModel.onAddressChanged("3000")
        runCurrent()
        viewModel.onStartVideoCall()
        runCurrent()

        assertEquals(MediaProfile.AUDIO_VIDEO, engine.activeCalls.value.single().media)
    }

    @Test
    fun `with no account there is nothing to call from, and the page says which`() = runTest {
        val viewModel = viewModel()

        viewModel.onAddressChanged("3000")
        runCurrent()

        viewModel.events.test {
            viewModel.onStartAudioCall()
            runCurrent()

            val refusal = assertIs<GroupCallEvent.Refused>(awaitItem())
            assertTrue(refusal.message.contains("account"), refusal.message)
        }
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun given() {
        accounts.save(ACCOUNT)
        engine.givenRegistered(ACCOUNT)
    }

    private fun contact(name: String, address: String) = SipContact(
        contact = Contact(displayName = name, photoUri = null),
        address = SipUri.parse(address).getOrNull()!!,
    )

    private companion object {
        val ACCOUNT = SipAccount(
            id = AccountId("acct-1"),
            label = "Work",
            username = "alice",
            extension = null,
            authUsername = null,
            password = Secret("hunter22"),
            displayName = null,
            domain = "sip.example.com",
            registrar = null,
            outboundProxy = null,
            port = null,
            transport = Transport.UDP,
            registrationExpirySeconds = 3_600,
            stunServer = null,
            turn = null,
            natPolicy = NatPolicy.DEFAULT,
            srtpPolicy = SrtpPolicy.OPTIONAL,
            codecs = CodecPreferences.DEFAULT,
            isDefault = true,
        )
    }
}
