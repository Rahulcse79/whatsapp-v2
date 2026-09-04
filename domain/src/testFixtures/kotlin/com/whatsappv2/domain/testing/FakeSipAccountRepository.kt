package com.whatsappv2.domain.testing

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.repository.AccountRepositoryError
import com.whatsappv2.domain.repository.SipAccountRepository
import com.whatsappv2.domain.repository.SipCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * An in-memory [SipAccountRepository].
 *
 * Mirrors the real one where it matters: observed accounts carry an **empty** password,
 * the first account saved becomes the default, and deleting the default promotes another.
 * A fake that skipped those would let a use-case test pass against behaviour production
 * does not have.
 */
class FakeSipAccountRepository : SipAccountRepository {

    private val accounts = MutableStateFlow<List<SipAccount>>(emptyList())
    private val secrets = mutableMapOf<AccountId, Secret>()

    /** When set, the next mutating call fails with this. */
    var nextFailure: AccountRepositoryError? = null

    /** Recorded in call order, so a test can assert sequencing. */
    val savedIds: MutableList<AccountId> = mutableListOf()
    val deletedIds: MutableList<AccountId> = mutableListOf()

    /** Seeds an account without going through [save], for arranging a test. */
    fun given(vararg seeded: SipAccount) = apply {
        seeded.forEach { account ->
            secrets[account.id] = account.password
            accounts.value = accounts.value.filterNot { it.id == account.id } +
                account.copy(password = Secret.EMPTY)
        }
        ensureOneDefault()
    }

    override fun observeAccounts(): Flow<List<SipAccount>> = accounts

    override fun observeAccount(id: AccountId): Flow<SipAccount?> =
        accounts.map { list -> list.firstOrNull { it.id == id } }

    override fun observeDefaultAccount(): Flow<SipAccount?> =
        accounts.map { list -> list.firstOrNull { it.isDefault } }

    override suspend fun findById(id: AccountId): SipAccount? =
        accounts.value.firstOrNull { it.id == id }

    override suspend fun count(): Int = accounts.value.size

    override suspend fun save(account: SipAccount): Outcome<Unit, AccountRepositoryError> {
        nextFailure?.let { nextFailure = null; return failure(it) }

        val clash = accounts.value.firstOrNull {
            it.username == account.username && it.domain == account.domain && it.id != account.id
        }
        if (clash != null) {
            return failure(AccountRepositoryError.DuplicateIdentity(account.username, account.domain))
        }

        savedIds += account.id
        if (account.password.length > 0) secrets[account.id] = account.password
        accounts.value = accounts.value.filterNot { it.id == account.id } +
            account.copy(password = Secret.EMPTY)
        ensureOneDefault()
        return success(Unit)
    }

    override suspend fun delete(id: AccountId): Outcome<Unit, AccountRepositoryError> {
        nextFailure?.let { nextFailure = null; return failure(it) }
        if (accounts.value.none { it.id == id }) return failure(AccountRepositoryError.NotFound)

        deletedIds += id
        secrets -= id
        accounts.value = accounts.value.filterNot { it.id == id }
        ensureOneDefault()
        return success(Unit)
    }

    override suspend fun setDefault(id: AccountId): Outcome<Unit, AccountRepositoryError> {
        nextFailure?.let { nextFailure = null; return failure(it) }
        if (accounts.value.none { it.id == id }) return failure(AccountRepositoryError.NotFound)

        accounts.value = accounts.value.map { it.copy(isDefault = it.id == id) }
        return success(Unit)
    }

    override suspend fun credentialsFor(id: AccountId): Outcome<SipCredentials, AccountRepositoryError> {
        nextFailure?.let { nextFailure = null; return failure(it) }
        val secret = secrets[id] ?: return failure(AccountRepositoryError.NotFound)
        return success(SipCredentials(id, secret, turnPassword = null))
    }

    /** The app must never hold accounts with no default: it could not place a call. */
    private fun ensureOneDefault() {
        val current = accounts.value
        if (current.isEmpty() || current.any { it.isDefault }) return
        accounts.value = current.mapIndexed { index, account ->
            account.copy(isDefault = index == 0)
        }
    }
}
