package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import com.whatsappv2.domain.engine.SipCallController
import com.whatsappv2.domain.engine.SipRegistrar
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.repository.SipAccountRepository
import javax.inject.Inject

/** Why logging out was refused. */
sealed interface LogoutError {

    /** No such account. */
    data object NotFound : LogoutError

    /**
     * A call is in progress on this account.
     *
     * Refused rather than allowed, for the same reason as
     * [DeleteAccountError.CallInProgress]: the registration is what carries the dialog's
     * in-progress requests, so tearing it down would drop the call mid-sentence. Ending
     * the call is a decision only the user can make.
     */
    data class CallInProgress(val activeCalls: Int) : LogoutError
}

/**
 * Logs an account out while keeping it configured (Task 29, §5.1).
 *
 * Logout is **not** a delete. The row survives, so the account is still listed and can be
 * logged back in without the user retyping anything — the encrypted password stays at
 * rest in the database. What goes away is the registration and every decrypted copy of
 * the credentials.
 *
 * ## What "wipe credentials from memory" means here
 *
 * Nothing in this layer holds a password to wipe, and that is the design rather than an
 * accident: a decrypted credential exists only inside
 * [SipRegistrar.register], between the repository handing it over and the stack storing
 * it. So the wipe is [SipRegistrar.unregister]'s job — it drops the account from the
 * stack **and** the credentials the stack kept for it — and this use case's contribution
 * is to order it correctly and refuse when a call would be broken by it.
 *
 * ## Stopping the foreground service
 *
 * Task 29 also asks for the service to stop. It is deliberately not stopped from here:
 * `:domain` has no Android in it, and more importantly the service already decides this
 * for itself from `ServiceRunPolicy`, which sees registrations *and* calls. A logout that
 * stopped the service directly would kill it while another account was still registered.
 * Once the last registration is gone and no call is up, that policy returns `Stop` and
 * the service ends itself — asserted in `RegistrationServiceTest`.
 *
 * ## Why an engine failure does not fail the logout
 *
 * The user asked to be logged out. The engine drops the binding and the credentials
 * locally whatever the registrar says, so reporting failure because a server did not
 * answer would leave the UI claiming the account is still logged in when it is not.
 */
class LogoutUseCase @Inject constructor(
    private val repository: SipAccountRepository,
    private val registrar: SipRegistrar,
    private val calls: SipCallController,
) {

    suspend operator fun invoke(id: AccountId): Outcome<Unit, LogoutError> {
        repository.findById(id) ?: return failure(LogoutError.NotFound)

        val active = calls.activeCalls.value.count { it.accountId == id }
        if (active > 0) return failure(LogoutError.CallInProgress(active))

        // Clean unregister: `Expires: 0`, acknowledged by the registrar before this
        // returns, so a caller may stop the service afterwards without cutting it off.
        registrar.unregister(id)

        // The row is deliberately left alone. That is the whole difference between this
        // and DeleteAccountUseCase.
        return success(Unit)
    }
}
