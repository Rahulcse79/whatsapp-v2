package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.engine.SipRegistrar
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.repository.SipAccountRepository
import javax.inject.Inject

/** Why logging in failed. */
sealed interface LoginError {

    /** No such account. Usually a stale reference after a delete elsewhere. */
    data object NotFound : LoginError

    /**
     * The registrar refused, or could not be reached.
     *
     * Carries the [SipError] rather than a message so the caller can tell "your password
     * is wrong" from "the server is down" — one of those the user can fix.
     */
    data class Rejected(val cause: SipError) : LoginError
}

/**
 * Registers a stored account: the second half of "login = save + register" (Task 29).
 *
 * ## Why it takes an id rather than an account
 *
 * It re-reads the account from the repository, which returns it with an **empty**
 * password. That copy is what goes to the engine, which fetches and decrypts the real
 * credentials itself, immediately before building the REGISTER.
 *
 * Passing a caller's [com.whatsappv2.domain.model.SipAccount] instead would work — the
 * engine ignores the password on it — but it would mean a decrypted credential travelling
 * through a use case that has no need of one, and the rule in Task 18 is that a decrypted
 * credential must not outlive the operation that needs it. Taking an id makes that
 * impossible rather than merely unlikely.
 *
 * ## What it is not
 *
 * Not a retry loop. It reports the outcome of one attempt;
 * [com.whatsappv2.domain.registration.RegistrationBackoff] decides what happens after a
 * failure, and mixing the two produces two competing schedules for the same account.
 */
class LoginUseCase @Inject constructor(
    private val repository: SipAccountRepository,
    private val registrar: SipRegistrar,
) {

    suspend operator fun invoke(id: AccountId): Outcome<Unit, LoginError> {
        val account = repository.findById(id) ?: return failure(LoginError.NotFound)

        return when (val result = registrar.register(account)) {
            is Outcome.Success -> success(Unit)
            is Outcome.Failure -> failure(LoginError.Rejected(result.error))
        }
    }
}
