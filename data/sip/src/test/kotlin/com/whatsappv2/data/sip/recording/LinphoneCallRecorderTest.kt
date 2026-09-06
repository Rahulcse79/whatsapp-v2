package com.whatsappv2.data.sip.recording

import com.whatsappv2.core.common.logging.NoOpLogger
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.result.success
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.core.common.time.MutableClock
import com.whatsappv2.data.sip.registration.FakeLinphoneCoreGateway
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Task 58's consent gate and lifecycle, with no device in the room.
 *
 * The store is faked, so what is under test is the part that decides: whether a recording
 * may start at all, whether the indicator can disagree with what is being written, and
 * whether a recording can outlive its call. The encryption itself lives in
 * `EncryptedRecordingStore`, which needs the Keystore and is verified on a device.
 */
class LinphoneCallRecorderTest {

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

    private val gateway = FakeLinphoneCoreGateway()
    private val engine = FakeSipEngine().givenRegistered(account)
    private val store = FakeRecordingStore()
    private val clock = MutableClock().set(NOW)

    private fun recorder(scope: TestScope) =
        LinphoneCallRecorder(gateway, engine, store, clock, NoOpLogger, scope.backgroundScope)

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
    fun `the retention hook is passed straight through to the store`() = runTest {
        val recorder = recorder(this)

        recorder.purgeOlderThan(NOW)

        assertEquals(listOf(NOW), store.purgedBefore)
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

    val allocated = mutableListOf<AllocatedRecording>()
    val sealed = mutableListOf<Recording>()
    val purgedBefore = mutableListOf<Long>()
    var failAllocation = false
    private var next = 0

    override fun allocate(callId: CallId): Outcome<AllocatedRecording, RecordingError> {
        if (failAllocation) return failure(RecordingError.StorageUnavailable("no space"))
        val slot = AllocatedRecording(
            id = RecordingId("rec-${++next}"),
            callId = callId,
            plaintextPath = "/tmp/rec-$next.tmp",
        )
        allocated += slot
        return success(slot)
    }

    override fun seal(
        allocated: AllocatedRecording,
        startedAtEpochMillis: Long,
        endedAtEpochMillis: Long,
    ): Outcome<Recording?, RecordingError> {
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
