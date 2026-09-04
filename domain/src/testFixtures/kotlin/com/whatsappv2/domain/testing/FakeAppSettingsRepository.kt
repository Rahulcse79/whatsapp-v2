package com.whatsappv2.domain.testing

import com.whatsappv2.domain.model.AppSettings
import com.whatsappv2.domain.model.DtmfMode
import com.whatsappv2.domain.model.PreferredAudioRoute
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.repository.AppSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** An in-memory [AppSettingsRepository]. Starts from the same defaults as a fresh install. */
class FakeAppSettingsRepository(
    initial: AppSettings = AppSettings.DEFAULT,
) : AppSettingsRepository {

    private val settings = MutableStateFlow(initial)

    override fun observeSettings(): Flow<AppSettings> = settings

    override suspend fun currentSettings(): AppSettings = settings.first()

    override suspend fun setDtmfMode(mode: DtmfMode) {
        settings.value = settings.value.copy(dtmfMode = mode)
    }

    override suspend fun setDefaultSrtpPolicy(policy: SrtpPolicy) {
        settings.value = settings.value.copy(defaultSrtpPolicy = policy)
    }

    override suspend fun setPreferredAudioRoute(route: PreferredAudioRoute) {
        settings.value = settings.value.copy(preferredAudioRoute = route)
    }

    override suspend fun setSipTraceEnabled(enabled: Boolean) {
        settings.value = settings.value.copy(sipTraceEnabled = enabled)
    }
}
