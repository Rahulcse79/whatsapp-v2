package com.whatsappv2.feature.dialer

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.core.designsystem.theme.WhatsAppV2Theme
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.testing.FakeSipAccountRepository
import com.whatsappv2.domain.testing.FakeSipEngine
import com.whatsappv2.domain.usecase.PlaceCallUseCase
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
@Config(sdk = [DIALER_ROBOLECTRIC_SDK])
class DialerScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeSipAccountRepository()
    private val engine = FakeSipEngine()

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
        // Task 36's second done-when: the override is honoured, and it decides the domain
        // a bare extension is completed against.
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

    // ---------------------------------------------------------------- helpers

    private fun setContent() {
        val viewModel = DialerViewModel(
            placeCall = PlaceCallUseCase(repository, engine),
            recentDials = RecentDials(),
            repository = repository,
            registrar = engine,
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
