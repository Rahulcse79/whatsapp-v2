package com.whatsappv2.feature.accounts.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.AudioCodec
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.model.VideoCodec
import com.whatsappv2.domain.repository.SipAccountRepository
import com.whatsappv2.domain.usecase.SaveAccountError
import com.whatsappv2.domain.usecase.SaveAccountUseCase
import com.whatsappv2.domain.validation.AccountField
import com.whatsappv2.domain.validation.AccountViolation
import com.whatsappv2.domain.validation.SipAccountDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** The editor's state: the draft being edited plus anything wrong with it. */
data class AccountEditorUiState(
    val draft: SipAccountDraft,
    val isNewAccount: Boolean,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    /**
     * Violations by field, so each input can show its own message.
     *
     * Populated only after a save attempt: marking fields red while someone is still
     * typing the first character teaches them to ignore the colour.
     */
    val fieldErrors: Map<AccountField, AccountViolation> = emptyMap(),
    val warnings: List<AccountViolation> = emptyList(),
) {
    fun errorFor(field: AccountField): AccountViolation? = fieldErrors[field]
}

/** One-shot outcomes of the editor. */
sealed interface AccountEditorEvent {
    data class Saved(val label: String, val unregisteredFirst: Boolean) : AccountEditorEvent
    data class SaveFailed(val detail: String) : AccountEditorEvent

    /** The stored password could not be read; the user must type it again. */
    data object CredentialsMustBeReEntered : AccountEditorEvent
}

/**
 * Creates and edits a SIP account.
 *
 * Holds a [SipAccountDraft] - raw strings, as typed - and hands it to
 * [SaveAccountUseCase], which is the single place validation happens. A ViewModel that
 * validated as well would be a second, drifting copy of the rules.
 *
 * ## The password field on an existing account
 *
 * Loading an account never yields its password: the repository returns an empty one so a
 * decrypted credential does not sit in memory for the life of a screen. The field is
 * therefore left blank when editing, meaning "unchanged" - and only a non-empty value is
 * treated as a new password. That is also what stops an edit needlessly re-registering.
 */
