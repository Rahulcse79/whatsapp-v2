package com.whatsappv2.feature.settings

import app.cash.turbine.test
import com.whatsappv2.domain.model.AppSettings
import com.whatsappv2.domain.model.DtmfMode
import com.whatsappv2.domain.model.PreferredAudioRoute
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.testing.FakeAppSettingsRepository
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val repository = FakeAppSettingsRepository()
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(traceAvailable: Boolean = true) =
        SettingsViewModel(repository, TraceAvailability { traceAvailable })

    @Test
    fun `a fresh install starts from the documented defaults`() = runTest(dispatcher) {
        val model = viewModel()
        model.uiState.test {
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertEquals(DtmfMode.RFC_4733, state.settings.dtmfMode)
            assertEquals(SrtpPolicy.OPTIONAL, state.settings.defaultSrtpPolicy)
            assertEquals(PreferredAudioRoute.AUTOMATIC, state.settings.preferredAudioRoute)
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
        model.setSipTraceEnabled(true)
        advanceUntilIdle()

        model.uiState.test {
            advanceUntilIdle()
            val state = expectMostRecentItem().settings
            assertEquals(DtmfMode.SIP_INFO, state.dtmfMode)
            assertEquals(SrtpPolicy.MANDATORY, state.defaultSrtpPolicy)
            assertEquals(PreferredAudioRoute.SPEAKER, state.preferredAudioRoute)
            assertTrue(state.sipTraceEnabled)
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
}
