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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
 * ## Video joins the same way audio does
 *
 * It did not use to. A video conference was assembled in a FreeSWITCH room (ADR-003), so
 * calling one back from history dialled every member with video and REFERred each into
 * the room as it answered — a second join loop, with its own planner, that ended in a
 * blind transfer to extension `3000`.
 *
 * `pjmedia`'s video bridge composes the picture here, so that whole path is gone: a
 * member joining a video conference is mixed exactly as a member joining an audio one,
 * and `mixCalls` decides who is in the picture. One loop, one planner, one way in.
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
) {

    private val wanted = MutableStateFlow<Set<CallId>>(emptySet())

    /**
     * Every leg somebody has asked to have joined and that has not joined yet.
     *
     * Published so the call screen can list the members of a conference being called
     * back while they are still ringing — the roster is otherwise built from the mix,
     * which nobody is in until two have answered.
     */
    val pendingMembers: Flow<Set<CallId>> = wanted

    /** Asks for [callId] to be mixed into the conference the moment it is established. */
    fun joinOnConnect(callId: CallId) = wanted.update { it + callId }

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
     * Audio and video call-backs are the same operation here; what differs is the media
     * each member is dialled with, which the caller decides.
     *
     * @param members how to dial each member, in order, given how it is being placed;
     *   one that cannot be dialled returns null and is skipped.
     * @return the first leg that went out, for the screen to show, or null if none did.
     */
    suspend fun callBack(members: List<suspend (CallPlacement) -> CallId?>): CallId? {
        var index = 0
        var first: CallId? = null
        while (first == null && index < members.size) {
            first = members[index](CallPlacement.STANDALONE)?.also(::joinOnConnect)
            index++
        }
        if (first == null) return null

        // Asked for *before* the INVITE goes out, so a member that answers between the
        // placement returning and the request being recorded still joins.
        coroutineScope {
            members.drop(index).map { place ->
                async { place(CallPlacement.CONFERENCE_MEMBER)?.also(::joinOnConnect) }
            }.awaitAll()
        }
        return first
    }

    /**
     * True while a call placed now would be a **participant** rather than a new call.
     *
     * Any established call is enough — it does not have to be a conference yet. That is
     * the difference between this and [hostingLiveConference], and it is the difference
     * that makes Add → Merge work at all:
     *
     * A second call placed as [CallPlacement.STANDALONE] is registered with Telecom,
     * Telecom makes it the active call, and Telecom holds the first one. The app then
     * sends a hold re-INVITE on a call nobody asked to hold. The reference FreeSWITCH
     * tolerates that; the `iriscloud` platform at 192.168.20.56 tears the held dialog
     * down, so the resume every merge starts with returns `481` and the leg is gone
     * before it can be mixed (2026-09-24).
     *
     * So the dialler reads this and places the call as a [CallPlacement.CONFERENCE_MEMBER]
     * instead, which leaves the platform's side where it is and sends no hold at all.
     *
     * Established rather than connected, deliberately: a leg Telecom parked a moment ago
     * is still a call this device is on, and treating it as absent is how the hold comes
     * back by another route.
     */
    val addingToCall: Flow<Boolean> =
        calls.activeCalls.map { active -> active.any { it.state.isEstablished } }.distinctUntilChanged()

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

    /** Runs until cancelled: the join loop. */
    suspend fun run() = joinLoop()

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
}
