package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.errorOrNull
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.repository.SipAccountRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Puts back, at process start, every registration the user had and never gave up.
 *
 * ## The defect this closes
 *
 * Registration lived only in the engine's memory. Every process death — the OS reclaiming
 * a backgrounded app, a native crash, an install over the top — left every account
 * *Offline* until a person opened the app and pressed *Log in*, and no call could arrive
 * until they did. On a handset that is meant to sit on a desk and ring, that is the
 * phone being off for most of the day. Measured on a Zebra TC15 on 2026-09-10, three
 * times in one evening.
 *
 * ## What it restores, and what it deliberately does not
 *
 * Only accounts with [com.whatsappv2.domain.model.SipAccount.registrationWanted] — the
 * ones logged in and not logged out — through [LoginUseCase], so a restored registration
 * is the same code path as a pressed button. An account the user logged out stays out:
 * restoring it would undo a choice, which is the one thing a start-up job must never do.
 * A failure on one account is returned and does not stop the others.
 *
 * Runs once per process, from the application's own scope, after the engine has started.
 */
class RestoreRegistrationsUseCase @Inject constructor(
    private val repository: SipAccountRepository,
    private val login: LoginUseCase,
) {

    /** @return the accounts whose registration could not be restored, with the reason. */
    suspend operator fun invoke(): Map<AccountId, LoginError> {
        val wanted = repository.observeAccounts().first().filter { it.registrationWanted }
        return wanted.mapNotNull { account ->
            login(account.id).errorOrNull()?.let { account.id to it }
        }.toMap()
    }
}
