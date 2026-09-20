package com.whatsappv2.domain.usecase

import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.call.HoldParty
import com.whatsappv2.domain.engine.CallSnapshot
import com.whatsappv2.domain.engine.SipConferenceController
import com.whatsappv2.domain.model.CallId

/**
 * Which calls to mix next, given who is waiting to join and who is already in (ADR-009).
 *
 * ## The rule
 *
 * A call somebody asked to have joined is mixed the moment it is established, together
 * with everything already mixed — that is what "join" means, and it is the one thing a
 * WhatsApp group call does that this app made the user do by hand: add a participant,
 * wait for them to answer, then press Merge. Established rather than connected, because
 * Telecom holds every other call the instant one goes active, and a member the platform
 * parked while the next one was ringing is still a member; `mixCalls` resumes it.
 *
 * ## When it waits
 *
 * - **Nobody to join yet.** One established call and no conference is a call, not a
 *   conference; the plan waits for a second. This is the first leg of a group being
 *   called back from history.
 * - **The conference is held.** Every member held is the user having stepped out of the
 *   room; a member arriving then must not drag them back in. The join happens when the
 *   user resumes.
 * - **The room is full.** ADR-009's ceiling stands; a ninth is left as a call and the
 *   caller is not told here, because this runs in the background and a refusal belongs on
 *   a screen — the same reason `mixCalls` refuses it with a message rather than a crash.
 *
 * Pure, so every one of those is a JVM test rather than a handset with three people.
 */
object ConferenceJoinPolicy {

    /**
     * The membership to ask for, or null when there is nothing to do right now.
     *
     * @param calls every call on the device.
     * @param wanted the calls somebody asked to have joined, whatever their state.
     * @param mixed the membership the engine is mixing now.
     */
    fun plan(calls: List<CallSnapshot>, wanted: Set<CallId>, mixed: Set<CallId>): Set<CallId>? {
        val ready = calls
            .filter { it.callId in wanted && it.callId !in mixed && it.state.canJoin }
            .map { it.callId }
        if (ready.isEmpty()) return null

        if (mixed.isNotEmpty() && calls.none { it.callId in mixed && it.state is CallState.Connected }) return null

        val room = (SipConferenceController.MAX_LOCAL_CONFERENCE - mixed.size).coerceAtLeast(0)
        val members = mixed + ready.take(room)
        return members.takeIf { it.size >= SipConferenceController.MINIMUM_MIXED && it != mixed }
    }

    /** Established with media that can be started: connected, or held by us and resumable. */
    private val CallState.canJoin: Boolean
        get() = this is CallState.Connected || (this is CallState.Held && by != HoldParty.REMOTE)
}
