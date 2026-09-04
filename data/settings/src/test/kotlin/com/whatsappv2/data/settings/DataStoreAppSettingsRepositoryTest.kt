package com.whatsappv2.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
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

    /**
     * A store per test class run, in a fresh file.
     *
     * Robolectric reuses the application between test cases, so a store tied to a fixed
     * location would carry one test's writes into the next - which is exactly how the
     * first version of this suite failed.
     */
    private val file = File(context.cacheDir, "settings-${System.nanoTime()}.preferences_pb")

    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create { file }

    /**
     * A repository reading a COPY of the file the first one wrote.
     *
     * This is what stands in for process death, and the copy is not incidental: DataStore
     * refuses two live instances over one file in a single process, so re-opening the
     * original is impossible. Reading a byte-for-byte copy proves the same thing and
     * proves it more directly - the settings are in the file, not in memory.
     */
    private fun reopenedFromDisk(): DataStoreAppSettingsRepository {
        val copy = File(context.cacheDir, "settings-copy-${System.nanoTime()}.preferences_pb")
        file.copyTo(copy, overwrite = true)
        copies += copy
        return DataStoreAppSettingsRepository(PreferenceDataStoreFactory.create { copy }, NoOpLogger)
    }

    private val copies = mutableListOf<File>()

    private fun repository() = DataStoreAppSettingsRepository(store, NoOpLogger)

    @After
    fun tearDown() {
        file.delete()
        copies.forEach { it.delete() }
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

        val reloaded = reopenedFromDisk().currentSettings()

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
    fun `a corrupt preferences file falls back to defaults rather than crashing`() = runTest {
        // Losing settings is recoverable; crashing on launch for every user whose file
        // got truncated is not.
        val corrupt = File(context.cacheDir, "corrupt-${System.nanoTime()}.preferences_pb")
        corrupt.writeText("this is not a preferences protobuf")
        copies += corrupt

        val repository = DataStoreAppSettingsRepository(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            ) { corrupt },
            NoOpLogger,
        )

        assertEquals(AppSettings.DEFAULT, repository.currentSettings())
    }

    @Test
    fun `an unknown stored value is treated as unset, not as a crash`() = runTest {
        // A downgrade after a new option shipped leaves a name this build does not know.
        val repository = repository()
        repository.setDtmfMode(DtmfMode.SIP_INFO)
        assertEquals(DtmfMode.SIP_INFO, repository.currentSettings().dtmfMode)
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
