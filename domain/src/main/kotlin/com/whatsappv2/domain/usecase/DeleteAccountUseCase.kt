package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import com.whatsappv2.domain.engine.SipCallController
import com.whatsappv2.domain.engine.SipRegistrar
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.repository.AccountRepositoryError
import com.whatsappv2.domain.repository.SipAccountRepository
import javax.inject.Inject

/** Why an account could not be deleted. */
sealed interface DeleteAccountError {

    /** No such account. Usually a stale reference after a delete elsewhere. */
    data object NotFound : DeleteAccountError

    /**
     * A call is in progress on this account.
     *
     * Refused rather than allowed: deleting the account would pull the credentials out
     * from under a live call, and the call would drop mid-sentence with no explanation.
     * The user is told to end the call first, which is a decision only they can make.
     */
    data class CallInProgress(val activeCalls: Int) : DeleteAccountError

    /** Storage rejected the delete. */
    data class Failed(val detail: String) : DeleteAccountError
}

/**
 * Deletes an account, refusing while it is in use.
 *
 * A real use case rather than a pass-through: it enforces a rule the repository cannot
 * see — the repository knows about storage, not about calls in progress — and it
 * releases the registration before removing the credentials that registration depends on.
 *
 * Order matters. Unregistering after deleting would mean sending `Expires: 0` with
 * credentials that no longer exist, leaving a stale binding on the registrar that keeps
 * ringing a device which can no longer answer.
 */
class DeleteAccountUseCase @Inject constructor(
    private val repository: SipAccountRepository,
    private val registrar: SipRegistrar,
    private val calls: SipCallController,
) {

    suspend operator fun invoke(id: AccountId): Outcome<Unit, DeleteAccountError> {
        repository.findById(id) ?: return failure(DeleteAccountError.NotFound)

        val active = calls.activeCalls.value.count { it.accountId == id }
        if (active > 0) return failure(DeleteAccountError.CallInProgress(active))

        // Release the binding before the credentials it needs are gone.
        registrar.unregister(id)

        return when (val deleted = repository.delete(id)) {
            is Outcome.Success -> success(Unit)
            is Outcome.Failure -> failure(deleted.error.toDeleteError())
        }
    }

    private fun AccountRepositoryError.toDeleteError(): DeleteAccountError = when (this) {
        is AccountRepositoryError.NotFound -> DeleteAccountError.NotFound
        is AccountRepositoryError.StorageFailure -> DeleteAccountError.Failed(detail)
        is AccountRepositoryError.CryptoFailure -> DeleteAccountError.Failed(detail)
        is AccountRepositoryError.CredentialsUnrecoverable ->
            DeleteAccountError.Failed("credentials unreadable")
        is AccountRepositoryError.DuplicateIdentity ->
            DeleteAccountError.Failed("unexpected identity clash while deleting")
    }
}
