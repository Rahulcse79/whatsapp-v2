package com.whatsappv2.data.calllog

import com.whatsappv2.data.calllog.db.CallLogDao
import com.whatsappv2.data.calllog.mapper.toDomain
import com.whatsappv2.data.calllog.mapper.toEntity
import com.whatsappv2.domain.model.CallLogEntry
import com.whatsappv2.domain.model.CallLogId
import com.whatsappv2.domain.repository.CallLogFilter
import com.whatsappv2.domain.repository.CallLogQuery
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

    override fun observe(filter: CallLogFilter): Flow<List<CallLogEntry>> =
        when (filter) {
            CallLogFilter.ALL -> dao.observeAll()
            CallLogFilter.MISSED -> dao.observeMissed()
        }.map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun page(filter: CallLogFilter, offset: Int, limit: Int): List<CallLogEntry> =
        dao.page(missedOnly = if (filter == CallLogFilter.MISSED) 1 else 0, offset = offset, limit = limit)
            .mapNotNull { it.toDomain() }

    /**
     * A search, translated into the DAO's flag-and-value pairs.
     *
     * The text is wrapped in `%` here rather than in the query, so the SQL stays a plain
     * `LIKE :text` and the one place that decides "contains" rather than "starts with" is
     * this line. `LIKE` is already case-insensitive for ASCII in SQLite.
     */
    override suspend fun search(query: CallLogQuery, offset: Int, limit: Int): List<CallLogEntry> {
        val text = query.text.trim()
        return dao.search(
            hasText = if (text.isEmpty()) 0 else 1,
            text = "%$text%",
            direction = query.direction.name,
            hasFrom = if (query.fromEpochMillis == null) 0 else 1,
            from = query.fromEpochMillis ?: 0L,
            hasTo = if (query.toEpochMillis == null) 0 else 1,
            to = query.toEpochMillis ?: Long.MAX_VALUE,
            offset = offset,
            limit = limit,
        ).mapNotNull { it.toDomain() }
    }

    /**
     * The table's own change signal, with the count discarded.
     *
     * `map { }` rather than `distinctUntilChanged`: two writes that leave the count equal
     * are still two changes a paged reader has to reload for.
     */
    override fun changes(): Flow<Unit> = dao.observeCount().map { }

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

    override suspend fun deleteStartedBefore(cutoffEpochMillis: Long): Int =
        dao.deleteStartedBefore(cutoffEpochMillis)
}
