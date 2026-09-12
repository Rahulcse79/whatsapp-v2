@file:OptIn(ExperimentalCoroutinesApi::class)

package com.whatsappv2.data.sip.recording

import com.whatsappv2.core.common.dispatcher.DispatcherProvider
import com.whatsappv2.core.common.logging.NoOpLogger
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.result.success
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.core.common.time.MutableClock
import com.whatsappv2.data.sip.registration.FakeSipCoreGateway
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.recording.Recording
import com.whatsappv2.domain.recording.RecordingConsent
import com.whatsappv2.domain.recording.RecordingError
import com.whatsappv2.domain.recording.RecordingId
import com.whatsappv2.domain.recording.RecordingRefusal
import com.whatsappv2.domain.testing.FakeSipEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Task 58's consent gate and lifecycle, with no device in the room.
 *
 * The store is faked, so what is under test is the part that decides: whether a recording
 * may start at all, whether the indicator can disagree with what is being written, and
 * whether a recording can outlive its call. The encryption itself lives in
 * `EncryptedRecordingStore`, which needs the Keystore and is verified on a device.
 */
class PjsipCallRecorderTest {

    private val account = SipAccount(
        id = AccountId("acct-1"),
        label = "Work",
        username = "alice",
        extension = null,
        authUsername = null,
        password = Secret("hunter22"),
        displayName = null,
        domain = "sip.example.com",
        registrar = null,
        outboundProxy = null,
        port = null,
        transport = Transport.UDP,
        registrationExpirySeconds = 3_600,
        stunServer = null,
        turn = null,
        natPolicy = NatPolicy.DEFAULT,
        srtpPolicy = SrtpPolicy.OPTIONAL,
        codecs = CodecPreferences.DEFAULT,
        isDefault = true,
    )

    private val bob = requireNotNull(SipUri.parse("sip:bob@example.com").getOrNull())

    private val gateway = FakeSipCoreGateway()
    private val engine = FakeSipEngine().givenRegistered(account)
    private val store = FakeRecordingStore()
    private val clock = MutableClock().set(NOW)

    private fun recorder(scope: TestScope, dispatchers: DispatcherProvider = InlineDispatchers) =
        PjsipCallRecorder(gateway, engine, store, clock, NoOpLogger, scope.backgroundScope, dispatchers)

    private suspend fun connectedCall(): CallId =
        engine.simulateIncomingCall(account.id, bob).callId
            .also { engine.answer(it, MediaProfile.AUDIO) }

    private fun consent(callId: CallId) = RecordingConsent.GrantedByLocalUser(callId, NOW)

    @Test
    fun `recording will not start without consent, and the stack is never asked`() = runTest {
        val recorder = recorder(this)
        val callId = connectedCall()

        val result = recorder.start(callId, RecordingConsent.None)

        val refusal = assertIs<RecordingError.Refused>(assertIs<Outcome.Failure<RecordingError>>(result).error)
        assertIs<RecordingRefusal.NoConsent>(refusal.refusal)
        assertTrue(gateway.startedRecordings.isEmpty())
    }

    @Test
    fun `consent for another call does not carry to this one`() = runTest {
        val recorder = recorder(this)
        val callId = connectedCall()

        val result = recorder.start(callId, consent(CallId("someone-else")))

        val refusal = assertIs<RecordingError.Refused>(assertIs<Outcome.Failure<RecordingError>>(result).error)
        assertIs<RecordingRefusal.ConsentForAnotherCall>(refusal.refusal)
    }

    @Test
    fun `with consent it starts, and the indicator says so`() = runTest {
        val recorder = recorder(this)
        val callId = connectedCall()

        assertIs<Outcome.Success<RecordingId>>(recorder.start(callId, consent(callId)))

        // Task 58's second done-when is a function of this set, not of an event.
        assertEquals(setOf(callId), recorder.active.value)
        assertEquals(1, gateway.startedRecordings.size)
    }

    @Test
    fun `the path comes from the store and never from a caller`() = runTest {
        val recorder = recorder(this)
        val callId = connectedCall()

        recorder.start(callId, consent(callId))

        assertEquals(store.allocated.single().plaintextPath, gateway.startedRecordings.single().second)
    }

    @Test
    fun `starting twice on one call is the same recording, not two`() = runTest {
        val recorder = recorder(this)
        val callId = connectedCall()

        val first = recorder.start(callId, consent(callId)).getOrNull()
        val second = recorder.start(callId, consent(callId)).getOrNull()

        assertEquals(first, second)
        assertEquals(1, gateway.startedRecordings.size)
    }

    @Test
    fun `stopping seals the file and clears the indicator`() = runTest {
        val recorder = recorder(this)
        val callId = connectedCall()
        recorder.start(callId, consent(callId))
        clock.advanceBy(30_000)

        val recording = recorder.stop(callId).getOrNull()

        assertEquals(listOf(callId.value), gateway.stoppedRecordings)
        assertTrue(recorder.active.value.isEmpty())
        assertEquals(30_000L, recording?.durationMillis)
    }

