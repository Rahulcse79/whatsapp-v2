package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import com.whatsappv2.domain.engine.SipError
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

/**
 * What the save did about registration.
 *
 * Reported rather than hidden: a save that silently left an account logged out looks
 * identical to one that registered it, and the difference decides whether calls arrive.
 */
sealed interface RegistrationAttempt {

    /**
     * The account was deliberately left as it was — an edit to an account that was
     * already logged out. Saving must not log someone back in behind their back.
     */
    data object NotAttempted : RegistrationAttempt

    /** REGISTER was accepted. */
    data object Succeeded : RegistrationAttempt

    /**
     * The account is saved, but registration failed.
     *
     * Not an error of the save: the account exists, the user's work is not lost, and the
     * failure is something they retry — or fix a password and retry.
     */
    data class Rejected(val cause: SipError) : RegistrationAttempt
}

/** A saved account, plus anything worth telling the user that did not block the save. */
data class SaveAccountResult(
    val account: SipAccount,
    val warnings: List<AccountViolation>,
    /** True when an existing registration was torn down as part of the save. */
    val unregisteredFirst: Boolean,
    /** Whether the saved account was registered, and what happened if it was tried. */
    val registration: RegistrationAttempt,
)

/**
 * Validates a draft, stores it, and logs it in.
 *
 * A real use case rather than a pass-through: it composes validation, the
 * unregister-then-register rule, persistence and registration, and none of those belong
 * in a ViewModel where they would be re-implemented per screen.
 *
 * ## Login is save + register (Task 29)
 *
 * Saving a **new** account registers it. That is what "login" means in this app — there
 * is no separate credential prompt, because the account editor already collected exactly
 * the fields a REGISTER needs.
 *
 * ## Why editing unregisters first, and re-registers after
 *
 * Changing a username, domain, transport or password changes the SIP identity or the
 * credentials behind an existing binding. Registering the new identity without releasing
 * the old one leaves a stale binding on the registrar that keeps receiving calls until
 * it expires — so the far end rings a device that no longer answers. §5.1 calls silent
 * partial re-registration a bug, and this is where that is prevented.
 *
 * The other half of the same rule is that the sequence must **finish**. Unregistering and
 * then not registering again is the same bug seen from the other side: the user pressed
 * Save, not Log out, and would be left unreachable with the UI showing a saved account.
 *
 * Only fields that actually affect the binding trigger the cycle. Renaming an account's
 * label should not drop its registration.
 *
 * ## What is deliberately left alone
 *
 * Editing an account that was **not** registered saves and stops. Registering it would
 * undo a logout the user asked for, and correcting a typo in a logged-out account is not
 * a request to log in.
 */
class SaveAccountUseCase @Inject constructor(
    private val repository: SipAccountRepository,
    private val registrar: SipRegistrar,
    private val login: LoginUseCase,
) {

    suspend operator fun invoke(draft: SipAccountDraft): Outcome<SaveAccountResult, SaveAccountError> {
        val validated = when (val result = AccountValidator.validate(draft)) {
            is Outcome.Failure -> return failure(SaveAccountError.Invalid(result.error))
            is Outcome.Success -> result.value
        }

        val existing = repository.findById(validated.account.id)
        val wasRegistered = existing != null &&
            registrar.registrationState.first()[existing.id]?.isUsable == true
        val mustUnregister = existing != null &&
            wasRegistered &&
            existing.affectsRegistration(validated.account)

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
                    // A new account is a login. An edit that dropped a live binding must
                    // put it back. Anything else is left as the user had it.
                    registration = if (existing == null || mustUnregister) {
                        register(validated.account)
                    } else {
                        RegistrationAttempt.NotAttempted
                    },
                ),
            )
        }
    }

    /**
     * Registers the account as it is now stored.
     *
     * Goes through [LoginUseCase] rather than calling the registrar with the validated
     * draft: the stored copy carries an empty password, so the plaintext the user just
     * typed does not travel any further than the repository that encrypts it.
     */
    private suspend fun register(account: SipAccount): RegistrationAttempt =
        when (val result = login(account.id)) {
            is Outcome.Success -> RegistrationAttempt.Succeeded
            is Outcome.Failure -> when (val error = result.error) {
                // The account was written a moment ago; if it is gone now, something
                // deleted it concurrently, which is a registration that cannot happen
                // rather than a save that failed.
                is LoginError.NotFound -> RegistrationAttempt.Rejected(SipError.UnknownAccount)
                is LoginError.Rejected -> RegistrationAttempt.Rejected(error.cause)
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
