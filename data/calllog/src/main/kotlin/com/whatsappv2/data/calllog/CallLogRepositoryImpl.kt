package com.whatsappv2.data.calllog

import com.whatsappv2.data.calllog.db.CallLogDao
import com.whatsappv2.data.calllog.mapper.toDomain
import com.whatsappv2.data.calllog.mapper.toEntity
import com.whatsappv2.domain.model.CallLogEntry
import com.whatsappv2.domain.model.CallLogId
import com.whatsappv2.domain.repository.CallLogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Room-backed call log (Task 47).
 *
 * Thin by design: the ordering is the DAO's, the filtering is the index's, and all this
 * adds is the translation. A row that cannot be read as a domain entry is dropped from
 * the list rather than failing it — see the mapper for why one bad row must not cost the
 * user the whole screen.
 */
@Singleton
class CallLogRepositoryImpl @Inject constructor(
    private val dao: CallLogDao,
) : CallLogRepository {

    override fun observeEntries(): Flow<List<CallLogEntry>> =
        dao.observeAll().map { rows -> rows.mapNotNull { it.toDomain() } }

    override fun observeMissed(): Flow<List<CallLogEntry>> =
        dao.observeMissed().map { rows -> rows.mapNotNull { it.toDomain() } }

    override fun observeEntry(id: CallLogId): Flow<CallLogEntry?> =
        dao.observeById(id.value).map { it?.toDomain() }

    /**
     * Writes the entry and hands back the identity the store gave it.
     *
     * The returned entry is the argument with its id filled in rather than a re-read: the
     * row was just written from this value, so reading it back would only prove the
     * mapper round-trips, and it would do so on the call path of every ended call.
     */
    override suspend fun record(entry: CallLogEntry): CallLogEntry {
        val rowId = dao.insert(entry.toEntity())
        return entry.copy(id = CallLogId(rowId))
    }

    override suspend fun delete(id: CallLogId) = dao.deleteById(id.value)

    override suspend fun clear() = dao.deleteAll()
}
