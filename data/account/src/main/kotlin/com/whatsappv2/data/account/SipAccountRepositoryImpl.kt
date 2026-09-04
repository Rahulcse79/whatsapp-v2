package com.whatsappv2.data.account

import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.core.common.time.Clock
import com.whatsappv2.data.account.crypto.CipherError
import com.whatsappv2.data.account.crypto.CredentialCipher
import com.whatsappv2.data.account.db.SipAccountDao
import com.whatsappv2.data.account.db.SipAccountEntity
import com.whatsappv2.data.account.mapper.AccountMapper
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.repository.AccountRepositoryError
import com.whatsappv2.domain.repository.SipAccountRepository
import com.whatsappv2.domain.repository.SipCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed accounts, with encryption applied at this boundary.
 *
 * This is the only class that handles a plaintext credential. Above it the domain sees
 * [SipAccount] with an empty password; below it the database sees ciphertext. Keeping
 * both translations here means there is one place to audit rather than one per caller.
 *
 * Mapping failures drop the row rather than throwing. These flows are collected by the
 * UI, and a corrupt row after a bad migration should cost one account, not crash the
 * screen listing them — but it is logged at error level, because silently vanishing data
 * is its own kind of bug.
 */
@Singleton
class SipAccountRepositoryImpl @Inject constructor(
    private val dao: SipAccountDao,
    private val cipher: CredentialCipher,
    private val clock: Clock,
    private val logger: Logger,
) : SipAccountRepository {

    override fun observeAccounts(): Flow<List<SipAccount>> =
        dao.observeAll().map { rows -> rows.mapNotNull(::toDomainLogging) }

    override fun observeAccount(id: AccountId): Flow<SipAccount?> =
        dao.observeById(id.value).map { it?.let(::toDomainLogging) }

    override fun observeDefaultAccount(): Flow<SipAccount?> =
        dao.observeDefault().map { it?.let(::toDomainLogging) }

    override suspend fun findById(id: AccountId): SipAccount? =
        dao.findById(id.value)?.let(::toDomainLogging)

    override suspend fun count(): Int = dao.count()

    override suspend fun save(account: SipAccount): Outcome<Unit, AccountRepositoryError> {
        val clash = dao.findByIdentity(account.username, account.domain)
        if (clash != null && clash.id != account.id.value) {
            return failure(AccountRepositoryError.DuplicateIdentity(account.username, account.domain))
        }

        val password = cipher.encrypt(account.password)
        if (password is Outcome.Failure) return failure(password.error.toRepositoryError())

        val turnPassword = account.turn?.password?.takeIf { !it.isEmpty }?.let { secret ->
            when (val encrypted = cipher.encrypt(secret)) {
                is Outcome.Failure -> return failure(encrypted.error.toRepositoryError())
                is Outcome.Success -> encrypted.value
            }
        }

        val existing = dao.findById(account.id.value)
        val entity = AccountMapper.toEntity(
            account = account,
            passwordCiphertext = (password as Outcome.Success).value,
            turnPasswordCiphertext = turnPassword,
            // Creation time is preserved on update so the list order does not jump when
            // an account is edited.
            createdAtEpochMillis = existing?.createdAtEpochMillis ?: clock.nowEpochMillis(),
        )

        return runCatchingStorage {
            if (existing == null) dao.insert(entity) else dao.update(entity)
            // The first account must become the default: an app holding accounts but no
            // default cannot place a call, and no caller should have to remember that.
            if (entity.isDefault || dao.count() == 1) dao.setDefault(entity.id)
        }
    }

    override suspend fun delete(id: AccountId): Outcome<Unit, AccountRepositoryError> {
        dao.findById(id.value) ?: return failure(AccountRepositoryError.NotFound)
        return runCatchingStorage { dao.deleteAndPromoteDefault(id.value) }
    }

    override suspend fun setDefault(id: AccountId): Outcome<Unit, AccountRepositoryError> {
        dao.findById(id.value) ?: return failure(AccountRepositoryError.NotFound)
        return runCatchingStorage { dao.setDefault(id.value) }
    }

    override suspend fun credentialsFor(id: AccountId): Outcome<SipCredentials, AccountRepositoryError> {
        val row = dao.findById(id.value) ?: return failure(AccountRepositoryError.NotFound)

        val password = when (val decrypted = cipher.decrypt(row.passwordCiphertext)) {
            is Outcome.Failure -> return failure(decrypted.error.toRepositoryError())
            is Outcome.Success -> decrypted.value
        }

        val turnPassword = row.turnPasswordCiphertext?.let { stored ->
            when (val decrypted = cipher.decrypt(stored)) {
                is Outcome.Failure -> return failure(decrypted.error.toRepositoryError())
                is Outcome.Success -> decrypted.value
            }
        }

        return success(SipCredentials(id, password, turnPassword))
    }

    private fun toDomainLogging(entity: SipAccountEntity): SipAccount? =
        AccountMapper.toDomain(entity).also {
            if (it == null) {
                // The id only - never a credential, and not the SIP identity (§7).
                logger.error(TAG, "Dropping unreadable account row ${entity.id}")
            }
        }

    private inline fun runCatchingStorage(
        block: () -> Unit,
    ): Outcome<Unit, AccountRepositoryError> = try {
        block()
        success(Unit)
    } catch (e: android.database.sqlite.SQLiteException) {
        failure(AccountRepositoryError.StorageFailure(e.javaClass.simpleName))
    }

    /**
     * Maps a cipher failure onto the repository's vocabulary.
     *
     * The distinction that matters: a lost key means "ask the user for the password
     * again", while a Keystore provider failure means retyping would not help either.
     * Collapsing them turns a recoverable prompt into a dead end.
     */
    private fun CipherError.toRepositoryError(): AccountRepositoryError = when {
        requiresReEntry -> AccountRepositoryError.CredentialsUnrecoverable
        this is CipherError.KeyUnavailable -> AccountRepositoryError.CryptoFailure(detail)
        this is CipherError.MalformedCiphertext -> AccountRepositoryError.CryptoFailure(detail)
        this is CipherError.Unexpected -> AccountRepositoryError.CryptoFailure(detail)
        else -> AccountRepositoryError.CryptoFailure(toString())
    }

    private companion object {
        const val TAG = "SipAccountRepository"
    }
}