@HiltViewModel
class AccountEditorViewModel @Inject constructor(
    private val repository: SipAccountRepository,
    private val saveAccount: SaveAccountUseCase,
) : ViewModel() {

    private val state = MutableStateFlow(
        AccountEditorUiState(draft = newDraft(), isNewAccount = true),
    )
    val uiState: StateFlow<AccountEditorUiState> = state.asStateFlow()

    private val eventChannel = Channel<AccountEditorEvent>(Channel.BUFFERED)
    val events: Flow<AccountEditorEvent> = eventChannel.receiveAsFlow()

    /** Loads an existing account, or starts a new one when [id] is null. */
    fun load(id: AccountId?) {
        if (id == null) {
            state.value = AccountEditorUiState(draft = newDraft(), isNewAccount = true)
            return
        }

        state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val account = repository.findById(id)
            state.value = if (account == null) {
                AccountEditorUiState(draft = newDraft(), isNewAccount = true)
            } else {
                AccountEditorUiState(draft = account.toDraft(), isNewAccount = false)
            }
        }
    }

    fun update(transform: (SipAccountDraft) -> SipAccountDraft) {
        state.update { current ->
            val draft = transform(current.draft)
            // Clear a field's error as soon as it is edited: leaving it red while the
            // user fixes it says the correction did not register.
            val stillWrong = current.fieldErrors.filterKeys { field ->
                field.valueIn(draft) == field.valueIn(current.draft)
            }
            current.copy(draft = draft, fieldErrors = stillWrong)
        }
    }

    fun save() {
        if (state.value.isSaving) return
        state.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            when (val result = saveAccount(state.value.draft)) {
                is Outcome.Success -> {
                    state.update {
                        it.copy(
                            isSaving = false,
                            fieldErrors = emptyMap(),
                            warnings = result.value.warnings,
                        )
                    }
                    eventChannel.send(
                        AccountEditorEvent.Saved(
                            label = result.value.account.label,
                            unregisteredFirst = result.value.unregisteredFirst,
                        ),
                    )
                }

                is Outcome.Failure -> {
                    state.update { it.copy(isSaving = false, fieldErrors = result.error.toFieldErrors()) }
                    result.error.toEvent()?.let { eventChannel.send(it) }
                }
            }
        }
    }

    private fun SaveAccountError.toFieldErrors(): Map<AccountField, AccountViolation> = when (this) {
        is SaveAccountError.Invalid -> violations.associateBy { it.field }
        is SaveAccountError.DuplicateIdentity -> mapOf(
            // Reported on the username, where the user can act on it, rather than as a
            // banner that does not say which field to change.
            AccountField.USERNAME to AccountViolation.Conflict(
                AccountField.USERNAME,
                "another account already registers $username@$domain",
            ),
        )
        else -> emptyMap()
    }

    private fun SaveAccountError.toEvent(): AccountEditorEvent? = when (this) {
        is SaveAccountError.CredentialsUnrecoverable -> AccountEditorEvent.CredentialsMustBeReEntered
        is SaveAccountError.Failed -> AccountEditorEvent.SaveFailed(detail)
        // Field errors are already on screen; an event as well would say it twice.
        is SaveAccountError.Invalid, is SaveAccountError.DuplicateIdentity -> null
    }

    private fun newDraft() = SipAccountDraft(id = AccountId(UUID.randomUUID().toString()))

    /**
     * Turns a stored account back into an editable draft.
     *
     * The password is left blank on purpose - see the class documentation.
     */
    private fun SipAccount.toDraft() = SipAccountDraft(
        id = id,
        label = label,
        username = username,
        extension = extension.orEmpty(),
        authUsername = authUsername.orEmpty(),
        password = Secret.EMPTY,
        displayName = displayName.orEmpty(),
        domain = domain,
        registrar = registrar?.render().orEmpty(),
        outboundProxy = outboundProxy?.render().orEmpty(),
        port = port?.toString().orEmpty(),
        transport = transport,
        registrationExpirySeconds = registrationExpirySeconds.toString(),
        stunServer = stunServer?.render().orEmpty(),
        turnServer = turn?.server?.render().orEmpty(),
        turnUsername = turn?.username.orEmpty(),
        turnPassword = Secret.EMPTY,
        iceEnabled = natPolicy.iceEnabled,
        stunEnabled = natPolicy.stunEnabled,
        keepaliveIntervalSeconds = natPolicy.keepaliveIntervalSeconds.toString(),
        srtpPolicy = srtpPolicy,
        audioCodecs = codecs.audio,
        videoCodecs = codecs.video,
        isDefault = isDefault,
    )
}

/** The draft value a field maps to, so an edit can clear only that field's error. */
internal fun AccountField.valueIn(draft: SipAccountDraft): Any? = when (this) {
    AccountField.LABEL -> draft.label
    AccountField.USERNAME -> draft.username
    AccountField.EXTENSION -> draft.extension
    AccountField.AUTH_USERNAME -> draft.authUsername
    AccountField.PASSWORD -> draft.password
    AccountField.DISPLAY_NAME -> draft.displayName
    AccountField.DOMAIN -> draft.domain
    AccountField.REGISTRAR -> draft.registrar
    AccountField.OUTBOUND_PROXY -> draft.outboundProxy
    AccountField.PORT -> draft.port
    AccountField.TRANSPORT -> draft.transport
    AccountField.REGISTRATION_EXPIRY -> draft.registrationExpirySeconds
    AccountField.STUN_SERVER -> draft.stunServer
    AccountField.TURN_SERVER -> draft.turnServer
    AccountField.TURN_USERNAME -> draft.turnUsername
    AccountField.TURN_PASSWORD -> draft.turnPassword
    AccountField.KEEPALIVE_INTERVAL -> draft.keepaliveIntervalSeconds
    AccountField.AUDIO_CODECS -> draft.audioCodecs
    AccountField.VIDEO_CODECS -> draft.videoCodecs
}

/** Codec choices offered by the editor. */
internal val ALL_AUDIO_CODECS: List<AudioCodec> = AudioCodec.entries
internal val ALL_VIDEO_CODECS: List<VideoCodec> = VideoCodec.entries
internal val ALL_TRANSPORTS: List<Transport> = Transport.entries
internal val ALL_SRTP_POLICIES: List<SrtpPolicy> = SrtpPolicy.entries
