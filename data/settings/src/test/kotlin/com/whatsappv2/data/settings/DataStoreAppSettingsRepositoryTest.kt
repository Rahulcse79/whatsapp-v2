package com.whatsappv2.data.settings

import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.whatsappv2.core.common.logging.NoOpLogger
import com.whatsappv2.domain.model.AppSettings
import com.whatsappv2.domain.model.DtmfMode
import com.whatsappv2.domain.model.PreferredAudioRoute
import com.whatsappv2.domain.model.SrtpPolicy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Settings against a real DataStore.
 *
 * Task 23's first done-when is "settings persist across process death", and the only way
 * to show that is to write with one repository instance and read with another - an
 * in-memory fake would pass while proving nothing about storage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class DataStoreAppSettingsRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun repository() = DataStoreAppSettingsRepository(context, NoOpLogger)

    @After
    fun tearDown() {
        File(context.filesDir, "datastore").deleteRecursively()
    }

    @Test
    fun `a fresh install reads the documented defaults`() = runTest {
        assertEquals(AppSettings.DEFAULT, repository().currentSettings())
    }

    @Test
    fun `the SIP trace is off before anyone turns it on`() = runTest {
        // Tracing writes signalling to the device log, so it must be something a user
        // enables, never something they discover was already on (§7, DoD 12).
        assertFalse(repository().currentSettings().sipTraceEnabled)
    }

    @Test
    fun `settings survive a new repository instance`() = runTest {
        // Standing in for process death: the second instance shares no state with the
        // first except what actually reached disk.
        repository().apply {
            setDtmfMode(DtmfMode.SIP_INFO)
            setDefaultSrtpPolicy(SrtpPolicy.MANDATORY)
            setPreferredAudioRoute(PreferredAudioRoute.SPEAKER)
            setSipTraceEnabled(true)
        }

        val reloaded = repository().currentSettings()

        assertEquals(DtmfMode.SIP_INFO, reloaded.dtmfMode)
        assertEquals(SrtpPolicy.MANDATORY, reloaded.defaultSrtpPolicy)
        assertEquals(PreferredAudioRoute.SPEAKER, reloaded.preferredAudioRoute)
        assertTrue(reloaded.sipTraceEnabled)
    }

    @Test
    fun `a change reaches an existing observer`() = runTest {
        // The in-call screen must not keep using an old audio route because it read the
        // value once on entry.
        val repository = repository()

        repository.observeSettings().test {
            assertEquals(PreferredAudioRoute.AUTOMATIC, awaitItem().preferredAudioRoute)

            repository.setPreferredAudioRoute(PreferredAudioRoute.EARPIECE)
            assertEquals(PreferredAudioRoute.EARPIECE, awaitItem().preferredAudioRoute)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `changing one setting leaves the others alone`() = runTest {
        // Individual setters exist precisely so two screens changing different settings
        // cannot overwrite each other.
        val repository = repository()
        repository.setDtmfMode(DtmfMode.SIP_INFO)
        repository.setPreferredAudioRoute(PreferredAudioRoute.SPEAKER)

        val settings = repository.currentSettings()
        assertEquals(DtmfMode.SIP_INFO, settings.dtmfMode)
        assertEquals(PreferredAudioRoute.SPEAKER, settings.preferredAudioRoute)
        assertEquals(AppSettings.DEFAULT.defaultSrtpPolicy, settings.defaultSrtpPolicy)
    }

    @Test
    fun `every enum value round-trips`() = runTest {
        val repository = repository()

        for (mode in DtmfMode.entries) {
            repository.setDtmfMode(mode)
            assertEquals(mode, repository.currentSettings().dtmfMode)
        }
        for (policy in SrtpPolicy.entries) {
            repository.setDefaultSrtpPolicy(policy)
            assertEquals(policy, repository.currentSettings().defaultSrtpPolicy)
        }
        for (route in PreferredAudioRoute.entries) {
            repository.setPreferredAudioRoute(route)
            assertEquals(route, repository.currentSettings().preferredAudioRoute)
        }
    }
}
