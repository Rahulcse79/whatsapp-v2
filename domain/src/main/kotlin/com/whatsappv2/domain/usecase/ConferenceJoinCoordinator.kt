package com.whatsappv2.domain.usecase

import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.engine.SipCallController
import com.whatsappv2.domain.engine.SipConferenceController
import com.whatsappv2.domain.model.CallId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mixes a call into the conference as soon as it is established (ADR-009).
 *
 * ## How people join a conference on this device
 *
 * There is no room to dial into: the host's phone is the mixer, so joining means the host
 * has a leg to you. Three things create that leg, and each used to end with the host
 * pressing Merge by hand once the leg connected —
 *
 * 1. the host dialling somebody from the conference screen ("Add"),
 * 2. somebody calling the host while the conference is running, and the host choosing
 *    "Add to conference" over "Hold and answer",
 * 3. the host calling a past conference back from history, which places every leg.
 *
 * Each of those now says [joinOnConnect] and this does the rest, on the engine's own call
 * list rather than on a callback the screen might have left: the screen that asked may be
 * gone by the time the far end answers, and the join must not go with it. That is why
 * this lives at application scope beside the call-log recorder, and why it is not a
 * method on a ViewModel.
 *
 * ## Video goes to the bridge, by the same mechanism
 *
 * A video conference is not mixed here (ADR-003; the measurement is on
 * [SipConferenceController.mergeIntoConference]). Calling one back from history dials
 * every member with video and, as each answers, [bridgeOnConnect] has it REFERred into the
 * room: the first two together, when the room is dialled, and every later one alone,
 * because by then this device holds a leg into the room and the engine sends the newcomer
 * to join it. The dialling has to wait for each of those merges — see [awaitQuietForBridge]
 * — where the audio path only waits for the previous member to answer.
 *
 * ## What it will not do
 *
 * Nothing without being asked. A call placed for a transfer, a second call taken with
 * "Hold and answer", a call the user simply made while somebody else was on hold — none
 * of those is a request to join, and inferring one from timing would be exactly the
 * accident this exists to prevent. [ConferenceJoinPolicy] decides the rest.
 */
