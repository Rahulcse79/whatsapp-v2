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
import com.whatsappv2.domain.usecase.RegistrationAttempt
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

    /**
     * The account was stored.
     *
     * [registration] says what happened to the account's session, because "saved" alone
     * is not the whole story: an edit to a registered account releases the old binding
     * and takes out a new one, and if that second half failed the user is now unreachable
     * and needs to be told. §5.1 calls a silent partial re-registration a bug.
     */
    data class Saved(
        val label: String,
        val unregisteredFirst: Boolean,
        val registration: RegistrationAttempt,
    ) : AccountEditorEvent

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
 * It is filled in, with the stored value decrypted on load. The repository still keeps
 * passwords off every `SipAccount` it publishes on a flow, so this screen fetches one
 * deliberately through `credentialsFor` and holds it only while it is open.
 *
 * The blank field that came before was the bigger problem: `AccountValidator` rejects an
 * empty password, so every edit - a display name, a transport - failed until the user
 * retyped a credential they had no reason to remember. See [storedSecrets].
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
                val stored = storedSecrets(id)
                AccountEditorUiState(
                    draft = account.toDraft(stored.password, stored.turnPassword),
                    isNewAccount = false,
                )
            }
        }
    }

    /** The decrypted secrets an editor opens with, or empties when they cannot be read. */
    private data class StoredSecrets(
        val password: Secret = Secret.EMPTY,
        val turnPassword: Secret = Secret.EMPTY,
    )

    /**
     * The account's stored secrets, for the editor to open with.
     *
     * ## Why the field is filled rather than blank
     *
     * It used to be blank, on the reasoning that a decrypted credential should not sit in
     * memory for the life of a screen. The cost of that was paid on every single edit:
     * [com.whatsappv2.domain.validation.AccountValidator] rejects an empty password, so
     * changing a display name or a transport failed validation until the user retyped the
     * password from memory — and the password is the one field they are least likely to
     * remember. Read as "the password is cleared every time I edit the account", which is
     * what it amounts to from the outside.
     *
     * So the editor now opens with the real value, decrypted once here rather than left
     * for the user to reconstruct. The credential is still encrypted at rest and still
     * absent from every `SipAccount` the repository publishes on a flow — this is one
     * screen, holding it for as long as that screen is open, which is the same window the
     * user would otherwise have had it on screen for anyway.
     *
     * An unreadable credential is not an error here: the Keystore key can be gone, and the
     * honest response is the empty field that asks for it again rather than a screen that
     * refuses to open.
     */
    private suspend fun storedSecrets(id: AccountId): StoredSecrets =
        when (val credentials = repository.credentialsFor(id)) {
            is Outcome.Success -> StoredSecrets(
                password = credentials.value.password,
                turnPassword = credentials.value.turnPassword ?: Secret.EMPTY,
            )

            is Outcome.Failure -> StoredSecrets()
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
                            registration = result.value.registration,
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

    private fun newDraft() = SipAccountDraft(
        id = AccountId(UUID.randomUUID().toString()),
        password = DEFAULT_PASSWORD,
    )

    /**
     * Turns a stored account back into an editable draft.
     *
     * [password] and [turnPassword] are the decrypted stored values — see [storedSecrets]
     * for why they are filled rather than blank.
     */
    private fun SipAccount.toDraft(password: Secret, turnPassword: Secret) = SipAccountDraft(
        id = id,
        label = label,
        username = username,
        extension = extension.orEmpty(),
        authUsername = authUsername.orEmpty(),
        password = password,
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
        turnPassword = turnPassword,
        iceEnabled = natPolicy.iceEnabled,
        stunEnabled = natPolicy.stunEnabled,
        keepaliveIntervalSeconds = natPolicy.keepaliveIntervalSeconds.toString(),
        srtpPolicy = srtpPolicy,
        audioCodecs = codecs.audio,
        videoCodecs = codecs.video,
        isDefault = isDefault,
    )

    private companion object {
        /**
         * What a brand-new account's password field starts as.
         *
         * The deployment this app ships against provisions its extensions with `1234`, so
         * a blank field made every first run a retype of a value the server already knew.
         * It is a starting point and not a policy: the field is editable, validated like
         * any other, and an account saved with it is stored encrypted exactly as one the
         * user typed in full.
         */
        val DEFAULT_PASSWORD: Secret = Secret("1234")
    }
}

/**
 * The draft value each field maps to, so an edit can clear only that field's error.
 *
 * A map rather than a `when`: nineteen branches is a cyclomatic-complexity finding, and a
 * lookup table says the same thing more directly — this is data, not control flow.
 */
private val FIELD_VALUES: Map<AccountField, (SipAccountDraft) -> Any?> = mapOf(
    AccountField.LABEL to { it.label },
    AccountField.USERNAME to { it.username },
    AccountField.EXTENSION to { it.extension },
    AccountField.AUTH_USERNAME to { it.authUsername },
    AccountField.PASSWORD to { it.password },
    AccountField.DISPLAY_NAME to { it.displayName },
    AccountField.DOMAIN to { it.domain },
    AccountField.REGISTRAR to { it.registrar },
    AccountField.OUTBOUND_PROXY to { it.outboundProxy },
    AccountField.PORT to { it.port },
    AccountField.TRANSPORT to { it.transport },
    AccountField.REGISTRATION_EXPIRY to { it.registrationExpirySeconds },
    AccountField.STUN_SERVER to { it.stunServer },
    AccountField.TURN_SERVER to { it.turnServer },
    AccountField.TURN_USERNAME to { it.turnUsername },
    AccountField.TURN_PASSWORD to { it.turnPassword },
    AccountField.KEEPALIVE_INTERVAL to { it.keepaliveIntervalSeconds },
    AccountField.AUDIO_CODECS to { it.audioCodecs },
    AccountField.VIDEO_CODECS to { it.videoCodecs },
)

internal fun AccountField.valueIn(draft: SipAccountDraft): Any? = FIELD_VALUES[this]?.invoke(draft)

/** Codec choices offered by the editor. */
internal val ALL_AUDIO_CODECS: List<AudioCodec> = AudioCodec.entries
internal val ALL_VIDEO_CODECS: List<VideoCodec> = VideoCodec.entries
internal val ALL_TRANSPORTS: List<Transport> = Transport.entries
internal val ALL_SRTP_POLICIES: List<SrtpPolicy> = SrtpPolicy.entries
