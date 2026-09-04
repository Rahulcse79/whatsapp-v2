package com.whatsappv2.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whatsappv2.domain.model.AppSettings
import com.whatsappv2.domain.model.DtmfMode
import com.whatsappv2.domain.model.PreferredAudioRoute
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.repository.AppSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the settings screen renders. */
data class SettingsUiState(
    val settings: AppSettings = AppSettings.DEFAULT,
    /**
     * Whether the SIP trace toggle is shown at all.
     *
     * False in release builds. Absent rather than disabled: a disabled control invites
     * someone to make it enableable, while an absent one has nothing to re-enable.
     */
    val traceToggleAvailable: Boolean = false,
)

/** App preferences. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: AppSettingsRepository,
    traceAvailability: TraceAvailability,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = repository.observeSettings()
        .map { SettingsUiState(it, traceAvailability.isAvailable) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
            initialValue = SettingsUiState(traceToggleAvailable = traceAvailability.isAvailable),
        )

    fun setDtmfMode(mode: DtmfMode) = viewModelScope.launch { repository.setDtmfMode(mode) }

    fun setDefaultSrtpPolicy(policy: SrtpPolicy) =
        viewModelScope.launch { repository.setDefaultSrtpPolicy(policy) }

    fun setPreferredAudioRoute(route: PreferredAudioRoute) =
        viewModelScope.launch { repository.setPreferredAudioRoute(route) }

    fun setSipTraceEnabled(enabled: Boolean) =
        viewModelScope.launch { repository.setSipTraceEnabled(enabled) }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * Whether this build may offer SIP tracing.
 *
 * An interface so the feature module does not depend on :app, and so a test can assert
 * both answers. The real value comes from a build-type source set, not a runtime check.
 */
fun interface TraceAvailability {
    val isAvailable: Boolean
}
