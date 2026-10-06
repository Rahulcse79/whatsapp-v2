package com.whatsappv2.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.domain.model.AppSettings
import com.whatsappv2.domain.model.CallHistoryRetention
import com.whatsappv2.domain.model.DtmfMode
import com.whatsappv2.domain.model.PreferredAudioRoute
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.ThemeMode
import com.whatsappv2.domain.repository.AppSettingsRepository
import com.whatsappv2.domain.video.VideoFrameRate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

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
    private val dataStore: DataStore<Preferences>,
    private val logger: Logger,
) : AppSettingsRepository {

    override fun observeSettings(): Flow<AppSettings> = dataStore.data
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

    override suspend fun setThemeMode(mode: ThemeMode) = edit { it[THEME_MODE] = mode.name }

    /**
     * Stored as the frame rate's own number rather than its enum name.
     *
     * A number is what the setting means, so a value written by a build that offered a rate
     * this one does not is still readable: `ofFps` returns null and the read below falls
     * back to the default, rather than a name like `FPS_24` that means nothing at all.
     */
    override suspend fun setVideoFrameRate(rate: VideoFrameRate) =
        edit { it[VIDEO_FRAME_RATE] = rate.fps }

    override suspend fun setSipTraceEnabled(enabled: Boolean) =
        edit { it[SIP_TRACE] = enabled }

    override suspend fun setLiveCallFilteringEnabled(enabled: Boolean) =
        edit { it[LIVE_CALL_FILTERING] = enabled }

    override suspend fun setVerifyTlsCertificates(verify: Boolean) =
        edit { it[VERIFY_TLS] = verify }

    override suspend fun setCallHistoryRetention(retention: CallHistoryRetention) =
        edit { it[HISTORY_RETENTION_DAYS] = retention.days }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
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
        themeMode = this[THEME_MODE]?.toEnumOrNull<ThemeMode>() ?: AppSettings.DEFAULT.themeMode,
        videoFrameRate = this[VIDEO_FRAME_RATE]?.let(VideoFrameRate::ofFps)
            ?: AppSettings.DEFAULT.videoFrameRate,
        sipTraceEnabled = this[SIP_TRACE] ?: AppSettings.DEFAULT.sipTraceEnabled,
        // Absent means a fresh install, or an upgrade from a build that predates the
        // setting - and the default is off, so neither gets a gate they did not ask for.
        // Somebody who has already chosen has a value stored here, and keeps it.
        liveCallFilteringEnabled = this[LIVE_CALL_FILTERING]
            ?: AppSettings.DEFAULT.liveCallFilteringEnabled,
        verifyTlsCertificates = this[VERIFY_TLS] ?: AppSettings.DEFAULT.verifyTlsCertificates,
        // Through `ofDays`, so a value written by a build with a longer maximum — or
        // corrupted to something absurd — is clamped rather than used to compute a cutoff
        // that would delete the wrong rows.
        callHistoryRetention = this[HISTORY_RETENTION_DAYS]?.let(CallHistoryRetention::ofDays)
            ?: AppSettings.DEFAULT.callHistoryRetention,
    )

    private inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? =
        enumValues<T>().firstOrNull { it.name == this }

    private companion object {
        const val TAG = "AppSettings"

        val DTMF_MODE = stringPreferencesKey("dtmf_mode")
        val SRTP_POLICY = stringPreferencesKey("default_srtp_policy")
        val AUDIO_ROUTE = stringPreferencesKey("preferred_audio_route")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val VIDEO_FRAME_RATE = intPreferencesKey("video_frame_rate_fps")
        val SIP_TRACE = booleanPreferencesKey("sip_trace_enabled")
        val LIVE_CALL_FILTERING = booleanPreferencesKey("live_call_filtering_enabled")
        val VERIFY_TLS = booleanPreferencesKey("verify_tls_certificates")
        val HISTORY_RETENTION_DAYS = intPreferencesKey("call_history_retention_days")
    }
}
