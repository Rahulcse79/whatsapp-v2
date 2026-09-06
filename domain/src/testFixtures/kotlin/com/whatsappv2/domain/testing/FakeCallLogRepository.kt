package com.whatsappv2.domain.testing

import com.whatsappv2.domain.model.CallLogEntry
import com.whatsappv2.domain.model.CallLogId
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

    override fun observeEntries(): Flow<List<CallLogEntry>> = entries

    override fun observeMissed(): Flow<List<CallLogEntry>> =
        entries.map { list -> list.filter { it.wasMissed } }

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
}
