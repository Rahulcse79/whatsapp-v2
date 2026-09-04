package com.whatsappv2.domain.call

import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.TransferType

/**
 * Which party currently has the call on hold.
 *
 * Both sides can hold simultaneously, and resuming then must **not** put the call back
 * to [CallState.Connected] — the other party is still holding. Collapsing this into a
 * single boolean is how "resume did nothing" bugs happen.
 */
enum class HoldParty {
    LOCAL,
    REMOTE,
    BOTH,
    ;

    /** After the local side resumes; `null` means nobody is holding any more. */
    fun withoutLocal(): HoldParty? = when (this) {
        LOCAL -> null
        REMOTE -> REMOTE
        BOTH -> REMOTE
    }

    /** After the remote side resumes; `null` means nobody is holding any more. */
    fun withoutRemote(): HoldParty? = when (this) {
        LOCAL -> LOCAL
        REMOTE -> null
        BOTH -> LOCAL
    }

    /** After the local side holds. */
    fun withLocal(): HoldParty = if (this == REMOTE || this == BOTH) BOTH else LOCAL

    /** After the remote side holds. */
    fun withRemote(): HoldParty = if (this == LOCAL || this == BOTH) BOTH else REMOTE
}

/**
 * The phase of a single call (§4.4).
 *
 * Sealed, so the compiler enumerates the cases and a `when` over them cannot silently
 * miss one. Every state that has media carries [CallControls], because mute and audio
 * route must survive a hold — losing them on resume is a real and very visible bug.
 */
sealed interface CallState {

    /** No call. The starting and, conceptually, the ending point. */
    data object Idle : CallState

    /** An outgoing call that has not yet been answered. */
    sealed interface Outgoing : CallState {

        /** INVITE sent, no provisional response yet. */
        data object Calling : Outgoing

        /** 180 Ringing — the far end is alerting. */
        data object Ringing : Outgoing

        /**
         * 183 Session Progress with SDP: the far end is sending media before answering,
         * typically a network announcement or ringback. Audio must be played.
         */
        data object EarlyMedia : Outgoing
    }

    /** An inbound INVITE that has not been answered or rejected. */
    data class Incoming(val from: SipUri) : CallState

    /** Media is flowing in both directions. */
    data class Connected(val controls: CallControls = CallControls.DEFAULT) : CallState

    /** One or both parties are holding. [by] says which. */
    data class Held(
        val by: HoldParty,
        val controls: CallControls = CallControls.DEFAULT,
    ) : CallState

    /** A re-INVITE to resume is in flight; media is not yet flowing again. */
    data class Resuming(val controls: CallControls = CallControls.DEFAULT) : CallState

    /** A REFER is in flight. A failure returns to [Connected], not to a dead end. */
    data class Transferring(
        val type: TransferType,
        val controls: CallControls = CallControls.DEFAULT,
    ) : CallState

    /** The call is over. Absorbing: no event moves it anywhere. */
    data class Terminated(val reason: HangupReason) : CallState

    /** True when media is or has been negotiated, so [controlsOrNull] is meaningful. */
    val isEstablished: Boolean
        get() = this is Connected || this is Held || this is Resuming || this is Transferring

    /** The current controls, or `null` in a phase that has no media yet. */
    val controlsOrNull: CallControls?
        get() = when (this) {
            is Connected -> controls
            is Held -> controls
            is Resuming -> controls
            is Transferring -> controls
            else -> null
        }

    /** True when the call can still be acted on. */
    val isActive: Boolean get() = this !is Idle && this !is Terminated
}
