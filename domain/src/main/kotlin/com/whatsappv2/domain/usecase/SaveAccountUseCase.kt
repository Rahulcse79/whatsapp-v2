package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import com.whatsappv2.domain.engine.SipRegistrar
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.repository.AccountRepositoryError
import com.whatsappv2.domain.repository.SipAccountRepository
import com.whatsappv2.domain.validation.AccountValidator
import com.whatsappv2.domain.validation.AccountViolation
import com.whatsappv2.domain.validation.SipAccountDraft
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Why saving an account failed. */
sealed interface SaveAccountError {

    /** The form is invalid. Carries every violation so the UI can mark each field. */
    data class Invalid(val violations: List<AccountViolation>) : SaveAccountError

    /** Another account already registers this identity. */
    data class DuplicateIdentity(val username: String, val domain: String) : SaveAccountError

    /** The credentials could not be encrypted; the user must enter the password again. */
    data object CredentialsUnrecoverable : SaveAccountError

    /** Storage or the Keystore failed in a way the user cannot resolve by retyping. */
    data class Failed(val detail: String) : SaveAccountError
}

/** A saved account, plus anything worth telling the user that did not block the save. */
data class SaveAccountResult(
    val account: SipAccount,
    val warnings: List<AccountViolation>,
    /** True when an existing registration was torn down as part of the save. */
    val unregisteredFirst: Boolean,
)

/**
 * Validates a draft and stores it.
 *
 * A real use case rather than a pass-through: it composes validation, the
 * unregister-before-re-register rule, and persistence, and none of those belong in a
 * ViewModel where they would be re-implemented per screen.
 *
 * ## Why editing unregisters first
 *
 * Changing a username, domain, transport or password changes the SIP identity or the
 * credentials behind an existing binding. Registering the new identity without releasing
 * the old one leaves a stale binding on the registrar that keeps receiving calls until
 * it expires — so the far end rings a device that no longer answers. §5.1 calls silent
 * partial re-registration a bug, and this is where that is prevented.
 *
 * Only fields that actually affect the binding trigger it. Renaming an account's label
 * should not drop its registration.
 */
class SaveAccountUseCase @Inject constructor(
    private val repository: SipAccountRepository,
    private val registrar: SipRegistrar,
) {

    suspend operator fun invoke(draft: SipAccountDraft): Outcome<SaveAccountResult, SaveAccountError> {
        val validated = when (val result = AccountValidator.validate(draft)) {
            is Outcome.Failure -> return failure(SaveAccountError.Invalid(result.error))
            is Outcome.Success -> result.value
        }

        val existing = repository.findById(validated.account.id)
        val mustUnregister = existing != null &&
            existing.affectsRegistration(validated.account) &&
            registrar.registrationState.first()[existing.id]?.isUsable == true

        if (mustUnregister) {
            // Release the old binding BEFORE storing the new identity, so a failure here
            // leaves the account exactly as it was rather than half-migrated.
            registrar.unregister(checkNotNull(existing).id)
        }

        return when (val saved = repository.save(validated.account)) {
            is Outcome.Failure -> failure(saved.error.toSaveError())
            is Outcome.Success -> success(
                SaveAccountResult(
                    account = validated.account,
                    warnings = validated.warnings,
                    unregisteredFirst = mustUnregister,
                ),
            )
        }
    }

    /**
     * True when the change would invalidate the current registration.
     *
     * Deliberately narrow. Re-registering on every edit would drop a working binding
     * because someone corrected a display name.
     */
    private fun SipAccount.affectsRegistration(updated: SipAccount): Boolean =
        username != updated.username ||
            domain != updated.domain ||
            effectiveAuthUsername != updated.effectiveAuthUsername ||
            transport != updated.transport ||
            effectivePort != updated.effectivePort ||
            effectiveRegistrar != updated.effectiveRegistrar ||
            registrationExpirySeconds != updated.registrationExpirySeconds ||
            // A password change cannot be compared directly - an observed account carries
            // an empty one - so a non-empty password on the update means the user typed a
            // new value, which must be re-authenticated.
            updated.password.length > 0

    private fun AccountRepositoryError.toSaveError(): SaveAccountError = when (this) {
        is AccountRepositoryError.DuplicateIdentity -> SaveAccountError.DuplicateIdentity(username, domain)
        is AccountRepositoryError.CredentialsUnrecoverable -> SaveAccountError.CredentialsUnrecoverable
        is AccountRepositoryError.CryptoFailure -> SaveAccountError.Failed(detail)
        is AccountRepositoryError.StorageFailure -> SaveAccountError.Failed(detail)
        is AccountRepositoryError.NotFound -> SaveAccountError.Failed("account disappeared while saving")
    }
}
