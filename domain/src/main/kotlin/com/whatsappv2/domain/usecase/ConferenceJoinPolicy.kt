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
 * When there is no conference yet, "everything already mixed" is the call the user is
 * adding somebody **to** — Add is reached from a call, and that call is what becomes the
 * conference. Leaving it out is the whole of why a four-party call could not be built by
 * adding participants one at a time; see [plan].
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

        // What the new member is joining. Once a conference is running that is the
        // conference; before there is one it is the conversation already in progress,
        // and leaving it out is what made a four-party call impossible to build.
        //
        // "Add participant" and "Add to conference" are only reachable **from** a call,
        // and that call is the thing being turned into a conference — the user is not
        // asking for a conference of the people they have not met yet. Counting only the
        // calls somebody named meant the first Add produced a membership of one, which is
        // not a conference and so did nothing; the second Add reached two and mixed those
        // two alone, leaving the original party outside a conference built out of their
        // own call — audible to nobody, drawn by nobody, and with no button anywhere on
        // the conference screen to let them back in (measured on 1000/1001/1003/1005,
        // 2026-09-24 18:15: the focus announced a roster of 1000, 1003 and 1005 while
        // 1001 sat in a one-to-one call it could no longer see).
        //
        // So the anchor is every other call that could be mixed right now — which is the
        // same set [MergeTopology] takes when the user presses Merge by hand. The two
        // ways of asking for a conference now agree on who is in it, and a call nobody
        // asked to have joined is still left alone, because none of this runs until
        // somebody asks for a join at all.
        val anchor = mixed.ifEmpty {
            calls.filterTo(mutableSetOf()) { it.callId !in ready && it.state.canJoin }
                .mapTo(mutableSetOf()) { it.callId }
        }

        val room = (SipConferenceController.MAX_LOCAL_CONFERENCE - anchor.size).coerceAtLeast(0)
        val members = anchor + ready.take(room)
        return members.takeIf { it.size >= SipConferenceController.MINIMUM_MIXED && it != mixed }
    }

    /** Established with media that can be started: connected, or held by us and resumable. */
    private val CallState.canJoin: Boolean
        get() = this is CallState.Connected || (this is CallState.Held && by != HoldParty.REMOTE)
}
