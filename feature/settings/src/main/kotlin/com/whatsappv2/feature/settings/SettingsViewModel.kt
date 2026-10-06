package com.whatsappv2.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whatsappv2.domain.model.AppSettings
import com.whatsappv2.domain.model.CallHistoryRetention
import com.whatsappv2.domain.model.DtmfMode
import com.whatsappv2.domain.model.PreferredAudioRoute
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.ThemeMode
import com.whatsappv2.domain.repository.AppSettingsRepository
import com.whatsappv2.domain.repository.ChatSessionRepository
import com.whatsappv2.domain.video.VideoFrameRate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    /** Who is signed in to chat, or null when nobody is. */
    val chatAccount: ChatAccountUiState? = null,
)

/**
 * The signed-in chat identity, as Settings shows it.
 *
 * ## Why the row appears only while signed in
 *
 * Because its only action is Sign out, and a Sign out for an account nobody has is a
 * control that cannot do anything. Signing *in* happens on the Chats tab, with the server
 * URL beside the credentials it belongs to — decision D1, and the reason this row carries
 * no URL field.
 *
 * [serverOrigin] is shown and not editable. It is what the session was obtained against,
 * so changing it here would leave a saved session pointing at a server it did not come
 * from — a failure that looks to a user like a broken account.
 */
data class ChatAccountUiState(
    val identity: String,
    val serverOrigin: String,
)

/**
 * App preferences.
 *
 * ## Sign out is here AND on the Chats tab, on purpose
 *
 * It was moved out of here when the login became the Chats tab's own (decision D4), on the
 * grounds that Settings is reached from the Calls tab too and so sits outside the section
 * the session belongs to. That argument holds for where the login *gate* lives and not for
 * where its exit is findable: "Settings" is the first place a person looks to sign out of
 * anything, and Chats' overflow menu is the first place they look while they are in Chats.
 * Both reach the same `ChatSessionRepository.signOut`, so neither can drift from the other.
 *
 * Signing *in* is still the Chats tab's alone: this card is absent with no session, so
 * Settings never asks for credentials.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: AppSettingsRepository,
    private val chatSessions: ChatSessionRepository,
    traceAvailability: TraceAvailability,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        repository.observeSettings(),
        chatSessions.observeSession(),
        chatSessions.observeServerUrl(),
    ) { settings, session, serverUrl ->
        SettingsUiState(
            settings = settings,
            traceToggleAvailable = traceAvailability.isAvailable(),
            chatAccount = session?.let {
                ChatAccountUiState(
                    identity = it.displayName ?: it.userId,
                    serverOrigin = serverUrl.origin,
                )
            },
        )
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
            initialValue = SettingsUiState(traceToggleAvailable = traceAvailability.isAvailable()),
        )

    fun setDtmfMode(mode: DtmfMode) = viewModelScope.launch { repository.setDtmfMode(mode) }

    fun setDefaultSrtpPolicy(policy: SrtpPolicy) =
        viewModelScope.launch { repository.setDefaultSrtpPolicy(policy) }

    fun setPreferredAudioRoute(route: PreferredAudioRoute) =
        viewModelScope.launch { repository.setPreferredAudioRoute(route) }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { repository.setThemeMode(mode) }

    fun setSipTraceEnabled(enabled: Boolean) =
        viewModelScope.launch { repository.setSipTraceEnabled(enabled) }

    fun setVerifyTlsCertificates(verify: Boolean) =
        viewModelScope.launch { repository.setVerifyTlsCertificates(verify) }

    fun setCallHistoryRetention(retention: CallHistoryRetention) =
        viewModelScope.launch { repository.setCallHistoryRetention(retention) }

    fun setVideoFrameRate(rate: VideoFrameRate) =
        viewModelScope.launch { repository.setVideoFrameRate(rate) }

    fun setLiveCallFilteringEnabled(enabled: Boolean) =
        viewModelScope.launch { repository.setLiveCallFilteringEnabled(enabled) }

    /**
     * Signs out of chat. The repository call and nothing else.
     *
     * No `ChatSignOutUseCase` in front of it: §4.2 forbids pass-through use cases, and
     * this has no second collaborator to order — disconnecting the socket is `:data:chat`'s
     * business, triggered by the session it already watches. The confirmation that guards
     * it is the screen's, because confirming is a UI decision. The same call backs the
     * Chats tab's overflow item, so the two cannot behave differently.
     */
    fun signOutOfChat() = viewModelScope.launch { chatSessions.signOut() }

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
    // A function, not a property: a `fun interface` must have exactly one abstract
    // FUNCTION, and abstract properties are not allowed in one at all.
    fun isAvailable(): Boolean
}
