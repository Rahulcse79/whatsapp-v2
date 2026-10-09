package com.whatsappv2.feature.settings

import app.cash.turbine.test
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.chat.ChatSession
import com.whatsappv2.domain.chat.CoralServerUrl
import com.whatsappv2.domain.model.AppSettings
import com.whatsappv2.domain.model.CallHistoryRetention
import com.whatsappv2.domain.model.DtmfMode
import com.whatsappv2.domain.model.PreferredAudioRoute
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.ThemeMode
import com.whatsappv2.domain.testing.FakeAppSettingsRepository
import com.whatsappv2.domain.testing.FakeChatSessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val repository = FakeAppSettingsRepository()
    private val chatSessions = FakeChatSessionRepository()
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(traceAvailable: Boolean = true) =
        SettingsViewModel(repository, chatSessions, TraceAvailability { traceAvailable })

    @Test
    fun `a fresh install starts from the documented defaults`() = runTest(dispatcher) {
        val model = viewModel()
        model.uiState.test {
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertEquals(DtmfMode.RFC_4733, state.settings.dtmfMode)
            // DISABLED since 2026-09-10: OPTIONAL failed every outgoing call on FreeSWITCH.
            assertEquals(SrtpPolicy.DISABLED, state.settings.defaultSrtpPolicy)
            assertEquals(PreferredAudioRoute.AUTOMATIC, state.settings.preferredAudioRoute)
            assertEquals(CallHistoryRetention.DEFAULT, state.settings.callHistoryRetention)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `there is no chat account row until somebody signs in`() = runTest(dispatcher) {
        val model = viewModel()
        model.uiState.test {
            advanceUntilIdle()
            // Its only action is Sign out, so with nobody signed in the row is absent
            // rather than present and inert.
            assertNull(expectMostRecentItem().chatAccount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a signed-in account shows its identity and the server it came from`() = runTest(dispatcher) {
        chatSessions.givenSignedIn(
            ChatSession(
                userId = "sample-user",
                displayName = "Sample User",
                token = Secret("a-token"),
                expiresAtMs = null,
                deviceId = "BF6625949EAA4D5F94CAA18641BE8E74",
            ),
        )
        val model = viewModel()

        model.uiState.test {
            advanceUntilIdle()
            val account = assertNotNull(expectMostRecentItem().chatAccount)
            assertEquals("Sample User", account.identity)
            assertEquals(CoralServerUrl.DEFAULT.origin, account.serverOrigin)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `signing out from settings clears the row and keeps the server URL - decision D2`() = runTest(dispatcher) {
        chatSessions.givenSignedIn(
            ChatSession(
                userId = "sample-user",
                displayName = null,
                token = Secret("a-token"),
                expiresAtMs = null,
                deviceId = "BF6625949EAA4D5F94CAA18641BE8E74",
            ),
        )
        val model = viewModel()

        model.uiState.test {
            advanceUntilIdle()
            model.signOutOfChat()
            advanceUntilIdle()

            assertNull(expectMostRecentItem().chatAccount)
            // The same repository call the Chats overflow makes, so the two cannot differ.
            assertEquals(1, chatSessions.signOutCount)
            assertEquals(CoralServerUrl.DEFAULT, chatSessions.currentServerUrl())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the SIP trace is off on a fresh install`() = runTest(dispatcher) {
        // Task 23 done-when. Tracing writes signalling to the device log, so it must be
        // something a user turns on, never something they discover was already on.
        assertFalse(AppSettings.DEFAULT.sipTraceEnabled)

        val model = viewModel()
        model.uiState.test {
            advanceUntilIdle()
            assertFalse(expectMostRecentItem().settings.sipTraceEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the trace toggle is absent when the build does not allow it`() = runTest(dispatcher) {
        // Absent, not disabled: a disabled control invites someone to make it enableable.
        val model = viewModel(traceAvailable = false)
        model.uiState.test {
            advanceUntilIdle()
            assertFalse(expectMostRecentItem().traceToggleAvailable)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `each setting can be changed and is observed`() = runTest(dispatcher) {
        val model = viewModel()

        model.setDtmfMode(DtmfMode.SIP_INFO)
        model.setDefaultSrtpPolicy(SrtpPolicy.MANDATORY)
        model.setPreferredAudioRoute(PreferredAudioRoute.SPEAKER)
        model.setThemeMode(ThemeMode.LIGHT)
        model.setSipTraceEnabled(true)
        model.setUpdateCallerIdOnTransfer(true)
        model.setCallHistoryRetention(CallHistoryRetention.ofDays(NINETY))
        advanceUntilIdle()

        model.uiState.test {
            advanceUntilIdle()
            val state = expectMostRecentItem().settings
            assertEquals(DtmfMode.SIP_INFO, state.dtmfMode)
            assertEquals(SrtpPolicy.MANDATORY, state.defaultSrtpPolicy)
            assertEquals(PreferredAudioRoute.SPEAKER, state.preferredAudioRoute)
            assertEquals(ThemeMode.LIGHT, state.themeMode)
            assertTrue(state.sipTraceEnabled)
            assertTrue(state.updateCallerIdOnTransfer)
            assertEquals(NINETY, state.callHistoryRetention.days)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a change reaches every observer, not just the one that made it`() = runTest(dispatcher) {
        // The in-call screen must not keep using an old audio route because it read the
        // value on entry.
        val first = viewModel()
        val second = viewModel()

        first.setPreferredAudioRoute(PreferredAudioRoute.EARPIECE)
        advanceUntilIdle()

        second.uiState.test {
            advanceUntilIdle()
            assertEquals(PreferredAudioRoute.EARPIECE, expectMostRecentItem().settings.preferredAudioRoute)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private companion object {
        const val NINETY = 90
    }
}
