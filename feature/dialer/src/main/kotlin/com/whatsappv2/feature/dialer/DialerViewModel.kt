package com.whatsappv2.feature.dialer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.call.userMessage
import com.whatsappv2.domain.contacts.ContactRepository
import com.whatsappv2.domain.contacts.SipContact
import com.whatsappv2.domain.engine.CameraAvailability
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.engine.SipRegistrar
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.RegistrationState
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.repository.SipAccountRepository
import com.whatsappv2.domain.usecase.ConferenceJoinCoordinator
import com.whatsappv2.domain.usecase.PlaceCallError
import com.whatsappv2.domain.usecase.PlaceCallUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The dialer (Task 36).
 *
 * ## What it does not decide
 *
 * How `1001` becomes an address. That is [PlaceCallUseCase]'s job, and deliberately: the
 * rule — complete a bare extension against the chosen account's domain, leave a full URI
 * alone — has to be identical wherever a call starts, and a copy of it in a ViewModel is a
 * copy that will drift the first time another screen places a call.
 *
 * What it does decide is which account a call goes out on, because that is a choice the
 * user makes on this screen.
 *
 * ## Choosing an account here changes the default, as it does everywhere else
 *
 * It used to be a **per-call override**: chosen here, cleared by the next placed call,
 * and invisible to the rest of the app. That was one screen with a private idea of which
 * extension the phone is on. Choosing an extension in the Chats bar sets the default
 * (`RegistrationStatusViewModel.setDefault`), so the two screens disagreed the moment
 * either was used — the dialler showed the account the user had picked, the bar showed the
 * one they had not, and placing a call quietly put the dialler back to the bar's answer.
 *
 * So the choice goes to the same place from both screens: [SipAccountRepository.setDefault],
 * which clears the previous default in the same transaction. The selection then survives
 * navigation, a placed call and process death for free, because it lives in the database
 * rather than in this object — which is also why the `SavedStateHandle` this class used to
 * carry is gone. The only thing held here is [Entry.pendingDefault], an echo that lasts
 * until the store's own flow reports the write, so the card does not sit on the old
 * account for the width of a database round trip.
 */
