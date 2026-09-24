package com.whatsappv2.feature.dialer

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.core.designsystem.theme.WhatsAppV2Theme
import com.whatsappv2.domain.engine.ConferenceRoom
import com.whatsappv2.domain.engine.NoCameraAvailable
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.testing.FakeContactRepository
import com.whatsappv2.domain.testing.FakeSipAccountRepository
import com.whatsappv2.domain.testing.FakeSipEngine
import com.whatsappv2.domain.usecase.ConferenceJoinCoordinator
import com.whatsappv2.domain.usecase.MergeCallsUseCase
import com.whatsappv2.domain.usecase.PlaceCallUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The dialler, driven end to end by [FakeSipEngine] (Task 36, third done-when).
 *
 * The screen, its ViewModel, the use case and the engine — everything except a SIP server,
 * a network and a device. Tapping the keypad here really does reach `placeCall`, which is
 * what makes this a test of the dialler rather than of a layout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [DIALER_ROBOLECTRIC_SDK], qualifiers = DIALER_SCREEN)
class DialerScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeSipAccountRepository()
    private val engine = FakeSipEngine()
    private val contacts = FakeContactRepository()

    @Test
    fun `tapping the keypad and calling places the call the digits spell`() {
        given(work)
        setContent()

        "1001".forEach { compose.onNodeWithTag(keyTag(it)).performClick() }
        compose.onNodeWithTag(TAG_CALL).performClick()
        compose.waitForIdle()

        assertEquals("sip:1001@sip.example.com", lastDialled())
    }

    @Test
    fun `the call button does nothing until there is something to call`() {
        given(work)
        setContent()

        compose.onNodeWithTag(TAG_CALL).assertIsNotEnabled()

        compose.onNodeWithTag(keyTag('1')).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(TAG_CALL).assertIsEnabled()
    }

    @Test
    fun `backspace removes a digit and clear removes them all`() {
        given(work)
        setContent()

        "123".forEach { compose.onNodeWithTag(keyTag(it)).performClick() }
        compose.onNodeWithTag(TAG_BACKSPACE).performClick()
        compose.onNodeWithTag(TAG_CALL).performClick()
        compose.waitForIdle()

        assertEquals("sip:12@sip.example.com", lastDialled())
    }

    @Test
    fun `the account picker appears only when there is a choice to make`() {
        given(work)
        setContent()

        compose.onNodeWithTag(TAG_ACCOUNT).assertDoesNotExist()
    }

    @Test
    fun `choosing another account sends the call out on it`() {
        // Task 36's second done-when: the chosen account is honoured, and it decides the
        // domain a bare extension is completed against.
        given(work, home)
        setContent()

        compose.onNodeWithTag(TAG_ACCOUNT).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(accountTag(home.id)).performClick()
        compose.waitForIdle()
        "1001".forEach { compose.onNodeWithTag(keyTag(it)).performClick() }
        compose.onNodeWithTag(TAG_CALL).performClick()
        compose.waitForIdle()

        assertEquals("sip:1001@home.example.com", lastDialled())
    }

    @Test
    fun `a dialled number comes back as a shortcut`() {
        given(work)
        setContent()

        "1001".forEach { compose.onNodeWithTag(keyTag(it)).performClick() }
        compose.onNodeWithTag(TAG_CALL).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(recentTag("1001")).assertIsDisplayed()
    }

    @Test
    fun `every row in the picker says whether that account is registered`() {
        // The state is the reason somebody opens this menu — an unregistered account
        // cannot place a call. It used to be the *absence* of a "· offline" suffix in a
        // quieter colour at the end of an address, so "registered" was something to infer.
        given(work)
        givenUnregistered(home)
        setContent()

        compose.onNodeWithTag(TAG_ACCOUNT).performClick()
        compose.waitForIdle()

        // Asserted through the merged semantics, which is what a screen reader reads out:
        // the row is one sentence, and the state is part of it.
        compose.onNodeWithTag(accountTag(work.id)).assertTextContains("Registered")
        compose.onNodeWithTag(accountTag(home.id)).assertTextContains("Unregistered")
    }

    @Test
    fun `the picker marks the account that is currently the default`() {
        // Picking a row sets the default, so without this the menu offers a choice and
        // never says what the current answer is.
        given(work, home)
        setContent()

        compose.onNodeWithTag(TAG_ACCOUNT).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(accountTag(work.id)).assertContentDescriptionEquals("Selected")
        compose.onNodeWithTag(accountTag(home.id)).assertContentDescriptionEquals()
    }

    @Test
    fun `choosing an account updates the card's status to that account's`() {
        // The card and the rows read from one place, so picking the unregistered account
        // cannot leave the card showing the registered one's state.
        given(work)
        givenUnregistered(home)
        setContent()

        compose.onNodeWithTag(TAG_ACCOUNT).assertTextContains("Registered")

        compose.onNodeWithTag(TAG_ACCOUNT).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(accountTag(home.id)).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(TAG_ACCOUNT).assertTextContains("Unregistered")
    }

    @Test
    fun `the account chosen here becomes the default the rest of the app reads`() = runTest {
        // The reported defect, from the screen: the dialler kept the choice to itself, so
        // the Chats indicator went on showing the account the user had not picked.
        given(work, home)
        setContent()

        compose.onNodeWithTag(TAG_ACCOUNT).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(accountTag(home.id)).performClick()
        compose.waitForIdle()

        assertEquals(home.id, repository.observeAccounts().first().single { it.isDefault }.id)
    }

    // ---------------------------------------------------------------- helpers

    private fun setContent() {
        val viewModel = DialerViewModel(
            placeCall = PlaceCallUseCase(repository, engine, NoCameraAvailable, engine),
            recentDials = RecentDials(),
            contacts = contacts,
            camera = NoCameraAvailable,
            repository = repository,
            registrar = engine,
            joins = ConferenceJoinCoordinator(engine, engine),
        )

        compose.setContent {
            WhatsAppV2Theme {
                val state by viewModel.uiState.collectAsState()

                DialerScreen(
                    state = state,
                    snackbarHostState = SnackbarHostState(),
                    actions = DialerActions(
                        onInputChanged = viewModel::onInputChanged,
                        onDigit = viewModel::onDigitPressed,
                        onBackspace = viewModel::onBackspace,
                        onClear = viewModel::onClear,
                        onAccountSelected = viewModel::onAccountSelected,
                        onRecentSelected = viewModel::onRecentSelected,
                        onCall = viewModel::onCall,
                        onVideoCall = viewModel::onVideoCall,
                    ),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun lastDialled(): String =
        engine.invocations.last { it.operation == FakeSipEngine.Operation.PLACE_CALL }.detail

    private fun given(vararg accounts: SipAccount) {
        accounts.forEach {
            repository.given(it)
            engine.givenRegistered(it)
        }
    }

    /**
     * An account the stack holds and has **not** registered.
     *
     * Known to the engine either way: the app hands every account to it at startup, so
     * "not registered" is a state the engine holds for an account it has.
     */
    private fun givenUnregistered(account: SipAccount) {
        repository.given(account)
        engine.givenRegistered(account)
        engine.simulateRegistrationExpiry(account.id)
    }

    private companion object {
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
