package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.engine.SipCallController
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.repository.SipAccountRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Why a call could not be placed, at the granularity the dialer can act on. */
sealed interface PlaceCallError {

    /** No account was chosen and none is marked default, so there is nothing to call from. */
    data object NoAccountAvailable : PlaceCallError

    /** The per-call account override names an account that is not configured. */
    data class UnknownAccount(val id: AccountId) : PlaceCallError

    /** What the user typed is not a callable address. */
    data class InvalidTarget(val input: String) : PlaceCallError

    /** The engine refused. Carries the cause so the dialer can name it. */
    data class Rejected(val cause: SipError) : PlaceCallError
}

/**
 * Places a call, having decided who places it and to where (Task 35).
 *
 * ## Why the resolution lives here
 *
 * Two things have to be worked out before an INVITE means anything, and neither of them
 * needs a SIP stack:
 *
 * **Which account.** The dialer offers a per-call override (Task 36); with none, the
 * default account is used. That is a rule, and a rule in a ViewModel is a rule that the
 * next screen to place a call will implement slightly differently.
 *
 * **What was dialled.** `1001` and `sip:1001@example.com` are the same call. Resolving a
 * bare extension against the chosen account's domain is the only place the two forms are
 * reconciled, so a test can assert the equivalence directly instead of through a stack.
 *
 * Both are pure decisions with a repository read in front of them, which is exactly the
 * shape that survives being unit-tested with no engine at all.
 */
class PlaceCallUseCase @Inject constructor(
    private val accounts: SipAccountRepository,
    private val calls: SipCallController,
) {

    /**
     * @param input a bare extension or a full SIP URI, as typed.
     * @param accountOverride the per-call account, or null to use the default.
     */
    suspend operator fun invoke(
        input: String,
        accountOverride: AccountId? = null,
        media: MediaProfile = MediaProfile.AUDIO,
    ): Outcome<CallId, PlaceCallError> {
        val account = when (accountOverride) {
            null -> accounts.observeDefaultAccount().first()
                ?: return failure(PlaceCallError.NoAccountAvailable)

            else -> accounts.findById(accountOverride)
                ?: return failure(PlaceCallError.UnknownAccount(accountOverride))
        }

        val target = resolveTarget(input, account.domain)
            ?: return failure(PlaceCallError.InvalidTarget(input))

        return when (val result = calls.placeCall(account.id, target, media)) {
            is Outcome.Success -> result
            is Outcome.Failure -> failure(PlaceCallError.Rejected(result.error))
        }
    }

    /**
     * Turns what was typed into an address.
     *
     * A bare extension is completed against the account's own domain, which is what makes
     * dialling `1001` work at all — a SIP URI has no meaning without one. Anything that
     * already looks like a URI is parsed as written, so a call to another domain is not
     * silently rewritten to this account's.
     */
    private fun resolveTarget(input: String, accountDomain: String): SipUri? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        val qualified = when {
            // Already a URI. Parsed as written, so a call to another domain is not
            // silently rewritten to this account's.
            trimmed.startsWith(SIP_SCHEME) || trimmed.startsWith(SIPS_SCHEME) -> trimmed
            // A user@host with no scheme - the host is explicit, only the scheme is not.
            trimmed.contains('@') -> "$SIP_SCHEME$trimmed"
            // A bare extension. This is the line that makes dialling 1001 work: a SIP URI
            // has no meaning without a domain, and the account's is the right one.
            else -> "$SIP_SCHEME$trimmed@$accountDomain"
        }
        return SipUri.parse(qualified).getOrNull()
    }

    private companion object {
        const val SIP_SCHEME = "sip:"
        const val SIPS_SCHEME = "sips:"
    }
}
