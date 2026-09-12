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
     * One page of a search, newest first.
     *
     * Every criterion is a pair: a flag saying whether it applies and the value it applies.
     * Room cannot bind a nullable into `LIKE` usefully, and `:x IS NULL OR col >= :x`
     * defeats an index on some SQLite builds — the flag form keeps each clause a plain
     * comparison and lets the planner skip the whole term when it is off.
     *
     * `remote_uri` is matched as well as the two name columns because people search for
     * "1001" as readily as for "Rahul", and the extension only lives in the address.
     */
    @Query(
        """
        SELECT * FROM call_log
        WHERE (:hasText = 0
               OR contact_name LIKE :text
               OR remote_display_name LIKE :text
               OR remote_uri LIKE :text)
          AND (:direction = 'ANY'
               OR (:direction = 'MISSED'
                   AND direction = 'INCOMING'
                   AND answered_at_epoch_millis IS NULL)
               OR (:direction <> 'MISSED' AND direction = :direction))
          AND (:hasFrom = 0 OR started_at_epoch_millis >= :from)
          AND (:hasTo = 0 OR started_at_epoch_millis <= :to)
        ORDER BY started_at_epoch_millis DESC, id DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    @Suppress("LongParameterList")
    suspend fun search(
        hasText: Int,
        text: String,
        direction: String,
        hasFrom: Int,
        from: Long,
        hasTo: Int,
        to: Long,
        offset: Int,
        limit: Int,
    ): List<CallLogEntity>

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
