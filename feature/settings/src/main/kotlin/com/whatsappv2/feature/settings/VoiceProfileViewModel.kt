package com.whatsappv2.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whatsappv2.domain.voice.EnrolmentProgress
import com.whatsappv2.domain.voice.EnrolmentRules
import com.whatsappv2.domain.voice.VoiceEnrolment
import com.whatsappv2.domain.voice.VoiceProfile
import com.whatsappv2.domain.voice.VoiceProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the voice-profile card shows. */
data class VoiceProfileUiState(
    /** The stored profile, or null when the user has not enrolled. */
    val profile: VoiceProfile? = null,
    /** Non-null while enrolment is running. */
    val progress: EnrolmentProgress? = null,
) {
    val isEnrolling: Boolean
        get() = progress is EnrolmentProgress.Recording || progress is EnrolmentProgress.Building
}

/**
 * Records a voice profile, replaces it, and deletes it (ADR-013).
 *
 * ## Why this is its own ViewModel
 *
 * `SettingsViewModel` is preferences: a dozen values that are read and written and have
 * no lifecycle. Enrolment has one — a cancellable job holding the microphone for up to
 * two minutes — and mixing a long-running job into the screen's state holder is how a
 * recording survives the screen that started it.
 *
 * ## Storing is a separate step from recording, on purpose
 *
 * [VoiceEnrolment] ends at [EnrolmentProgress.Ready] and hands the profile back; this
 * writes it. So a cancelled enrolment stores nothing without needing a rollback, and
 * there is exactly one line in the app that replaces a profile.
 */
@HiltViewModel
class VoiceProfileViewModel @Inject constructor(
    private val enrolment: VoiceEnrolment,
    private val profiles: VoiceProfileRepository,
) : ViewModel() {

    private val state = MutableStateFlow(VoiceProfileUiState())
    val uiState: StateFlow<VoiceProfileUiState> = state.asStateFlow()

    private var job: Job? = null

    init {
        profiles.profile
            .onEach { stored -> state.value = state.value.copy(profile = stored) }
            .launchIn(viewModelScope)
    }

    /** Starts recording. A second call while one is running is ignored. */
    fun train() {
        if (job?.isActive == true) return
        job = enrolment.enrol()
            .onEach { step ->
                state.value = state.value.copy(progress = step)
                if (step is EnrolmentProgress.Ready) {
                    profiles.replace(step.profile)
                }
            }
            .launchIn(viewModelScope)
    }

    /** Abandons a recording in progress. Nothing is stored and the old profile stands. */
    fun cancel() {
        job?.cancel()
        job = null
        state.value = state.value.copy(progress = null)
    }

    /**
     * Ends a recording early and builds a profile from what was captured.
     *
     * The job is deliberately left running: the flow still has to reach `Building` and
     * then `Ready` or `Failed`, and cancelling it — which is what the button used to do —
     * is exactly the bug this replaces. See [VoiceEnrolment.requestFinish].
     */
    fun finish() {
        enrolment.requestFinish()
    }

    /** Clears the finished/failed banner once the user has seen it. */
    fun acknowledge() {
        state.value = state.value.copy(progress = null)
    }

    /** Forgets the profile. The gate then does nothing; it does not start muting. */
    fun delete() {
        viewModelScope.launch {
            profiles.delete()
            state.value = state.value.copy(progress = null)
        }
    }

    /** How long the user is asked to speak for, so the card and the rules agree. */
    val targetSeconds: Int get() = EnrolmentRules.TARGET_SECONDS
}
