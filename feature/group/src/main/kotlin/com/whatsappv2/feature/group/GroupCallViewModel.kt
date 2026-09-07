package com.whatsappv2.feature.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.call.userMessage
import com.whatsappv2.domain.contacts.ContactRepository
import com.whatsappv2.domain.contacts.SipContact
import com.whatsappv2.domain.engine.CameraAvailability
import com.whatsappv2.domain.usecase.JoinConferenceUseCase
import com.whatsappv2.domain.usecase.PlaceCallError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Builds a group and dials the bridge it will meet on (Task 78, §2.2, ADR-003).
 *
 * ## What this deliberately does not do
 *
 * Create anything on a server. §2.2 says group calling is not a SIP client feature on its
 * own, and ADR-003 chose a dial-in MCU — so there is no API here, and none specified, for
 * making a room or inviting people to it. The member list is assembled on this device and
 * stays there; the call is [JoinConferenceUseCase] against an address the user supplies.
 *
 * Everything after the join is machinery that already exists: the roster, the video grid,
 * hold, mute and the call log all work on a conference leg without knowing it is one
 * (Tasks 60, 61). This ViewModel adds no call handling of its own.
 */
@HiltViewModel
class GroupCallViewModel @Inject constructor(
    private val joinConference: JoinConferenceUseCase,
    private val contacts: ContactRepository,
    /**
     * Read only to *describe* a video join, never to decide one.
     *
     * [JoinConferenceUseCase] owns the downgrade rule, exactly as [PlaceCallError]'s path
     * does for a one-to-one call. Asking here as well is so the snackbar can say the join
     * went out as audio; two copies of the decision would be one that drifts.
     */
    private val camera: CameraAvailability,
) : ViewModel() {

    private val state = MutableStateFlow(GroupCallUiState())
    val uiState: StateFlow<GroupCallUiState> = state.asStateFlow()

    private val eventChannel = Channel<GroupCallEvent>(Channel.BUFFERED)
    val events: Flow<GroupCallEvent> = eventChannel.receiveAsFlow()

    init {
        watchContactQuery()
    }

    fun onNameChanged(value: String) = state.update { it.copy(name = value) }

    fun onAddressChanged(value: String) = state.update { it.copy(conferenceAddress = value) }

    fun onQueryChanged(value: String) = state.update { it.copy(query = value) }

    /** Adds a member, ignoring one already on the list — the same person twice is not two people. */
    fun onAddMember(contact: SipContact) = state.update { current ->
        if (current.members.any { it.address == contact.address }) {
            current
        } else {
            current.copy(members = current.members + contact)
        }
    }

    fun onRemoveMember(contact: SipContact) = state.update { current ->
        current.copy(members = current.members.filterNot { it.address == contact.address })
    }

    fun onStartAudioCall() = join(withVideo = false)

    fun onStartVideoCall() = join(withVideo = true)

    /**
     * Contacts matching what has been typed, as the dialler's picker does (Task 50).
     *
     * `mapLatest`, so a keystroke cancels the search the one before it started: a picker
     * that queued a provider read per character would show results for a prefix the user
     * has already finished typing past.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun watchContactQuery() {
        viewModelScope.launch {
            state.map { it.query }
                .distinctUntilChanged()
                .mapLatest { query -> contacts.search(query, CONTACT_RESULTS) }
                .collect { matches -> state.update { it.copy(matches = matches) } }
        }
    }

    private fun join(withVideo: Boolean) {
        val current = state.value
        if (!current.canJoin) return

        val downgraded = withVideo && !camera.isCameraUsable()
        state.update { it.copy(isJoining = true) }

        viewModelScope.launch {
            try {
                val result = joinConference(input = current.conferenceAddress, withVideo = withVideo)
                eventChannel.send(
                    when (result) {
                        is Outcome.Success -> GroupCallEvent.CallPlaced(result.value)
                        is Outcome.Failure -> GroupCallEvent.Refused(result.error.describe())
                    },
                )
                if (result is Outcome.Success && downgraded) {
                    eventChannel.send(GroupCallEvent.Notice(NO_CAMERA))
                }
            } finally {
                state.update { it.copy(isJoining = false) }
            }
        }
    }

    /** The refusal in a sentence, from the domain's table (Task 44). */
    private fun PlaceCallError.describe(): String = when (this) {
        is PlaceCallError.Rejected -> cause.userMessage()
        is PlaceCallError.NoAccountAvailable -> "Add an account before starting a group call"
        is PlaceCallError.UnknownAccount -> "That account is no longer set up"
        is PlaceCallError.InvalidTarget -> "That conference address could not be dialled"
    }

    private companion object {
        /** A screenful, and the bound that stops a blank query reading as "give me the book". */
        const val CONTACT_RESULTS = 20

        /** Worded as the dialler and the history word it, so one downgrade reads one way. */
        const val NO_CAMERA = "No camera available, so the group call went out as audio"
    }
}