@Singleton
class ConferenceJoinCoordinator @Inject constructor(
    private val calls: SipCallController,
    private val conferences: SipConferenceController,
    /** How legs reach the bridge; it knows the room, and this does not need to. */
    private val merge: MergeCallsUseCase,
) {

    private val wanted = MutableStateFlow<Set<CallId>>(emptySet())

    /** Calls to be REFERred into the room as they are established, not mixed here. */
    private val wantedInBridge = MutableStateFlow<Set<CallId>>(emptySet())

    /** True while a merge into the bridge is in flight, so nothing dials over it. */
    private val bridging = MutableStateFlow(false)

    /** Group call-backs still to be dialled: the legs after the first, and the leg to wait on. */
    private val callBacks = Channel<CallBack>(Channel.BUFFERED)

    private data class CallBack(
        val after: CallId,
        val remaining: List<suspend () -> CallId?>,
        val video: Boolean,
    )

    /** Asks for [callId] to be mixed into the conference the moment it is established. */
    fun joinOnConnect(callId: CallId) = wanted.update { it + callId }

    /** Asks for [callId] to be moved into the bridge the moment it is established (ADR-003). */
    fun bridgeOnConnect(callId: CallId) = wantedInBridge.update { it + callId }

    /**
     * Calls a conference back: every member dialled, each asked to join as they answer.
     *
     * One at a time, and that is the platform's rule rather than a choice. Telecom
     * permits a self-managed connection service one outgoing call in progress, and
     * refuses the second INVITE while the first is still ringing — measured on a TC15,
     * 2026-09-19: the second leg of a call-back was "Telecom refused an outgoing call".
     * So the first member is dialled here, and each further member is dialled once the
     * one before it has been answered, or has given up. That happens on [run]'s scope,
     * because the screen that pressed "Call again" is replaced by the call screen before
     * the second member has been reached.
     *
     * @param members how to dial each member, in order; one that cannot be dialled
     *   returns null and is skipped. For a video call-back each dials with video.
     * @param video true to assemble the conference in the bridge rather than mix it here.
     * @return the first leg that went out, for the screen to show, or null if none did.
     */
    suspend fun callBack(members: List<suspend () -> CallId?>, video: Boolean = false): CallId? {
        var index = 0
        var first: CallId? = null
        while (first == null && index < members.size) {
            first = members[index]()?.also { if (video) bridgeOnConnect(it) else joinOnConnect(it) }
            index++
        }
        val rest = members.drop(index)
        if (first != null && rest.isNotEmpty()) callBacks.send(CallBack(after = first, remaining = rest, video = video))
        return first
    }

    /**
     * True while this device is mixing a conference that is live — at least two members,
     * at least one of them connected rather than held. The dialler reads it to know that
     * a call placed now is a participant being added, and to say so.
     */
    val hostingLiveConference: Flow<Boolean> =
        combine(calls.activeCalls, conferences.mixedCalls) { active, mixed ->
            mixed.size >= SipConferenceController.MINIMUM_MIXED &&
                active.any { it.callId in mixed && it.state is CallState.Connected }
        }.distinctUntilChanged()

    /** Runs until cancelled: the join loops, and the call-backs still being dialled. */
    suspend fun run() = coroutineScope {
        launch { joinLoop() }
        launch { bridgeLoop() }
        for (callBack in callBacks) {
            var previous = callBack.after
            for (place in callBack.remaining) {
                awaitSettled(previous)
                if (callBack.video) awaitQuietForBridge()
                val id = place() ?: continue
                if (callBack.video) bridgeOnConnect(id) else joinOnConnect(id)
                previous = id
            }
        }
    }

    /** Suspends until [callId] is no longer an outgoing call in progress: answered, or gone. */
    private suspend fun awaitSettled(callId: CallId) {
        calls.activeCalls.first { active -> active.none { it.callId == callId && it.state is CallState.Outgoing } }
    }

    /**
     * Suspends until the next member of a video call-back can be dialled.
     *
     * Three things have to be over, and Telecom's one-outgoing-call rule is why. A merge
     * that is still to happen — the plan [bridgeLoop] is about to run — would place its
     * own INVITE to the room; one in flight is doing so; and one just finished has left
     * this device's leg to the room still ringing. Dialling the next member over any of
     * them is the INVITE Telecom refuses, and a member silently skipped. So the wait is
     * for no plan, no merge and no outgoing call at all, which is also what makes the
     * order deterministic: the same emission that lets the merge start keeps this waiting.
     */
    private suspend fun awaitQuietForBridge() {
        combine(calls.activeCalls, wantedInBridge, bridging, conferences.conferences) { active, asked, busy, rooms ->
            !busy &&
                active.none { it.state is CallState.Outgoing } &&
                ConferenceJoinPolicy.planBridge(active, asked, rooms.mapTo(HashSet()) { it.callId }) == null
        }.first { it }
    }

    /** One collector, so joins are planned in order and never twice. */
    private suspend fun joinLoop() {
        var seen = emptySet<CallId>()
        combine(calls.activeCalls, wanted, conferences.mixedCalls) { active, asked, mixed ->
            Triple(active, asked, mixed)
        }.collect { (active, asked, mixed) ->
            val alive = active.mapTo(HashSet()) { it.callId }
            // A request for a call that has ended is forgotten — but only once the call
            // has been seen at all. A request can arrive a frame before the engine
            // publishes the call it names, and forgetting it then would drop the join.
            val ended = asked.filter { it !in alive && it in seen }
            seen = alive
            if (ended.isNotEmpty()) wanted.update { it - ended.toSet() }

            val plan = ConferenceJoinPolicy.plan(active, asked - ended.toSet(), mixed) ?: return@collect
            wanted.update { it - plan }
            conferences.mixCalls(plan)
        }
    }

    /**
     * The bridge's counterpart of [joinLoop]: wanted legs are REFERred as they establish.
     *
     * [bridging] is raised before the wanted set is trimmed and lowered after the merge
     * returns, so [awaitQuietForBridge] sees no gap in which the plan has vanished and no
     * merge is visible. A merge the engine refuses is logged by the engine and dropped
     * here: the legs stay as the calls they are, and the user can press Merge.
     */
    private suspend fun bridgeLoop() {
        var seen = emptySet<CallId>()
        combine(calls.activeCalls, wantedInBridge, conferences.conferences) { active, asked, rooms ->
            Triple(active, asked, rooms.mapTo(HashSet()) { it.callId })
        }.collect { (active, asked, rooms) ->
            val alive = active.mapTo(HashSet()) { it.callId }
            val ended = asked.filter { it !in alive && it in seen }
            seen = alive
            if (ended.isNotEmpty()) wantedInBridge.update { it - ended.toSet() }

            val plan = ConferenceJoinPolicy.planBridge(active, asked - ended.toSet(), rooms) ?: return@collect
            bridging.value = true
            try {
                wantedInBridge.update { it - plan }
                // A refusal is the engine's to log; the legs remain the calls they are.
                merge.bridge(plan)
            } finally {
                bridging.value = false
            }
        }
    }
}
