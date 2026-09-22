package com.whatsappv2.domain.usecase

import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.engine.CallPlacement
import com.whatsappv2.domain.engine.SipCallController
import com.whatsappv2.domain.engine.SipConferenceController
import com.whatsappv2.domain.model.CallId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * to join it.
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

    /**
     * Every leg somebody has asked to have joined and that has not joined yet, whichever
     * way it is to join.
     *
     * Published so the call screen can list the members of a conference being called
     * back while they are still ringing — the roster is otherwise built from the mix,
     * which nobody is in until two have answered.
     */
    val pendingMembers: Flow<Set<CallId>> =
        combine(wanted, wantedInBridge) { mix, bridge -> mix + bridge }.distinctUntilChanged()

    /** Asks for [callId] to be mixed into the conference the moment it is established. */
    fun joinOnConnect(callId: CallId) = wanted.update { it + callId }

    /** Asks for [callId] to be moved into the bridge the moment it is established (ADR-003). */
    fun bridgeOnConnect(callId: CallId) = wantedInBridge.update { it + callId }

    /**
     * Calls a conference back: every member dialled **at the same time**, each asked to
     * join as they answer.
     *
     * ## Everyone's phone rings at once
     *
     * It used to be one at a time — each member dialled once the one before had answered
     * or given up — because Telecom permits a self-managed app one outgoing call in
     * progress and refused the second INVITE while the first rang (TC15, 2026-09-19).
     * A conference whose third member is reached a minute after its first is not what
     * "call the conference again" means, so the rule moved into the engine: the first
     * leg is registered with the platform as any call is, and every other member is
     * placed beside it as a [CallPlacement.CONFERENCE_MEMBER], which the platform is not
     * asked about while that leg is dialling. See [CallPlacement] for what that costs
     * and why it is safe.
     *
     * The first member is dialled first, on its own, and only then the rest together:
     * it is the leg the platform will refuse if there is a reason to refuse — a cellular
     * call in progress — and a refusal there must stop the whole call-back rather than
     * leave the other members ringing behind the platform's back. A member that cannot
     * be dialled for its own reason (an address that will not resolve) is skipped, and
     * the next becomes the first.
     *
     * @param members how to dial each member, in order, given how it is being placed;
     *   one that cannot be dialled returns null and is skipped. For a video call-back
     *   each dials with video.
     * @param video true to assemble the conference in the bridge rather than mix it here.
     * @return the first leg that went out, for the screen to show, or null if none did.
     */
    suspend fun callBack(members: List<suspend (CallPlacement) -> CallId?>, video: Boolean = false): CallId? {
        val ask = if (video) ::bridgeOnConnect else ::joinOnConnect
        var index = 0
        var first: CallId? = null
        while (first == null && index < members.size) {
            first = members[index](CallPlacement.STANDALONE)?.also(ask)
            index++
        }
        if (first == null) return null

        // Asked for *before* the INVITE goes out, so a member that answers between the
        // placement returning and the request being recorded still joins.
        coroutineScope {
            members.drop(index).map { place ->
                async { place(CallPlacement.CONFERENCE_MEMBER)?.also(ask) }
            }.awaitAll()
        }
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

    /** Runs until cancelled: the two join loops. */
    suspend fun run() = coroutineScope {
        launch { joinLoop() }
        launch { bridgeLoop() }
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
     * The room is joined as a [CallPlacement.CONFERENCE_MEMBER] by the engine, so a
     * merge while the first member is still ringing is not refused by the platform. A
     * merge the engine refuses is logged by the engine and dropped here: the legs stay
     * as the calls they are, and the user can press Merge.
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
            wantedInBridge.update { it - plan }
            merge.bridge(plan)
        }
    }
}
