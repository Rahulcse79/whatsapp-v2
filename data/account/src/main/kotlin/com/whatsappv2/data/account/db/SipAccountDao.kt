package com.whatsappv2.data.account.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Storage operations for SIP accounts.
 *
 * Reads are [Flow]s so a screen re-renders when an account changes anywhere — editing an
 * account in one place and having a stale copy on another screen is the failure this
 * avoids. Writes are `suspend`, so no caller can block the main thread by accident.
 */
@Dao
interface SipAccountDao {

    /** All accounts, newest last, as a stream. */
    @Query("SELECT * FROM sip_accounts ORDER BY created_at_epoch_millis ASC")
    fun observeAll(): Flow<List<SipAccountEntity>>

    /** One account as a stream. Emits null once it is deleted. */
    @Query("SELECT * FROM sip_accounts WHERE id = :id")
    fun observeById(id: String): Flow<SipAccountEntity?>

    /** The default account, or null when none is set. */
    @Query("SELECT * FROM sip_accounts WHERE is_default = 1 LIMIT 1")
    fun observeDefault(): Flow<SipAccountEntity?>

    @Query("SELECT * FROM sip_accounts WHERE id = :id")
    suspend fun findById(id: String): SipAccountEntity?

    /** Used to reject a duplicate identity before the unique index raises a conflict. */
    @Query("SELECT * FROM sip_accounts WHERE username = :username AND domain = :domain LIMIT 1")
    suspend fun findByIdentity(username: String, domain: String): SipAccountEntity?

    @Query("SELECT COUNT(*) FROM sip_accounts")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(account: SipAccountEntity)

    @Update
    suspend fun update(account: SipAccountEntity)

    @Delete
    suspend fun delete(account: SipAccountEntity)

    @Query("DELETE FROM sip_accounts WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Makes [id] the only default account.
     *
     * One statement per step inside a single transaction: doing it in two separate calls
     * leaves a window in which no account is default, and a call placed in that window
     * would have no account to place it on.
     */
    @Transaction
    suspend fun setDefault(id: String) {
        clearDefaults()
        markDefault(id)
    }

    /**
     * Deletes an account and promotes another to default if it was the default one.
     *
     * In one transaction for the same reason: an app left with accounts but no default
     * cannot place a call, and a crash between the two steps would leave exactly that.
     */
    @Transaction
    suspend fun deleteAndPromoteDefault(id: String) {
        val existing = findById(id) ?: return
        deleteById(id)
        if (existing.isDefault) {
            oldestId()?.let { markDefault(it) }
        }
    }

    @Query("UPDATE sip_accounts SET is_default = 0 WHERE is_default = 1")
    suspend fun clearDefaults()

    @Query("UPDATE sip_accounts SET is_default = 1 WHERE id = :id")
    suspend fun markDefault(id: String)

    @Query("SELECT id FROM sip_accounts ORDER BY created_at_epoch_millis ASC LIMIT 1")
    suspend fun oldestId(): String?
}
