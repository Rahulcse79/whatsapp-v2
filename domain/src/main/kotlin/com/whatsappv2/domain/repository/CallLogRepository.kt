package com.whatsappv2.domain.repository

import com.whatsappv2.domain.model.CallLogEntry
import com.whatsappv2.domain.model.CallLogId
import kotlinx.coroutines.flow.Flow

/**
 * Which calls have happened (Task 47, §5.2).
 *
 * ## Reads are flows, writes are suspending
 *
 * The history screen must update the moment a call ends rather than when the user next
 * opens it, so [observeEntries] is a [Flow] the store keeps current. The writes are
 * one-shot and ordinary.
 *
 * ## Filtering here, not in the screen
 *
 * [observeMissed] exists rather than leaving the screen to filter what [observeEntries]
 * emits, because the list is paged: filtering after the fact would page through answered
 * calls to find the missed ones and show a short page whenever a page held few of them.
 * The store is where the question can be asked of the index.
 */
interface CallLogRepository {

    /** Every call, newest first. */
    fun observeEntries(): Flow<List<CallLogEntry>>

    /** Inbound calls that were never answered, newest first. */
    fun observeMissed(): Flow<List<CallLogEntry>>

    /** One entry, or null once it has been deleted. */
    fun observeEntry(id: CallLogId): Flow<CallLogEntry?>

    /**
     * Records a finished call and returns it with the id the store assigned.
     *
     * Called once per terminal transition. The entry passed in carries
     * [CallLogId.UNSAVED], because only the store can say what a row is called.
     */
    suspend fun record(entry: CallLogEntry): CallLogEntry

    /** Removes one entry. Deleting an entry that is already gone is not an error. */
    suspend fun delete(id: CallLogId)

    /** Removes every entry. */
    suspend fun clear()
}