    @Test
    fun `stopping a call that was not recording succeeds quietly`() = runTest {
        val recorder = recorder(this)
        val callId = connectedCall()

        val result = recorder.stop(callId)

        assertIs<Outcome.Success<Recording?>>(result)
        assertTrue(gateway.stoppedRecordings.isEmpty())
    }

    @Test
    fun `a call that ends takes its recording with it`() = runTest {
        val recorder = recorder(this)
        recorder.start()
        val callId = connectedCall()
        recorder.start(callId, consent(callId))
        runCurrent()

        engine.simulateRemoteHangup(callId)
        runCurrent()

        // The case that leaves a plaintext file behind if nothing is watching the call
        // list — a hangup handler covers a hangup, and nothing else.
        assertTrue(recorder.active.value.isEmpty())
        assertEquals(listOf(callId.value), gateway.stoppedRecordings)
    }

    @Test
    fun `a recording cannot start on a call that is still ringing`() = runTest {
        val recorder = recorder(this)
        val incoming = engine.simulateIncomingCall(account.id, bob)

        val result = recorder.start(incoming.callId, consent(incoming.callId))

        val refusal = assertIs<RecordingError.Refused>(assertIs<Outcome.Failure<RecordingError>>(result).error)
        assertIs<RecordingRefusal.CallNotEstablished>(refusal.refusal)
    }

    @Test
    fun `a recording cannot start on a call the engine does not have`() = runTest {
        val recorder = recorder(this)

        val result = recorder.start(CallId("gone"), consent(CallId("gone")))

        assertIs<RecordingError.EngineRefused>(assertIs<Outcome.Failure<RecordingError>>(result).error)
    }

    @Test
    fun `storage that will not take a recording refuses it rather than recording nowhere`() = runTest {
        val recorder = recorder(this)
        val callId = connectedCall()
        store.failAllocation = true

        val result = recorder.start(callId, consent(callId))

        assertIs<RecordingError.StorageUnavailable>(assertIs<Outcome.Failure<RecordingError>>(result).error)
        assertTrue(gateway.startedRecordings.isEmpty())
        assertFalse(callId in recorder.active.value)
    }

    @Test
    fun `a stack that refuses is not shown as recording`() = runTest {
        val recorder = recorder(this)
        val callId = connectedCall()
        gateway.recordingRefusal = "the call has no audio stream"

        val result = recorder.start(callId, consent(callId))

        // The defect this covers: start used to return before the stack had answered, so
        // the indicator said "Recording this call" over a file nothing was writing.
        val refused = assertIs<RecordingError.EngineRefused>(
            assertIs<Outcome.Failure<RecordingError>>(result).error,
        )
        assertEquals("the call has no audio stream", refused.detail)
        assertTrue(recorder.active.value.isEmpty())
    }

    @Test
    fun `a refused start gives its slot back instead of leaving a stub`() = runTest {
        val recorder = recorder(this)
        val callId = connectedCall()
        gateway.recordingRefusal = "the stack is not running"

        recorder.start(callId, consent(callId))

        // Sealed, the stub would become a recording of nothing that still has to be
        // listed, shown and deleted like a real one.
        assertEquals(store.allocated.single(), store.discarded.single())
        assertTrue(store.sealed.isEmpty())
    }

    @Test
    fun `a refused start can be retried once the stack is willing`() = runTest {
        val recorder = recorder(this)
        val callId = connectedCall()
        gateway.recordingRefusal = "the call has no audio stream"
        recorder.start(callId, consent(callId))

        gateway.recordingRefusal = null
        val result = recorder.start(callId, consent(callId))

        // The first attempt must leave nothing behind that makes the second a no-op.
        assertIs<Outcome.Success<RecordingId>>(result)
        assertEquals(setOf(callId), recorder.active.value)
        assertEquals(1, gateway.startedRecordings.size)
    }

    @Test
    fun `stopping the recorder seals everything still running`() = runTest {
        val recorder = recorder(this)
        recorder.start()
        val callId = connectedCall()
        recorder.start(callId, consent(callId))

        recorder.stop()

        assertTrue(recorder.active.value.isEmpty())
        assertEquals(listOf(callId.value), gateway.stoppedRecordings)
    }

    @Test
    fun `the store never runs on the thread that asked - the ANR this fixes`() = runTest {
        val caller = Thread.currentThread().name
        val recorder = recorder(this, OnIo)
        val callId = connectedCall()

        recorder.start(callId, consent(callId))
        recorder.stop(callId)

        // seal() encrypts the whole recording with a Keystore key — a Keymaster round trip
        // per block. Its callers are on viewModelScope, which is the main thread, so
        // running it there blocked the call screen for ~5 s on a 5 MB recording and ANR'd
        // it (docs/HANDOFF.md §0d). `suspend` is what permits the hop off the caller;
        // this asserts the hop is actually taken rather than merely allowed.
        assertNotEquals(caller, store.sealedOnThread)
        assertNotEquals(caller, store.allocatedOnThread)
    }

