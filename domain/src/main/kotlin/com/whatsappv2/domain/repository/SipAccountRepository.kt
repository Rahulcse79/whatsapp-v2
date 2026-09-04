package com.whatsappv2.domain.repository

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.SipAccount
import kotlinx.coroutines.flow.Flow

/**
 * Why an account operation failed.
 *
 * Typed rather than a message: the UI owns the wording, and Task 21 must be able to put
 * a duplicate-identity error on the username field rather than in a generic toast.
 */
sealed interface AccountRepositoryError {

    /** No account with that id. Usually a stale reference after a delete. */
    data object NotFound : AccountRepositoryError

    /**
     * Another account already registers this `user@domain`.
     *
     * Two accounts sharing an identity would fight over the same registrar binding, so
     * this is refused rather than allowed to produce intermittent registration failures.
     */
    data class DuplicateIdentity(val username: String, val domain: String) : AccountRepositoryError

    /**
     * The stored password cannot be decrypted — the Keystore key was lost or the data
     * was altered.
     *
     * Recoverable only by the user entering the password again, which is why it is
     * distinct from [StorageFailure]: it drives a prompt, not an error message.
     */
    data object CredentialsUnrecoverable : AccountRepositoryError

    /** Encryption or the Keystore failed in a way re-entry cannot fix. */
    data class CryptoFailure(val detail: String) : AccountRepositoryError

    /** The database rejected the operation. */
    data class StorageFailure(val detail: String) : AccountRepositoryError

    /** True when the only way forward is to ask the user for the password again. */
    val requiresCredentialReEntry: Boolean get() = this is CredentialsUnrecoverable
}

/**
 * The decrypted secrets for one account.
 *
 * Returned only from [SipAccountRepository.credentialsFor], never carried on a
 * [SipAccount]: accounts are observed through long-lived flows and held by ViewModels
 * across configuration changes, so a password on one would sit decrypted in memory for
 * the lifetime of a screen rather than the lifetime of a registration.
 */
data class SipCredentials(
    val accountId: AccountId,
    val password: Secret,
    val turnPassword: Secret?,
)

/**
 * Stored SIP accounts.
 *
 * ## Passwords are not part of the observable model
 *
 * Every read path returns [SipAccount] with an **empty** password. The real value is
 * fetched on demand through [credentialsFor], decrypted for the duration of that call.
 * This is deliberate and is what Task 18 requires: a decrypted credential must not
 * outlive the operation that needs it, and anything reachable from a `Flow` outlives
 * almost everything.
 *
 * An empty password is unambiguous, because [com.whatsappv2.domain.validation.AccountValidator]
 * rejects one — so a blank value read back always means "not loaded", never "the user
 * set an empty password".
 */
interface SipAccountRepository {

    /** All accounts, oldest first. Passwords are empty; see [credentialsFor]. */
    fun observeAccounts(): Flow<List<SipAccount>>

    /** One account, or null once it is deleted. */
    fun observeAccount(id: AccountId): Flow<SipAccount?>

    /** The account outgoing calls use by default. */
    fun observeDefaultAccount(): Flow<SipAccount?>

    suspend fun findById(id: AccountId): SipAccount?

    /** Number of stored accounts. */
    suspend fun count(): Int

    /**
     * Creates or replaces an account, encrypting its credentials.
     *
     * The first account saved becomes the default: an app holding accounts but no
     * default cannot place a call.
     */
    suspend fun save(account: SipAccount): Outcome<Unit, AccountRepositoryError>

    /**
     * Deletes an account and promotes another to default if it was the default one.
     *
     * Refusing to leave the app with accounts but no default is the repository's job,
     * not the caller's — every caller would otherwise have to remember it.
     */
    suspend fun delete(id: AccountId): Outcome<Unit, AccountRepositoryError>

    suspend fun setDefault(id: AccountId): Outcome<Unit, AccountRepositoryError>

    /**
     * Decrypts and returns the credentials for [id].
     *
     * Call this immediately before they are needed — building a REGISTER, say — and do
     * not retain the result. Fails with
     * [AccountRepositoryError.CredentialsUnrecoverable] when the key is gone, which the
     * UI turns into a prompt to re-enter the password.
     */
    suspend fun credentialsFor(id: AccountId): Outcome<SipCredentials, AccountRepositoryError>
}
