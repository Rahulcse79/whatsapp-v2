package com.whatsappv2.feature.dialer

import app.cash.turbine.test
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.contacts.Contact
import com.whatsappv2.domain.contacts.SipContact
import com.whatsappv2.domain.engine.CameraAvailability
import com.whatsappv2.domain.engine.ConferenceRoom
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
import com.whatsappv2.domain.repository.AccountRepositoryError
import com.whatsappv2.domain.testing.FakeContactRepository
import com.whatsappv2.domain.testing.FakeSipAccountRepository
import com.whatsappv2.domain.testing.FakeSipEngine
import com.whatsappv2.domain.usecase.ConferenceJoinCoordinator
import com.whatsappv2.domain.usecase.MergeCallsUseCase
import com.whatsappv2.domain.usecase.PlaceCallUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
     * A second ViewModel over the same repository is "leave the dialer and come back",
     * with no navigation library in the test.
     *
     * There is nothing else to carry across: the selected account lives in the store now,
     * which is the whole point of the change — a `SavedStateHandle` was what the per-call
     * override needed to survive that round trip, and the override is gone.
     */
    private fun viewModel(camera: CameraAvailability = CameraPresent) = DialerViewModel(
        placeCall = PlaceCallUseCase(repository, engine, camera, engine),
        recentDials = recents,
        contacts = contacts,
        camera = camera,
        repository = repository,
        registrar = engine,
        joins = ConferenceJoinCoordinator(engine, engine),
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
    fun `the chosen account decides which one places the call`() = runTest {
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

    // ------------------------------------------------- choosing an account

    @Test
    fun `choosing an account here makes it the default, as choosing one in the bar does`() =
        runTest {
            // The reported defect. The dialler kept a per-call override of its own, so the
            // extension picked here was invisible to the rest of the app: the Chats
            // indicator went on showing the account the user had not chosen, and the two
            // screens disagreed about which extension the phone was on.
            given(work, home)
            val viewModel = ready(viewModel())

            viewModel.onAccountSelected(home.id)
            runCurrent()

            assertEquals(
                home.id,
                repository.observeAccounts().first().single { it.isDefault }.id,
                "the store is what every other screen reads",
            )
            assertEquals(home.id, viewModel.uiState.value.selectedAccount?.id)
            assertTrue(viewModel.uiState.value.selectionIsDefault)
        }

    @Test
    fun `the previous default is cleared, so there is never more than one`() = runTest {
        // One call rather than two, because the repository does both halves in a single
        // transaction — an instant with two defaults is an instant where "which account
        // does this call leave on" has two answers.
        given(work, home)
        val viewModel = ready(viewModel())

        viewModel.onAccountSelected(home.id)
        runCurrent()

        assertEquals(
            listOf(home.id),
            repository.observeAccounts().first().filter { it.isDefault }.map { it.id },
        )
    }

    @Test
    fun `the choice survives a placed call`() = runTest {
        // The behaviour that was deliberately the other way round, and is the bug the user
        // reported: the override was cleared on success, so a call placed from the home
        // account silently put the dialler back on work.
        given(work, home)
        val viewModel = ready(viewModel())

        viewModel.onAccountSelected(home.id)
        viewModel.onInputChanged("1001")
        runCurrent()
        viewModel.onCall()
        runCurrent()

        assertEquals(home.id, viewModel.uiState.value.selectedAccount?.id)
        assertEquals("", viewModel.uiState.value.input, "the input is cleared; the account is not")
    }

    @Test
    fun `the choice survives leaving the screen and coming back`() = runTest {
        // It used to need a SavedStateHandle to manage this, and managed it only as far as
        // the next placed call. Living in the store, it survives navigation, a call, and
        // process death without this ViewModel holding anything.
        given(work, home)
        val first = ready(viewModel())

        first.onAccountSelected(home.id)
        runCurrent()

        val second = ready(viewModel())
        runCurrent()

        assertEquals(home.id, second.uiState.value.selectedAccount?.id)
    }

    @Test
    fun `an account that is gone by the time it is picked says so and is not shown as chosen`() =
        runTest {
            // Deleted under the open menu. The echo must not stand over a default that
            // never changed: a card naming one account while calls leave on another is the
            // §6 lie in the most expensive place to tell it.
            given(work, home)
            val viewModel = ready(viewModel())
            repository.nextFailure = AccountRepositoryError.NotFound

            viewModel.events.test {
                viewModel.onAccountSelected(home.id)
                runCurrent()

                assertEquals(DialerViewModel.ACCOUNT_GONE, (awaitItem() as DialerEvent.Refused).message)
            }

            assertEquals(work.id, viewModel.uiState.value.selectedAccount?.id, "still the real default")
        }

    @Test
    fun `every account in the picker carries its own registration state`() = runTest {
        // The picker is where somebody looks before placing a call, and an unregistered
        // account cannot place one. The rows must therefore be individually honest rather
        // than inheriting the selected account's state.
        given(work)
        given(home, registered = false)
        val viewModel = ready(viewModel())

        val rows = viewModel.uiState.value.accounts.associateBy { it.id }

        assertTrue(rows.getValue(work.id).isRegistered)
        assertTrue(!rows.getValue(home.id).isRegistered)
        assertTrue(rows.getValue(work.id).isDefault, "and which one is selected")
        assertTrue(!rows.getValue(home.id).isDefault)
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
    fun `a refused call keeps the selection, because the user is about to try again`() = runTest {
        // Nothing about a call that failed changes which extension the phone is on, and the
        // user is about to press the button again.
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