@HiltViewModel
class DialerViewModel @Inject constructor(
    private val placeCall: PlaceCallUseCase,
    private val recentDials: RecentDials,
    private val contacts: ContactRepository,
    /**
     * Read only to *describe* a video call, never to decide one (Task 74).
     *
     * [PlaceCallUseCase] owns the downgrade rule and is the only place that may; asking
     * here as well is purely so the snackbar can say the call went out as audio. Two
     * copies of the decision would be one that drifts.
     */
    private val camera: CameraAvailability,
    /**
     * Read for the account list, and written when the user picks one of them.
     *
     * A `val` rather than a constructor-only parameter because choosing an account is now
     * a write: see the class documentation for why the dialler sets the default rather
     * than keeping an override of its own.
     */
    private val repository: SipAccountRepository,
    registrar: SipRegistrar,
    /**
     * How a call placed from a live conference becomes a participant of it: the dialler
     * asks for the join, and the coordinator mixes the leg when the far end answers —
     * long after this screen has been left (ADR-009).
     */
    private val joins: ConferenceJoinCoordinator,
) : ViewModel() {

    /**
     * Everything the user has entered, in one holder.
     *
     * One flow rather than three, because they are read together on every emission and
     * `combine` runs out of arities quickly — and because "what is being dialled, from
     * which account, is it going out yet" is genuinely one piece of state.
     */
    private data class Entry(
        val input: String = "",
        /**
         * The account just chosen on this screen, until the store confirms it.
         *
         * Not where the selection lives — that is the account's `isDefault` column. This
         * is only so the card changes on the tap rather than a database round trip later,
         * and it is dropped the moment [SipAccountRepository.observeAccounts] reports the
         * write. Keeping it any longer would let a stale choice here outrank a default
         * changed from the Chats bar.
         */
        val pendingDefault: AccountId? = null,
        val placing: Boolean = false,
    )

    private val entry = MutableStateFlow(Entry())

    private val eventChannel = Channel<DialerEvent>(Channel.BUFFERED)
    val events: Flow<DialerEvent> = eventChannel.receiveAsFlow()

    /**
     * Contacts matching what has been typed (Task 50).
     *
     * `mapLatest`, so a keystroke cancels the search the one before it started: a picker
     * that queued a provider read per character would show results for a prefix the user
     * has already finished typing past.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val matchingContacts: Flow<List<SipContact>> = entry
        .map { it.input }
        .distinctUntilChanged()
        .mapLatest { query -> contacts.search(query, CONTACT_RESULTS) }

    val uiState: StateFlow<DialerUiState> = combine(
        repository.observeAccounts(),
        registrar.registrationState,
        entry,
        recentDials.recent,
        matchingContacts,
    ) { accounts, registrations, current, recent, matches ->
        Frame(accounts, registrations, current, recent, matches)
    }.combine(joins.hostingLiveConference) { frame, adding -> frame.toUiState(adding) }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
        initialValue = DialerUiState(),
    )

    /**
     * The five sources `combine` type-checks, folded before the sixth is added: past
     * five it stops being type-checked, and an indexed array of Any is a worse trade
     * than one small class.
     */
    private data class Frame(
        val accounts: List<SipAccount>,
        val registrations: Map<AccountId, RegistrationState>,
        val current: Entry,
        val recent: List<String>,
        val matches: List<SipContact>,
    )

    private fun Frame.toUiState(addingToConference: Boolean): DialerUiState {
        val rows = accounts.map { account ->
            DialerAccount(
                id = account.id,
                label = account.label,
                identity = "${account.username}@${account.domain}",
                isRegistered = registrations[account.id]?.isUsable == true,
                isDefault = account.isDefault,
            )
        }
        // The tap that has not reached the store yet, the default account once it has, and
        // the first account if nothing is marked default - which is what the use case
        // would pick.
        val selected = rows.firstOrNull { it.id == current.pendingDefault }
            ?: rows.firstOrNull { it.isDefault }
            ?: rows.firstOrNull()

        return DialerUiState(
            input = current.input,
            accounts = rows,
            selectedAccount = selected,
            selectionIsDefault = selected?.isDefault == true,
            recent = recent,
            contacts = matches,
            isPlacing = current.placing,
            addingToConference = addingToConference,
        )
    }

    /** Replaces the input, for the text field. */
    fun onInputChanged(value: String) {
        entry.update { it.copy(input = value) }
    }

    /** Appends one keypad character. */
    fun onDigitPressed(digit: Char) {
        entry.update { it.copy(input = it.input + digit) }
    }

    /** Removes the last character. */
    fun onBackspace() {
        entry.update { it.copy(input = it.input.dropLast(1)) }
    }

    /** Clears the input. Bound to a long press on backspace, as every dialler does. */
    fun onClear() {
        entry.update { it.copy(input = "") }
    }

    /**
     * Makes [id] the default account — the one outgoing calls leave on, here and
     * everywhere else in the app.
     *
     * The same single call the Chats indicator makes, for the same reason: the repository
     * clears the previous default in the same transaction, so there is never an instant
     * with two defaults or none. See the class documentation for why this is a setting
     * rather than the per-call override it used to be.
     *
     * The echo is shown immediately and dropped once the store's own flow reports the
     * write, so the card follows the tap without ever outliving the fact it is echoing.
     */
    fun onAccountSelected(id: AccountId) {
        entry.update { it.copy(pendingDefault = id) }
        viewModelScope.launch {
            val written = repository.setDefault(id) is Outcome.Success
            if (written) {
                // Suspends until the row the write produced comes back round — or until
                // the account disappears, which is the only other way this can end and
                // would otherwise leave a coroutine waiting for a row that is gone.
                repository.observeAccounts().first { accounts ->
                    accounts.none { it.id == id } || accounts.any { it.id == id && it.isDefault }
                }
            } else {
                // Refused: the account was deleted under the open menu, most likely. The
                // echo goes rather than standing over a default that never changed — a
                // card naming one account while calls leave on another is the §6 lie in
                // the most expensive place to tell it.
                eventChannel.send(DialerEvent.Refused(ACCOUNT_GONE))
            }
            entry.update { if (it.pendingDefault == id) it.copy(pendingDefault = null) else it }
        }
    }

    /** Fills the input from a recent target. Tapping does not dial: a misplaced tap should
     * not place a call. */
    fun onRecentSelected(target: String) {
        entry.update { it.copy(input = target) }
    }

    /**
     * Places the call.
     *
     * The input is recorded as dialled **before** the outcome is known, on purpose: a call
     * that failed is exactly the one someone wants to redial, and a recents list that only
     * remembers successes is missing the entries that matter.
     */
    fun onCall() {
        val state = uiState.value
        if (!state.canPlaceCall) return
        place(state.input, MediaProfile.AUDIO)
    }

    /**
     * Places the call with video (Task 74).
     *
     * Same guard, same path, one different profile. A device that cannot capture places an
     * audio call instead — the use case decides that, and [place] says so afterwards.
     */
    fun onVideoCall() {
        val state = uiState.value
        if (!state.canPlaceCall) return
        place(state.input, MediaProfile.AUDIO_VIDEO)
    }

    /**
     * Calls a contact straight from the picker (Task 50).
     *
     * The address is placed directly rather than typed into the field and then dialled:
     * [onCall] reads `uiState`, which is one dispatch behind the entry it is combined
     * from, so it would dial whatever was in the field a moment ago. The field is still
     * filled in, because a call that is placed should show what is being called.
     */
    fun onContactSelected(contact: SipContact) {
        val target = contact.address.render()
        entry.update { it.copy(input = target) }
        place(target, MediaProfile.AUDIO)
    }

    private fun place(target: String, media: MediaProfile) {
        val state = uiState.value
        val downgraded = media.hasVideo && !camera.isCameraUsable()
        entry.update { it.copy(placing = true) }
        viewModelScope.launch {
            try {
                recentDials.record(target)
                val result = placeCall(
                    // Named explicitly unless it is the default account: an override is
                    // one reason for that, and "nothing is marked default" is the other -
                    // the screen shows an account, so the call must go out on it rather
                    // than fail for want of a default the user never set.
                    accountOverride = state.selectedAccount?.id.takeIf { !state.selectionIsDefault },
                    input = target,
                    media = media,
                )
                // Decided from the state the screen showed, which is what the user was
                // told this call would be. Asked for before anything else can happen to
                // the call, so an answer that comes back quickly still finds the request.
                if (result is Outcome.Success && state.addingToConference) joins.joinOnConnect(result.value)
                eventChannel.send(result.toEvent(target))
                // Said after the call is on its way, not instead of it: the call still
                // happened, and the notice explains which kind it turned out to be.
                if (result is Outcome.Success && downgraded) {
                    eventChannel.send(DialerEvent.Notice(NO_CAMERA))
                }
                // Cleared only on success: a call that was refused leaves what was typed
                // on screen, because the user is about to correct it or try again.
                //
                // The *input* only. The account is not cleared any more and must not be:
                // it is the default now, chosen deliberately, and putting it back after
                // every call is precisely the behaviour that made the dialler and the
                // Chats bar disagree about which extension the phone is on.
                if (result is Outcome.Success) entry.update { it.copy(input = "") }
            } finally {
                entry.update { it.copy(placing = false) }
            }
        }
    }

    private fun Outcome<CallId, PlaceCallError>.toEvent(target: String): DialerEvent = when (this) {
        is Outcome.Success -> DialerEvent.CallPlaced(value)
        is Outcome.Failure -> when (val reason = error) {
            is PlaceCallError.NoAccountAvailable -> DialerEvent.NoAccount
            is PlaceCallError.UnknownAccount -> DialerEvent.Refused(ACCOUNT_GONE)
            is PlaceCallError.InvalidTarget -> DialerEvent.InvalidTarget(target)
            // The account was not registered, the app tried to register it, and the server
            // did not answer inside PlaceCallUseCase's bound. Worded as what happened rather
            // than as "not registered", because by now that is only half the story.
            is PlaceCallError.NotRegistered ->
                DialerEvent.Refused("Could not reach the server for that account")
            is PlaceCallError.Rejected -> reason.cause.toEvent()
        }
    }

    /**
     * The engine's refusal, in a sentence.
     *
     * The sentences are the domain's, not this screen's (Task 44). They used to be a
     * `when` here with a general branch under it, which meant an error this dialler had
     * not thought of read "The call could not be placed" while the in-call screen called
     * the same error something else again.
     */
    private fun SipError.toEvent(): DialerEvent = DialerEvent.Refused(userMessage())

    internal companion object {
        const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L

        /** Worded exactly as the refusal for a call on a vanished account, so one fact reads one way. */
        const val ACCOUNT_GONE = "That account is no longer configured"

        /**
         * How many contacts the picker offers.
         *
         * A screenful. The bound is not a nicety: it is what stops a blank query reading
         * as "give me the address book" (§7, §11).
         */
        const val CONTACT_RESULTS = 20

        /** Worded exactly as `:feature:history` words it, so one downgrade reads one way. */
        const val NO_CAMERA = "No camera available, so the call went out as audio"
    }
}
