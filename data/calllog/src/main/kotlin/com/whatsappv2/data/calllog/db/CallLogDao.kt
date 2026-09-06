package com.whatsappv2.data.calllog.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Storage operations for the call log.
 *
 * Reads are [Flow]s so the history screen updates when a call ends rather than when the
 * user next opens it, which is Task 48's third done-when. Writes are `suspend`.
 *
 * Newest first, everywhere. A call log is read from the top — the call you want is nearly
 * always the last one — and ordering it once here means no caller can render it the other
 * way round by forgetting.
 */
@Dao
interface CallLogDao {

    @Query("SELECT * FROM call_log ORDER BY started_at_epoch_millis DESC, id DESC")
    fun observeAll(): Flow<List<CallLogEntity>>

    /**
     * Inbound calls that were never answered.
     *
     * Asked of the index rather than filtered after reading, so the missed-call tab pages
     * over missed calls instead of paging over everything and showing a short page
     * whenever a page happened to hold few of them.
     */
    @Query(
        """
        SELECT * FROM call_log
        WHERE direction = 'INCOMING' AND answered_at_epoch_millis IS NULL
        ORDER BY started_at_epoch_millis DESC, id DESC
        """,
    )
    fun observeMissed(): Flow<List<CallLogEntity>>

    /**
     * One page, newest first.
     *
     * `:missedOnly` folds both filters into one statement rather than two near-identical
     * queries: the pair would drift the first time the ordering changed in one of them.
     */
    @Query(
        """
        SELECT * FROM call_log
        WHERE :missedOnly = 0
           OR (direction = 'INCOMING' AND answered_at_epoch_millis IS NULL)
        ORDER BY started_at_epoch_millis DESC, id DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun page(missedOnly: Int, offset: Int, limit: Int): List<CallLogEntity>

    /**
     * A value that changes when the table does.
     *
     * Room re-runs a Flow query whenever the table it reads is written, so this emits on
     * every insert, delete and clear — including the ones that leave the count alone.
     * That is the signal a paged reader invalidates on; the number itself is not used.
     */
    @Query("SELECT COUNT(*) FROM call_log")
    fun observeCount(): Flow<Int>

    /** One entry as a stream. Emits null once it is deleted. */
    @Query("SELECT * FROM call_log WHERE id = :id")
    fun observeById(id: Long): Flow<CallLogEntity?>

    /** Returns the row id the store assigned, which is the entry's identity from now on. */
    @Insert
    suspend fun insert(entity: CallLogEntity): Long

    /** Deleting a row that is already gone affects nothing, which is not an error. */
    @Query("DELETE FROM call_log WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM call_log")
    suspend fun deleteAll()
}
