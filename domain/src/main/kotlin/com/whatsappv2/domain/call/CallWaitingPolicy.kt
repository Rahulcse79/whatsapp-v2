package com.whatsappv2.domain.call

import com.whatsappv2.domain.engine.CallSnapshot
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.HangupReason

/** What the user chose to do about a second call arriving (Task 56, §5.2). */
enum class SecondCallResponse {
    /** Answer it and put the call already in progress on hold. */
    ACCEPT_AND_HOLD,

    /** Answer it and end the call already in progress. */
    ACCEPT_AND_END,

    /** Refuse it. The call in progress is untouched. */
    REJECT,
}

/**
 * One thing to do to one call, in order.
 *
 * A list of these rather than a method that does the work, because the **order** is the
 * requirement. Task 56's second done-when — never two active at once — is a statement
 * about the sequence, and a sequence is only assertable if it is a value.
 */
sealed interface CallStep {
    val callId: CallId

    data class Hold(override val callId: CallId) : CallStep
    data class Resume(override val callId: CallId) : CallStep
    data class Answer(override val callId: CallId) : CallStep
    data class Reject(override val callId: CallId, val reason: HangupReason) : CallStep
    data class Hangup(override val callId: CallId, val reason: HangupReason) : CallStep
}

/**
 * Call waiting and swapping, as a pure function (Task 56, §5.2).
 *
 * ## Why the steps are a value
 *
 * "Never two active" is not something you can check after the fact. Both a hold and an
 * answer are round trips to the far end, and a version that answers first and holds
 * second is briefly — sometimes for a whole second on a slow link — a phone with two live
 * microphones and two audio streams mixed together. The user hears both callers at once,
 * and so does each caller.
 *
 * Ordering the steps is therefore the whole of the design, and returning them lets a JVM
 * test read the order directly instead of inferring it from the state that came out the
 * other end. The rule below is one line: **anything that gives up the audio path comes
 * before anything that takes it.**
 *
 * ## Why Telecom cannot disagree
 *
 * Task 56's third done-when says the system call UI and this app must never disagree.
 * They cannot, because both are driven from the same steps: the engine performs them and
 * reports each one to `PlatformCallRegistry` as it completes. There is no second path by
 * which this app changes a call's state, so there is nothing for the platform to fall out
 * of step with.
 */
object CallWaitingPolicy {

    /**
     * How to respond to [incoming] while [calls] are in progress.
     *
     * Calls that have ended are ignored rather than rejected as an error: the second call
     * may have arrived a moment after the first one ended, and answering it plainly is
     * then the right answer rather than a failure.
     */
    fun respondTo(
        calls: List<CallSnapshot>,
        incoming: CallId,
        response: SecondCallResponse,
    ): List<CallStep> {
        val others = calls.filter { it.callId != incoming && it.state.isActive }

        return when (response) {
            // 486 rather than 603: the user is on another call, which is what "busy"
            // means. Declining says they were free and refused, and the caller hears the
            // difference (§5.2).
            SecondCallResponse.REJECT -> listOf(CallStep.Reject(incoming, HangupReason.BUSY))

            // Hold first, answer second. The other order is two live calls.
            SecondCallResponse.ACCEPT_AND_HOLD ->
                others.filterNot { it.isLocallyHeld }.map { CallStep.Hold(it.callId) } +
                    CallStep.Answer(incoming)

            // End first, for the same reason: a BYE that has not been sent yet is still a
            // call holding the microphone.
            SecondCallResponse.ACCEPT_AND_END ->
                others.map { CallStep.Hangup(it.callId, HangupReason.LOCAL_HANGUP) } +
                    CallStep.Answer(incoming)
        }
    }

    /**
     * How to make [activate] the live call, holding everything else (Task 56).
     *
     * Every other call is held before [activate] is resumed — including calls that are
     * neither active nor held yet, which is why this filters on state rather than
     * assuming there are exactly two. A three-call state should not be reachable, and if
     * it ever is, this leaves one active call rather than two.
     *
     * Empty when [activate] is already the only live call: a swap that changes nothing
     * must not send a re-INVITE that changes nothing.
     */
    fun swapTo(calls: List<CallSnapshot>, activate: CallId): List<CallStep> {
        val target = calls.firstOrNull { it.callId == activate } ?: return emptyList()
        if (!target.state.isActive) return emptyList()

        val toHold = calls.filter { it.callId != activate && it.state.isActive && !it.isLocallyHeld }
        val resume = if (target.isLocallyHeld) listOf(CallStep.Resume(activate)) else emptyList()

        // Nothing to hold and nothing to resume means this call is already the live one.
        return if (toHold.isEmpty() && resume.isEmpty()) emptyList() else toHold.map { CallStep.Hold(it.callId) } + resume
    }

    /**
     * True when this side is holding the call.
     *
     * `HoldParty.REMOTE` deliberately does not count: a call the far end is holding is
     * not ours to resume, and treating it as held-by-us would produce a resume the FSM
     * rejects (see [CallStateMachine]).
     */
    private val CallSnapshot.isLocallyHeld: Boolean
        get() = (state as? CallState.Held)?.by.let { it == HoldParty.LOCAL || it == HoldParty.BOTH }
}
