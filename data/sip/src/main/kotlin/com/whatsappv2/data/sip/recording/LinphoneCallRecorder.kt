package com.whatsappv2.data.sip.recording

import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import com.whatsappv2.core.common.time.Clock
import com.whatsappv2.data.sip.call.LinphoneRecordingGateway
import com.whatsappv2.data.sip.di.SipStackScope
import com.whatsappv2.domain.engine.SipCallController
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.recording.CallRecorder
import com.whatsappv2.domain.recording.Recording
import com.whatsappv2.domain.recording.RecordingConsent
import com.whatsappv2.domain.recording.RecordingError
import com.whatsappv2.domain.recording.RecordingId
import com.whatsappv2.domain.recording.RecordingPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recording, gated and bookkept (Task 58, §2.6, §7).
 *
 * ## What this class is for
 *
 * Not "recording a call" — the stack does that. This is the part §2.6 is actually about:
 * making it impossible to record without consent, impossible to record without the user
 * being able to see it, and impossible for a file to survive as plaintext.
 *
 * Each of those is one line here and each is load-bearing:
 *
 * - [start] asks [RecordingPolicy] and refuses on its answer. The consent is a
 *   *parameter*, so there is no lookup this class could be persuaded to skip.
 * - [active] is a `StateFlow`, so the in-call indicator is a function of what is being
 *   written rather than of an event somebody has to remember to send. A recording that is
 *   running and an indicator that is showing cannot disagree.
 * - [stop] seals through [RecordingStore], which encrypts and destroys the plaintext.
 *
 * ## Recording stops when the call does, and nothing has to ask
 *
 * A call that ends while recording is the case that leaves a file open and a plaintext
 * copy on disk. [watchCalls] closes it: the recorder follows
 * [SipCallController.activeCalls] and seals any recording whose call has left the list or
 * fallen out of the established states, which covers hangup, error, transfer and the
 * stack going down alike — the same reasoning as `CameraPolicy`, applied to a file
 * instead of a device.
 */
@Singleton
internal class LinphoneCallRecorder @Inject constructor(
    private val gateway: LinphoneRecordingGateway,
    private val calls: SipCallController,
    private val store: RecordingStore,
    private val clock: Clock,
    private val logger: Logger,
    @SipStackScope private val scope: CoroutineScope,
) : CallRecorder {

    private val running = MutableStateFlow<Map<CallId, InFlight>>(emptyMap())

    /**
     * The published view of [running].
     *
     * Derived on write rather than with `stateIn`, for the same reason the engine derives
     * its call list that way: a sharing coroutine started here would live as long as this
     * singleton and hand back no way to end it.
     */
    private val recordingCalls = MutableStateFlow<Set<CallId>>(emptySet())
    override val active: StateFlow<Set<CallId>> = recordingCalls.asStateFlow()

    /** One recording in progress: what it is called, where it is going, and since when. */
    private data class InFlight(
        val allocated: AllocatedRecording,
        val startedAtEpochMillis: Long,
    )

    override suspend fun start(
        callId: CallId,
        consent: RecordingConsent,
    ): Outcome<RecordingId, RecordingError> {
        if (callId in running.value) return success(running.value.getValue(callId).allocated.id)

        val call = calls.activeCalls.value.firstOrNull { it.callId == callId }
            ?: return failure(RecordingError.EngineRefused("no such call"))

        // The gate, and the only one. Everything §2.6 asks for is decided by this call.
        RecordingPolicy.mayRecord(
            callId = callId,
            state = call.state,
            consent = consent,
            platformSupported = true,
        )?.let { return failure(RecordingError.Refused(it)) }

        val allocated = when (val slot = store.allocate(callId)) {
            is Outcome.Failure -> return slot
            is Outcome.Success -> slot.value
        }

        gateway.startRecording(callId.value, allocated.plaintextPath)
        setRunning(running.value + (callId to InFlight(allocated, clock.nowEpochMillis())))
        // The call id, never the path: a log line naming a recording's file is a map to it
        // for anything that can read logcat (§7, DoD 12).
        logger.info(TAG, "Recording started on $callId")
        return success(allocated.id)
    }

    override suspend fun stop(callId: CallId): Outcome<Recording?, RecordingError> {
        // Idempotent: stopping a call that was not recording is the outcome the caller
        // wanted, and reporting an error would make the teardown path noisy for nothing.
        val inFlight = running.value[callId] ?: return success(null)

        gateway.stopRecording(callId.value)
        setRunning(running.value - callId)

        return store.seal(
            allocated = inFlight.allocated,
            startedAtEpochMillis = inFlight.startedAtEpochMillis,
            endedAtEpochMillis = clock.nowEpochMillis(),
        )
    }

    override suspend fun recordings(): List<Recording> = store.list()

    override suspend fun delete(id: RecordingId): Outcome<Unit, RecordingError> = store.delete(id)

    override suspend fun purgeOlderThan(cutoffEpochMillis: Long): Outcome<Int, RecordingError> =
        store.purgeOlderThan(cutoffEpochMillis)

    /**
     * Begins watching the call list, so no recording outlives its call.
     *
     * Started with the stack rather than with a screen: the case this exists for is a call
     * ending while nothing is on screen, and a collector owned by the in-call UI would be
     * gone by then — leaving a file open and a plaintext copy on disk.
     */
    fun start() {
        if (watchJob != null) return
        watchJob = scope.launch {
            calls.activeCalls.collect { onCallsChanged() }
        }
    }

    /** Stops watching and seals anything still running. The stack is going down. */
    suspend fun stop() {
        watchJob?.cancel()
        watchJob = null
        running.value.keys.toList().forEach { stop(it) }
    }

    /** Held so [stop] can end it; a collector that outlives the stack keeps it alive. */
    private var watchJob: Job? = null

    /**
     * Seals any recording whose call is no longer in a state that can be recorded.
     *
     * Driven from the call list rather than from a hangup handler, so every way a call can
     * end is covered by one rule instead of by an enumeration somebody has to keep
     * complete.
     */
    private suspend fun onCallsChanged() {
        val live = calls.activeCalls.value.associateBy { it.callId }
        running.value.keys.toList().forEach { callId ->
            val state = live[callId]?.state
            if (state == null || RecordingPolicy.mustStop(state)) {
                logger.info(TAG, "Sealing the recording on $callId; its call is no longer recordable")
                stop(callId)
            }
        }
    }

    /** The only way [running] changes, so [recordingCalls] cannot fall behind it. */
    private fun setRunning(next: Map<CallId, InFlight>) {
        running.value = next
        recordingCalls.value = next.keys
    }

    private companion object {
        const val TAG = "CallRecorder"
    }
}