    @Test
    fun `starting the recorder sweeps abandoned plaintext, and not on the caller's thread`() = runTest {
        // The sweep used to run from the store's constructor, on whichever thread first
        // injected the store -- the main one -- and `listFiles` plus a delete per file is
        // I/O the call screen paid for. It belongs to the stack's start, on the I/O
        // dispatcher, and this asserts both the moment and the thread.
        val caller = Thread.currentThread().name
        val recorder = recorder(this, OnIo)
        assertEquals(0, store.sweeps, "nothing is swept until the stack starts")

        recorder.start()

        assertNotEquals(caller, store.swept.await())
        assertEquals(1, store.sweeps)
    }

    @Test
    fun `the retention hook is passed straight through to the store`() = runTest {
        val recorder = recorder(this)

        recorder.purgeOlderThan(NOW)

        // A hook, not a schedule: nothing here decides what the retention period is,
        // because that belongs to whoever deploys the app (Task 58, docs/security.md).
        assertEquals(listOf(NOW), store.purgedBefore)
    }

    @Test
    fun `listing and deleting go to the store, which is the only thing that knows where`() =
        runTest {
            val recorder = recorder(this)
            val callId = connectedCall()
            recorder.start(callId, consent(callId))
            val sealed = requireNotNull(recorder.stop(callId).getOrNull())

            assertEquals(listOf(sealed), recorder.recordings())

            recorder.delete(sealed.id)
            assertTrue(recorder.recordings().isEmpty())
        }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}

/**
 * A store that writes nothing.
 *
 * Enough to exercise the recorder's decisions and no more. What the real store does — AES,
 * the Keystore, deleting the plaintext before reporting success — needs a device, so it is
 * verified there rather than pretended at here.
 */
private class FakeRecordingStore : RecordingStore {

    /** Which thread the store was used from, so the ANR fix has something to assert. */
    var allocatedOnThread: String? = null
    var sealedOnThread: String? = null

    /** Completed with the thread the sweep ran on; a real dispatcher finishes it later. */
    val swept = CompletableDeferred<String>()
    var sweeps = 0

    val allocated = mutableListOf<AllocatedRecording>()
    val sealed = mutableListOf<Recording>()
    val discarded = mutableListOf<AllocatedRecording>()
    val purgedBefore = mutableListOf<Long>()
    var failAllocation = false
    private var next = 0

    override fun sweepAbandoned() {
        sweeps++
        swept.complete(Thread.currentThread().name)
    }

    override fun allocate(callId: CallId): Outcome<AllocatedRecording, RecordingError> {
        allocatedOnThread = Thread.currentThread().name
        if (failAllocation) return failure(RecordingError.StorageUnavailable("no space"))
        val slot = AllocatedRecording(
            id = RecordingId("rec-${++next}"),
            callId = callId,
            plaintextPath = "/tmp/rec-$next.tmp",
        )
        allocated += slot
        return success(slot)
    }

    override fun discard(allocated: AllocatedRecording) {
        discarded += allocated
    }

    override fun seal(
        allocated: AllocatedRecording,
        startedAtEpochMillis: Long,
        endedAtEpochMillis: Long,
    ): Outcome<Recording?, RecordingError> {
        sealedOnThread = Thread.currentThread().name
        val recording = Recording(
            id = allocated.id,
            callId = allocated.callId,
            startedAtEpochMillis = startedAtEpochMillis,
            endedAtEpochMillis = endedAtEpochMillis,
            sizeBytes = 1_024,
        )
        sealed += recording
        return success(recording)
    }

    override fun openForPlayback(id: RecordingId): Outcome<PlaybackCopy, RecordingError> =
        success(PlaybackCopy(id = id, plaintextPath = "/tmp/$id.wav"))

    override fun closePlayback(copy: PlaybackCopy) = Unit

    override fun list(): List<Recording> = sealed.toList()

    override fun delete(id: RecordingId): Outcome<Unit, RecordingError> {
        sealed.removeAll { it.id == id }
        return success(Unit)
    }

    override fun purgeOlderThan(cutoffEpochMillis: Long): Outcome<Int, RecordingError> {
        purgedBefore += cutoffEpochMillis
        return success(0)
    }
}

/**
 * Everything inline, for the tests that are about decisions rather than threads.
 *
 * `Unconfined` rather than the test scheduler because nothing here delays — the fake store
 * answers on the calling thread, and running it inline is both simpler and closer to what
 * the real one does once it is already on its dispatcher.
 */
private object InlineDispatchers : DispatcherProvider {
    override val main: CoroutineDispatcher get() = Dispatchers.Unconfined
    override val io: CoroutineDispatcher get() = Dispatchers.Unconfined
    override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
    override val unconfined: CoroutineDispatcher get() = Dispatchers.Unconfined
}

/**
 * A real IO pool for [io], so a test can prove the hop off the caller happened.
 *
 * `Dispatchers.IO` and not the test scheduler, because the whole assertion is *which
 * thread* — a scheduler that runs the block inline would pass whether the production code
 * hopped or not, which is the bug it is there to catch. `runTest` still waits for the
 * `withContext` to come back, so nothing here is timing-dependent.
 */
private object OnIo : DispatcherProvider {
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val main: CoroutineDispatcher get() = Dispatchers.Unconfined
    override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
    override val unconfined: CoroutineDispatcher get() = Dispatchers.Unconfined
}
