package com.whatsappv2.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.domain.model.AppSettings
import com.whatsappv2.domain.model.DtmfMode
import com.whatsappv2.domain.model.PreferredAudioRoute
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.repository.AppSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app-settings",
)

/**
 * App preferences in DataStore.
 *
 * DataStore rather than SharedPreferences: reads are a Flow, so a setting change reaches
 * every screen at once instead of only those that happen to re-read it, and writes are
 * transactional rather than fire-and-forget.
 *
 * Nothing here is sensitive. Credentials live in the encrypted account row (Task 16);
 * putting a preference through the cipher would imply a sensitivity it does not have and
 * would make these values unreadable after a Keystore reset for no benefit.
 */
@Singleton
class DataStoreAppSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) : AppSettingsRepository {

    override fun observeSettings(): Flow<AppSettings> = context.settingsDataStore.data
        .catch { error ->
            // A corrupt preferences file must not take the app down. Falling back to
            // defaults loses settings, which is recoverable; crashing on launch is not.
            if (error is IOException) {
                logger.error(TAG, "Settings unreadable; falling back to defaults")
                emit(androidx.datastore.preferences.core.emptyPreferences())
            } else {
                throw error
            }
        }
        .map { it.toAppSettings() }

    override suspend fun currentSettings(): AppSettings = observeSettings().first()

    override suspend fun setDtmfMode(mode: DtmfMode) = edit { it[DTMF_MODE] = mode.name }

    override suspend fun setDefaultSrtpPolicy(policy: SrtpPolicy) =
        edit { it[SRTP_POLICY] = policy.name }

    override suspend fun setPreferredAudioRoute(route: PreferredAudioRoute) =
        edit { it[AUDIO_ROUTE] = route.name }

    override suspend fun setSipTraceEnabled(enabled: Boolean) =
        edit { it[SIP_TRACE] = enabled }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }

    /**
     * Reads stored values, falling back to a default for anything missing or unknown.
     *
     * An unrecognised enum name - a downgrade after a new option shipped - must not
     * crash: it means the same as unset.
     */
    private fun Preferences.toAppSettings() = AppSettings(
        dtmfMode = this[DTMF_MODE]?.toEnumOrNull<DtmfMode>() ?: AppSettings.DEFAULT.dtmfMode,
        defaultSrtpPolicy = this[SRTP_POLICY]?.toEnumOrNull<SrtpPolicy>()
            ?: AppSettings.DEFAULT.defaultSrtpPolicy,
        preferredAudioRoute = this[AUDIO_ROUTE]?.toEnumOrNull<PreferredAudioRoute>()
            ?: AppSettings.DEFAULT.preferredAudioRoute,
        sipTraceEnabled = this[SIP_TRACE] ?: AppSettings.DEFAULT.sipTraceEnabled,
    )

    private inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? =
        enumValues<T>().firstOrNull { it.name == this }

    private companion object {
        const val TAG = "AppSettings"

        val DTMF_MODE = stringPreferencesKey("dtmf_mode")
        val SRTP_POLICY = stringPreferencesKey("default_srtp_policy")
        val AUDIO_ROUTE = stringPreferencesKey("preferred_audio_route")
        val SIP_TRACE = booleanPreferencesKey("sip_trace_enabled")
    }
}
