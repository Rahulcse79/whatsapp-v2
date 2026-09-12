package com.whatsappv2.domain.testing

import com.whatsappv2.domain.model.CallLogEntry
import com.whatsappv2.domain.model.CallLogId
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.repository.CallDirectionFilter
import com.whatsappv2.domain.repository.CallLogFilter
import com.whatsappv2.domain.repository.CallLogQuery
import com.whatsappv2.domain.repository.CallLogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * An in-memory [CallLogRepository], for anything that writes or reads history without a
 * database.
 *
 * Assigns row ids the way the real store does — increasing, starting at one — so a test
 * can assert that an entry came back with an identity, and so two entries recorded in
 * order are distinguishable by more than their timestamps.
 *
 * Newest first on the way out, matching the DAO's ordering: a test that asserted the
 * other order against this fake would pass here and fail against Room, which is the worst
 * kind of fake.
 */
class FakeCallLogRepository : CallLogRepository {

    private val entries = MutableStateFlow<List<CallLogEntry>>(emptyList())
    private var nextId = 0L

    /** Everything recorded, newest first. */
    val recorded: List<CallLogEntry> get() = entries.value

    override fun observe(filter: CallLogFilter): Flow<List<CallLogEntry>> =
        entries.map { list -> list.matching(filter) }

    override suspend fun page(filter: CallLogFilter, offset: Int, limit: Int): List<CallLogEntry> =
        entries.value.matching(filter).drop(offset).take(limit)

    /**
     * The same criteria the store applies, in Kotlin.
     *
     * Written out rather than delegated to the real SQL so the two can be compared: if
     * this and `CallLogRepositoryImplTest` ever disagree about what "missed" means, one of
     * them is wrong and the difference is visible.
     */
    override suspend fun search(query: CallLogQuery, offset: Int, limit: Int): List<CallLogEntry> {
        val text = query.text.trim().lowercase()
        return entries.value
            .filter { entry ->
                val matchesText = text.isEmpty() ||
                    listOfNotNull(entry.contactName, entry.remoteDisplayName, entry.remote.render())
                        .any { it.lowercase().contains(text) }
                val matchesDirection = when (query.direction) {
                    CallDirectionFilter.ANY -> true
                    CallDirectionFilter.MISSED -> entry.wasMissed
                    CallDirectionFilter.INCOMING -> entry.direction == CallDirection.INCOMING
                    CallDirectionFilter.OUTGOING -> entry.direction == CallDirection.OUTGOING
                }
                val after = query.fromEpochMillis?.let { entry.startedAtEpochMillis >= it } ?: true
                val before = query.toEpochMillis?.let { entry.startedAtEpochMillis <= it } ?: true
                matchesText && matchesDirection && after && before
            }
            .sortedByDescending { it.startedAtEpochMillis }
            .drop(offset)
            .take(limit)
    }

    override fun changes(): Flow<Unit> = entries.map { }

    override fun observeEntry(id: CallLogId): Flow<CallLogEntry?> =
        entries.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun record(entry: CallLogEntry): CallLogEntry {
        val saved = entry.copy(id = CallLogId(++nextId))
        entries.value = listOf(saved) + entries.value
        return saved
    }

    override suspend fun delete(id: CallLogId) {
        entries.value = entries.value.filterNot { it.id == id }
    }

    override suspend fun clear() {
        entries.value = emptyList()
    }

    // `wanted`, not `filter`: naming it after the parameter would shadow the stdlib
    // function used in the same expression, which reads as a bug even when it is not.
    private fun List<CallLogEntry>.matching(wanted: CallLogFilter): List<CallLogEntry> =
        when (wanted) {
            CallLogFilter.ALL -> this
            CallLogFilter.MISSED -> filter { it.wasMissed }
        }
}
