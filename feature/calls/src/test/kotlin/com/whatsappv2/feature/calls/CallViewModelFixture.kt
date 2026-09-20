package com.whatsappv2.feature.calls

import app.cash.turbine.ReceiveTurbine
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.engine.ConferenceRoom
import com.whatsappv2.domain.engine.NoCameraAvailable
import com.whatsappv2.domain.engine.NoVideoSurfaces
import com.whatsappv2.domain.engine.VideoSizes
import com.whatsappv2.domain.engine.VideoSurfaceController
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.testing.FakeCallRecorder
import com.whatsappv2.domain.testing.FakeContactRepository
import com.whatsappv2.domain.testing.FakeSipAccountRepository
import com.whatsappv2.domain.testing.FakeSipEngine
import com.whatsappv2.domain.usecase.CallWaitingUseCase
import com.whatsappv2.domain.usecase.ConferenceJoinCoordinator
import com.whatsappv2.domain.usecase.MergeCallsUseCase
import com.whatsappv2.domain.usecase.TransferCallUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before

/**
 * Everything [CallViewModelTest] and [CallViewModelConferenceTest] share: the fakes, the
 * ViewModel under test, and the waiters that read the screen's state.
 *
 * A base class rather than one test class, because detekt's `LargeClass` bound is where
 * the single class ended up, and the conference tests are a subject of their own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class CallViewModelFixture {

    protected val engine = FakeSipEngine()
    protected val contacts = FakeContactRepository()
    protected val accounts = FakeSipAccountRepository()
    protected val recorder = FakeCallRecorder()
    protected val clock = engine.clock
    protected val dispatcher = StandardTestDispatcher()

    /**
     * The conference bridge these tests merge into.
     *
     * Configured by default, because the interesting cases are about *which* topology a
     * merge chooses; the "no room at all" case sets [ConferenceRoom.NONE] itself.
     */
    protected var room = ConferenceRoom.DEFAULT

    /**
     * Surfaces that draw nothing but can be told what shape the picture is.
     *
     * [NoVideoSurfaces] answers [VideoSizes.UNKNOWN] forever, which is the right default
     * and is untestable: the whole point of the field is that it *changes* mid-call.
     */
    protected val surfaces = SizedSurfaces()

    /** A named class, not an object expression: the anonymous type would not be visible to subclasses. */
    protected class SizedSurfaces : VideoSurfaceController by NoVideoSurfaces {
        val sizes = MutableStateFlow(VideoSizes.UNKNOWN)
        override val videoSizes: StateFlow<VideoSizes> get() = sizes
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    protected fun viewModel() = CallViewModel(
        calls = engine,
        media = engine,
        contacts = contacts,
        conferences = engine,
        recorder = recorder,
        // The real use cases over the fake engine, not fakes of their own: the ordering
        // they enforce is the thing worth exercising from here (Tasks 55-57).
        transfers = TransferCallUseCase(engine, accounts),
        callWaiting = CallWaitingUseCase(engine, NoCameraAvailable, ConferenceJoinCoordinator(engine, engine)),
        mergeCalls = MergeCallsUseCase(engine, engine, accounts, room),
        surfaces = surfaces,
        clock = clock,
        accounts = accounts,
    )

    protected suspend fun placeCall(): CallId {
        engine.givenRegistered(ACCOUNT)
        return engine.placeCall(ACCOUNT.id, REMOTE, MediaProfile.AUDIO).getOrNull()!!
    }

    /**
     * Waits for the call to look like [predicate] describes.
     *
     * Not "the next item": the screen's state changes for reasons other than the one under
     * test — a tick, a control, the engine publishing the same call again — and a test that
     * counted emissions would be asserting the shape of the flow rather than the behaviour
     * of the screen.
     */
    protected suspend fun ReceiveTurbine<CallUiState>.awaitDisplay(
        predicate: (CallDisplay) -> Boolean,
    ): CallDisplay {
        while (true) {
            val item = awaitItem()
            if (item is CallUiState.Active && predicate(item.call)) return item.call
        }
    }

    /** [awaitDisplay]'s sibling, for assertions about the screen rather than the call on it. */
    protected suspend fun ReceiveTurbine<CallUiState>.awaitActive(
        predicate: (CallUiState.Active) -> Boolean,
    ): CallUiState.Active {
        while (true) {
            val item = awaitItem()
            if (item is CallUiState.Active && predicate(item)) return item
        }
    }

    protected suspend fun ReceiveTurbine<CallUiState>.awaitFinished(): CallUiState.Finished {
        var item = awaitItem()
        while (item !is CallUiState.Finished) item = awaitItem()
        return item
    }
    protected companion object {
        val REMOTE: SipUri = SipUri.parse("sip:bob@sip.example.com").getOrNull()!!
        val OTHER: SipUri = SipUri.parse("sip:1003@sip.example.com").getOrNull()!!

        /** Two mixed calls is a conference (ADR-009). */
        const val MIN_MIXED_IN_TEST = 2

        /** [CallViewModel]'s `SUBSCRIPTION_TIMEOUT_MILLIS`, which is private to it. */
        const val SUBSCRIPTION_TIMEOUT = 5_000L

        const val MILLIS_PER_SECOND = 1_000L
        const val TICK = 1_100L
        const val TEN_SECONDS = 10_000L
        const val THIRTY_SECONDS = 30_000L
        const val ONE_HOUR = 3_600_000L

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
            transport = Transport.TLS,
            registrationExpirySeconds = 600,
            stunServer = null,
            turn = null,
            natPolicy = NatPolicy.DEFAULT,
            srtpPolicy = SrtpPolicy.OPTIONAL,
            codecs = CodecPreferences.DEFAULT,
            isDefault = true,
        )
    }
}
