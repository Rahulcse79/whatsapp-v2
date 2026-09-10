package com.whatsappv2.feature.dialer

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.contacts.Contact
import com.whatsappv2.domain.contacts.SipContact
import com.whatsappv2.domain.engine.CameraAvailability
import com.whatsappv2.domain.engine.NoCameraAvailable
import com.whatsappv2.domain.engine.SipError
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
import com.whatsappv2.domain.usecase.PlaceCallUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The dialer's behaviour, with no SIP server anywhere (Task 36).
 *
 * The resolution rules themselves — a bare extension against the account's domain, a full
 * URI left alone — belong to `PlaceCallUseCase` and are asserted there. What is asserted
 * here is the dialler's own job: which account a call goes out on, and what the user is
 * told when it does not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DialerViewModelTest {

    private val repository = FakeSipAccountRepository()
    private val engine = FakeSipEngine()
    private val recents = RecentDials()

    /** A device that can capture, so a video request stays a video request. */
    private object CameraPresent : CameraAvailability {
        override fun isCameraUsable(): Boolean = true
    }

    private val contacts = FakeContactRepository()
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /**
     * The destination's saved state.
     *
     * Held by the test rather than created per ViewModel, because that is what Compose
     * Navigation does: the handle belongs to the back-stack entry and outlives the
     * ViewModel scoped to it. Building a second [DialerViewModel] over the same handle is
     * exactly "leave the dialer and come back", with no navigation library in the test.
     */
    private var savedState = SavedStateHandle()

    private fun viewModel(camera: CameraAvailability = CameraPresent) = DialerViewModel(
        placeCall = PlaceCallUseCase(repository, engine, camera, engine),
        recentDials = recents,
        contacts = contacts,
        camera = camera,
        repository = repository,
        savedState = savedState,
        registrar = engine,
    )

    @Test
    fun `a bare extension is dialled through the default account`() = runTest {
        // Task 36's first done-when, half one. The domain comes from the account, which is
        // the only reason `1001` means anything at all.
        given(work)
        val viewModel = ready(viewModel())

        "1001".forEach(viewModel::onDigitPressed)
        runCurrent()
        assertEquals("1001", viewModel.uiState.value.input)

        viewModel.onCall()
        runCurrent()

        assertEquals("sip:1001@sip.example.com", lastDialled())
    }

    @Test
    fun `the video button places a video call`() = runTest {
        // Task 74. The same call, one different profile — the button is not a separate
        // path, which is what keeps account resolution and target resolution shared.
        given(work)
        val viewModel = ready(viewModel())

        viewModel.onInputChanged("1001")
        runCurrent()
        viewModel.onVideoCall()
        runCurrent()

        assertEquals(MediaProfile.AUDIO_VIDEO, engine.activeCalls.value.single().media)
    }

    @Test
    fun `a video call with no usable camera is placed as audio and said so`() = runTest {
        // Downgrade, never refuse (Task 51's second done-when). The notice matters as much
        // as the downgrade: silently placing a different kind of call than the one asked
        // for is how a working button comes to look broken.
        given(work)
        val viewModel = ready(viewModel(camera = NoCameraAvailable))

        viewModel.events.test {
            viewModel.onInputChanged("1001")
            runCurrent()
            viewModel.onVideoCall()
            runCurrent()

            assertIs<DialerEvent.CallPlaced>(awaitItem())
            assertEquals(DialerViewModel.NO_CAMERA, (awaitItem() as DialerEvent.Notice).message)
        }

        assertEquals(MediaProfile.AUDIO, engine.activeCalls.value.single().media)
    }

    @Test
    fun `a full URI is dialled as written`() = runTest {
        // Task 36's first done-when, half two: a call to another domain must not be
        // silently rewritten to this account's.
        given(work)
        val viewModel = ready(viewModel())

        viewModel.onInputChanged("sip:carol@other.example.com")
        runCurrent()
        viewModel.onCall()
        runCurrent()

        assertEquals("sip:carol@other.example.com", lastDialled())
    }

    @Test
    fun `the per-call override decides which account places the call`() = runTest {
        // Task 36's second done-when. It also decides the domain a bare extension is
        // completed against, which is the part that is easy to get wrong.
        given(work, home)
        val viewModel = ready(viewModel())

        viewModel.onAccountSelected(home.id)
        viewModel.onInputChanged("1001")
        runCurrent()
        viewModel.onCall()
        runCurrent()

        assertEquals("sip:1001@home.example.com", lastDialled())
    }

    @Test
    fun `the override is shown while it applies and cleared once the call is placed`() = runTest {
        // Per call, not a setting: a one-off call from the work account must not silently
        // become every later call's account too.
        given(work, home)
        val viewModel = ready(viewModel())

        viewModel.onAccountSelected(home.id)
        runCurrent()
        assertTrue(viewModel.uiState.value.isOverridden)
        assertEquals(home.id, viewModel.uiState.value.selectedAccount?.id)

        viewModel.onInputChanged("1001")
        runCurrent()
        viewModel.onCall()
        runCurrent()

        assertTrue(!viewModel.uiState.value.isOverridden)
        assertEquals(work.id, viewModel.uiState.value.selectedAccount?.id, "back to the default")
    }

    @Test
    fun `the account on screen is the one that places the call, default or not`() = runTest {
        // The dialler names the selected account explicitly unless it is the default, so
        // the call goes out on what the user can see. The repository promotes a lone
        // account to default (Task 22), which is why this reads as belt and braces — and
        // why the belt is worth having: the screen must never show one account and dial
        // from another.
        given(account(id = "only", label = "Only", domain = "only.example.com", isDefault = false))
        val viewModel = ready(viewModel())

        viewModel.onInputChanged("1001")
        runCurrent()
        viewModel.onCall()
        runCurrent()

        assertEquals("sip:1001@only.example.com", lastDialled())
    }

    @Test
    fun `with no accounts there is nothing to call from, and the dialler says so`() = runTest {
        val viewModel = ready(viewModel())

        viewModel.events.test {
            viewModel.onInputChanged("1001")
            runCurrent()
            viewModel.onCall()
            runCurrent()

            // canPlaceCall is false with no account at all, so nothing is attempted and
            // no INVITE is invented for an identity that does not exist.
            expectNoEvents()
            assertTrue(!viewModel.uiState.value.canPlaceCall)
        }
    }

    @Test
    fun `an unregistered account is registered and the call goes through`() = runTest {
        // Changed deliberately (Task 76). This used to assert a refusal saying "that account
        // is not registered yet", which told the user to fix by hand something the app can
        // fix in well under a second — against the reference server a REGISTER completes in
        // 57 ms. PlaceCallUseCase now registers first and dials, so the dialler's job here
        // is to show a placed call rather than a refusal.
        given(work, registered = false)
        val viewModel = ready(viewModel())

        viewModel.events.test {
            viewModel.onInputChanged("1001")
            runCurrent()
            viewModel.onCall()
            runCurrent()

            assertIs<DialerEvent.CallPlaced>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an account that will not register is refused in words that name what happened`() =
        runTest {
            // The other half of Task 76's decision. The recovery is bounded, and when it runs
            // out the user is told what actually happened — the app tried to reach the server
            // and could not — rather than the bare "not registered" that was true before the
            // attempt and misleading after it.
            given(work, registered = false)
            engine.alwaysFail(FakeSipEngine.Operation.REGISTER, SipError.Timeout)
            val viewModel = ready(viewModel())

            viewModel.events.test {
                viewModel.onInputChanged("1001")
                runCurrent()
                viewModel.onCall()
                runCurrent()

                val event = assertIs<DialerEvent.Refused>(awaitItem())
                assertEquals("Could not reach the server for that account", event.message)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a call refused because the phone is on another call says exactly that`() = runTest {
        // §3: the cellular call is honoured, and the user is told why rather than left
        // with a call that silently did not happen.
        given(work)
        engine.failNext(FakeSipEngine.Operation.PLACE_CALL, SipError.CallNotPermitted)
        val viewModel = ready(viewModel())

        viewModel.events.test {
            viewModel.onInputChanged("1001")
            runCurrent()
            viewModel.onCall()
            runCurrent()

            val event = assertIs<DialerEvent.Refused>(awaitItem())
            assertEquals("Your phone is on another call", event.message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `what cannot be dialled is refused before the engine is asked`() = runTest {
        given(work)
        val viewModel = ready(viewModel())

        viewModel.events.test {
            viewModel.onInputChanged("sip:@@@")
            runCurrent()
            viewModel.onCall()
            runCurrent()

            assertIs<DialerEvent.InvalidTarget>(awaitItem())
            assertTrue(engine.invocations.none { it.operation == FakeSipEngine.Operation.PLACE_CALL })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a placed call is offered as a shortcut afterwards`() = runTest {
        given(work)
        val viewModel = ready(viewModel())

        viewModel.onInputChanged("1001")
        runCurrent()
        viewModel.onCall()
        runCurrent()

        assertEquals(listOf("1001"), viewModel.uiState.value.recent)
    }

    @Test
    fun `a call that failed is still offered as a shortcut, because that is the one to redial`() =
        runTest {
            given(work)
            engine.failNext(FakeSipEngine.Operation.PLACE_CALL, SipError.Busy(BUSY_HERE))
            val viewModel = ready(viewModel())

            viewModel.onInputChanged("1001")
            runCurrent()
            viewModel.onCall()
            runCurrent()

            assertEquals(listOf("1001"), viewModel.uiState.value.recent)
            // What was typed stays put: the user is about to try again.
            assertEquals("1001", viewModel.uiState.value.input)
        }

    @Test
    fun `the selected account survives leaving the dialler and coming back`() = runTest {
        // The reported defect. Compose Navigation scopes a ViewModel to its destination, so
        // walking to the call screen and back destroyed the override and the selection
        // reverted to the default account after the user had deliberately picked another.
        given(work, home)
        val first = ready(viewModel())

        first.onAccountSelected(home.id)
        runCurrent()
        assertEquals(home.id, first.uiState.value.selectedAccount?.id)

        // A new ViewModel over the same back-stack entry: the screen, left and re-entered.
        val second = ready(viewModel())

        assertEquals(home.id, second.uiState.value.selectedAccount?.id, "still the chosen one")
        assertTrue(second.uiState.value.isOverridden)
    }

    @Test
    fun `the selection does NOT survive a placed call, so a per-call override stays per call`() =
        runTest {
            // The trap in the fix. Two lifetimes are being conflated: surviving navigation is
            // what was asked for, surviving a placed call is what the design deliberately
            // refuses. Persisting it somewhere `place` does not clear would turn a one-off
            // call from the work account into every later call's account — a worse bug than
            // the one being fixed.
            given(work, home)
            val first = ready(viewModel())

            first.onAccountSelected(home.id)
            first.onInputChanged("1001")
            runCurrent()
            first.onCall()
            runCurrent()

            assertTrue(!first.uiState.value.isOverridden)

            val second = ready(viewModel())
            assertEquals(
                work.id,
                second.uiState.value.selectedAccount?.id,
                "back to the default, on the screen as well as in the handle",
            )
        }

    @Test
    fun `a refused call keeps the selection, because the user is about to try again`() = runTest {
        // The override is cleared on success only, and the saved handle has to agree with
        // that rather than having a rule of its own.
        given(work, home)
        engine.failNext(FakeSipEngine.Operation.PLACE_CALL, SipError.Busy(BUSY_HERE))
        val first = ready(viewModel())

        first.onAccountSelected(home.id)
        first.onInputChanged("1001")
        runCurrent()
        first.onCall()
        runCurrent()

        val second = ready(viewModel())
        assertEquals(home.id, second.uiState.value.selectedAccount?.id)
    }

    @Test
    fun `backspace and clear edit what was typed`() = runTest {
        val viewModel = ready(viewModel())

        viewModel.onInputChanged("100")
        viewModel.onBackspace()
        runCurrent()
        assertEquals("10", viewModel.uiState.value.input)

        viewModel.onClear()
        runCurrent()
        assertEquals("", viewModel.uiState.value.input)
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Starts the ViewModel's state flow and returns it, settled.
     *
     * `stateIn(WhileSubscribed)` produces nothing until something collects, so a test that
     * only reads `.value` reads the initial value forever. Collecting in the background
     * scope keeps it live for the length of the test and lets each assertion read the
     * current state directly rather than counting emissions.
     */
    // ---------------------------------------------------------------- contacts (Task 50)

    @Test
    fun `contacts with a SIP address are offered, and narrow as the user types`() = runTest {
        given(work)
        contacts.given(uri("sip:bob@sip.example.com"), name = "Bob Smith")
        contacts.given(uri("sip:carol@sip.example.com"), name = "Carol Jones")
        val viewModel = ready(viewModel())

        assertEquals(2, viewModel.uiState.value.contacts.size)

        viewModel.onInputChanged("carol")
        runCurrent()

        assertEquals(listOf("Carol Jones"), viewModel.uiState.value.contacts.map { it.contact.displayName })
    }

    @Test
    fun `a contact with no SIP address is not offered, because it cannot be called`() = runTest {
        // The picker's whole job is a name to tap and an address to dial. Someone this app
        // cannot reach is a dead end dressed up as a choice.
        given(work)
        val viewModel = ready(viewModel())

        assertTrue(viewModel.uiState.value.contacts.isEmpty())
    }

    @Test
    fun `calling a contact dials their address, not whatever was typed before`() = runTest {
        // onCall reads uiState, which lags the entry by a dispatch, so placing through it
        // would dial the previous input. This is the case that catches that.
        given(work)
        val bob = uri("sip:bob@sip.example.com")
        contacts.given(bob, name = "Bob Smith")
        val viewModel = ready(viewModel())
        viewModel.onInputChanged("999")
        runCurrent()

        viewModel.onContactSelected(SipContact(Contact("Bob Smith", null), bob))
        runCurrent()

        assertEquals("sip:bob@sip.example.com", lastDialled())
    }

    @Test
    fun `calling a contact goes out on the account the screen is showing`() = runTest {
        // Task 50's second done-when: the override the user chose still applies.
        given(work, home)
        val bob = uri("sip:bob@sip.example.com")
        contacts.given(bob, name = "Bob Smith")
        val viewModel = ready(viewModel())
        viewModel.onAccountSelected(home.id)
        runCurrent()

        viewModel.onContactSelected(SipContact(Contact("Bob Smith", null), bob))
        runCurrent()

        assertEquals(home.id, engine.activeCalls.value.single().accountId)
    }

    private fun uri(value: String): SipUri = SipUri.parse(value).getOrNull()!!

    private fun TestScope.ready(viewModel: DialerViewModel): DialerViewModel {
        backgroundScope.launch { viewModel.uiState.collect { } }
        runCurrent()
        return viewModel
    }

    private fun lastDialled(): String =
        engine.invocations.last { it.operation == FakeSipEngine.Operation.PLACE_CALL }.detail

    private fun given(vararg accounts: SipAccount, registered: Boolean = true) {
        accounts.forEach { account ->
            repository.given(account)
            // Known to the engine either way. The app hands every account to the stack at
            // startup, so "not registered" is a state the engine holds for an account it
            // has — an account it has never heard of is UnknownAccount, a different error
            // reaching the user as a different sentence.
            engine.givenRegistered(account)
            if (!registered) engine.simulateRegistrationExpiry(account.id)
        }
    }

    private companion object {
        /** 486, named so the assertion reads as intent rather than arithmetic. */
        const val BUSY_HERE = 486

        val work = account(id = "work", label = "Work", domain = "sip.example.com", isDefault = true)
        val home = account(id = "home", label = "Home", domain = "home.example.com", isDefault = false)

        fun account(
            id: String,
            label: String,
            domain: String,
            isDefault: Boolean,
        ) = SipAccount(
            id = AccountId(id),
            label = label,
            username = "alice",
            extension = null,
            authUsername = null,
            password = Secret("hunter22"),
            displayName = null,
            domain = domain,
            registrar = null,
            outboundProxy = null,
            port = null,
            transport = Transport.TLS,
            registrationExpirySeconds = 600,
            stunServer = null,
            turn = null,
            natPolicy = NatPolicy.DEFAULT,
            srtpPolicy = SrtpPolicy.OPTIONAL,
            codecs = CodecPreferences.DEFAULT,
            isDefault = isDefault,
        )
    }
}
