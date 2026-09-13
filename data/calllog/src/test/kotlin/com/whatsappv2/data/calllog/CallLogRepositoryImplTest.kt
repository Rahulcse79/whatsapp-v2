package com.whatsappv2.data.calllog

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.data.calllog.db.CallLogDatabase
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallLogEntry
import com.whatsappv2.domain.model.CallLogId
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.repository.CallLogFilter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The call log against a real SQLite (Task 47).
 *
 * In-memory Room rather than a fake: the ordering and the missed-call filter are the
 * store's, expressed as SQL, and a fake that reimplemented them in Kotlin would be
 * testing the reimplementation. The round trip through the mapper is exercised by every
 * assertion here for the same reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [CALL_LOG_ROBOLECTRIC_SDK])
class CallLogRepositoryImplTest {

    private lateinit var database: CallLogDatabase
    private lateinit var repository: CallLogRepositoryImpl

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CallLogDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = CallLogRepositoryImpl(database.callLogDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `a recorded call comes back with the identity the store gave it`() = runTest {
        val saved = repository.record(entry())

        assertTrue(saved.id.value > 0, "the store must assign a row id")
        assertEquals(entry().remote, saved.remote)
    }

    @Test
    fun `every field survives the round trip`() = runTest {
        val saved = repository.record(
            entry(
                displayName = "Bob",
                contactName = "Bob at work",
                answeredAt = ANSWERED_AT,
                media = MediaProfile.AUDIO_VIDEO,
                reason = HangupReason.REMOTE_HANGUP,
            ),
        )

        val read = repository.observeEntry(saved.id).first()
        assertEquals(saved, read)
    }

    @Test
    fun `the newest call is first, because that is the one being looked for`() = runTest {
        repository.record(entry(startedAt = STARTED_AT))
        repository.record(entry(startedAt = STARTED_AT + ONE_MINUTE))

        val entries = repository.observe().first()
        assertEquals(STARTED_AT + ONE_MINUTE, entries.first().startedAtEpochMillis)
    }

    @Test
    fun `missed lists inbound calls that were never answered, and nothing else`() = runTest {
        val missed = repository.record(entry(direction = CallDirection.INCOMING, answeredAt = null))
        repository.record(entry(direction = CallDirection.INCOMING, answeredAt = ANSWERED_AT))
        // An outbound call nobody picked up is not missed - nobody misses their own call.
        repository.record(entry(direction = CallDirection.OUTGOING, answeredAt = null))

        assertEquals(listOf(missed.id), repository.observe(CallLogFilter.MISSED).first().map { it.id })
    }

    @Test
    fun `deleting one leaves the rest, and deleting it again is not an error`() = runTest {
        val first = repository.record(entry())
        val second = repository.record(entry())

        repository.delete(first.id)
        repository.delete(first.id)

        assertEquals(listOf(second.id), repository.observe().first().map { it.id })
    }

    @Test
    fun `clearing removes everything`() = runTest {
        repository.record(entry())
        repository.record(entry())

        repository.clear()

        assertTrue(repository.observe().first().isEmpty())
    }

    @Test
    fun `pruning removes what started before the cutoff and nothing else`() = runTest {
        // Against SQLite, because the comparison is in the SQL: strictly before, so a
        // call that started exactly at the cutoff is inside the retention and stays.
        repository.record(entry(startedAt = STARTED_AT - ONE_MINUTE))
        val atCutoff = repository.record(entry(startedAt = STARTED_AT))
        val after = repository.record(entry(startedAt = STARTED_AT + ONE_MINUTE))

        val removed = repository.deleteStartedBefore(STARTED_AT)

        assertEquals(1, removed)
        assertEquals(setOf(atCutoff.id, after.id), repository.observe().first().map { it.id }.toSet())
    }

    @Test
    fun `pruning cannot tell audio from video`() = runTest {
        // One retention for both kinds of call is a property of the statement, and the
        // statement is what this exercises: `has_video` is a column here and must not
        // be a condition.
        repository.record(entry(startedAt = STARTED_AT - ONE_MINUTE, media = MediaProfile.AUDIO))
        repository.record(entry(startedAt = STARTED_AT - ONE_MINUTE, media = MediaProfile.AUDIO_VIDEO))
        val keptAudio = repository.record(entry(startedAt = STARTED_AT, media = MediaProfile.AUDIO))
        val keptVideo = repository.record(entry(startedAt = STARTED_AT, media = MediaProfile.AUDIO_VIDEO))

        assertEquals(2, repository.deleteStartedBefore(STARTED_AT))
        assertEquals(setOf(keptAudio.id, keptVideo.id), repository.observe().first().map { it.id }.toSet())
    }

    @Test
    fun `a prune that matches nothing is not a change`() = runTest {
        // The pruner re-runs on every change, including the one its own prune causes.
        // That loop ends only because an empty delete does not count as one.
        repository.record(entry(startedAt = STARTED_AT))

        repository.changes().test {
            awaitItem()
            assertEquals(0, repository.deleteStartedBefore(STARTED_AT - ONE_MINUTE))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an entry that is deleted stops being observed`() = runTest {
        val saved = repository.record(entry())

        repository.observeEntry(saved.id).test {
            assertEquals(saved, awaitItem())
            repository.delete(saved.id)
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the list updates when a call ends rather than when the screen reopens`() = runTest {
        repository.observe().test {
            assertTrue(awaitItem().isEmpty())
            repository.record(entry())
            assertEquals(1, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun entry(
        direction: CallDirection = CallDirection.OUTGOING,
        startedAt: Long = STARTED_AT,
        answeredAt: Long? = null,
        displayName: String? = null,
        contactName: String? = null,
        media: MediaProfile = MediaProfile.AUDIO,
        reason: HangupReason = HangupReason.LOCAL_HANGUP,
    ) = CallLogEntry(
        id = CallLogId.UNSAVED,
        accountId = AccountId("acct-1"),
        remote = REMOTE,
        remoteDisplayName = displayName,
        contactName = contactName,
        direction = direction,
        startedAtEpochMillis = startedAt,
        answeredAtEpochMillis = answeredAt,
        endedAtEpochMillis = startedAt + ONE_MINUTE,
        reason = reason,
        media = media,
    )

    private companion object {
        val REMOTE: SipUri = SipUri.parse("sip:bob@sip.example.com").getOrNull()!!
        const val STARTED_AT = 1_700_000_000_000L
        const val ONE_MINUTE = 60_000L
        const val ANSWERED_AT = STARTED_AT + 10_000L
    }
}
