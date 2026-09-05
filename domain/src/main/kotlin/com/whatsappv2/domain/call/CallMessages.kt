package com.whatsappv2.domain.call

import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.model.HangupReason

/**
 * What a failure and an ending are called on screen (Task 44, §4.2).
 *
 * ## One table, not one per screen
 *
 * The dialler and the in-call screen both have to name the same errors, and before this
 * existed each kept its own partial `when` with a generic branch at the bottom. Two
 * tables drift: the same 503 became "the server is busy" in one place and "that did not
 * work" in the other, and whichever screen a user happened to be on decided how much
 * they were told. Naming each case once, here, is the only way the two can agree — and
 * it is next to [SipError.toHangupReason] so an entry in the call log and the sentence
 * that was on screen come from the same reading of the same failure.
 *
 * ## Exhaustive on purpose
 *
 * Neither `when` below has an `else`. The compiler therefore refuses to build a new
 * [SipError] case or [HangupReason] until somebody has decided what the user is told
 * about it, which is a stronger guarantee than any test: the generic sentence cannot be
 * reached by forgetting, only by choosing it.
 *
 * ## The sentences
 *
 * Short, non-technical, and each says what to do about it where there is anything to be
 * done — "call failed" is true of every case and useful for none. No response code and
 * no stack detail reaches the user; both are already in the log, where a bug report can
 * find them.
 */
fun SipError.userMessage(): String = when (this) {
    // ---------------------------------------------------------------- authentication
    is SipError.AuthenticationFailed -> "Your username or password was rejected"
    is SipError.Forbidden -> "That account is not allowed to make this call"

    // ---------------------------------------------------------------- addressing
    is SipError.NotFound -> "That address does not exist"
    // A malformed request is our bug rather than the user's, but the address is the one
    // part they can change, so the sentence points there instead of blaming them.
    is SipError.BadRequest -> "That address could not be dialled"

    // ---------------------------------------------------------------- callee state
    is SipError.Busy -> "That line is busy"
    is SipError.Declined -> "The call was declined"
    is SipError.TemporarilyUnavailable -> "They are unavailable right now"
    is SipError.Timeout -> "Nobody answered"
    is SipError.Cancelled -> "The call was cancelled"

    // ---------------------------------------------------------------- server
    // The Retry-After figure is honoured by the backoff, not read out: a number the user
    // cannot act on is worse than "shortly".
    is SipError.ServiceUnavailable -> "The server is busy — try again shortly"
    is SipError.ServerError -> "The server could not complete the call"

    // ---------------------------------------------------------------- media
    // §7 / DoD 13: a mandatory-SRTP call that cannot be encrypted fails rather than
    // retrying in the clear, and the user is told that is why — an encryption failure
    // reported as "no shared audio format" would hide the one part that matters.
    is SipError.MediaNegotiationFailed -> if (encryptionRequired) {
        "The call could not be encrypted, so it was not connected"
    } else {
        "No audio format both ends support"
    }

    // ---------------------------------------------------------------- transport
    is SipError.TransportFailure -> "Could not reach the server"
    is SipError.NetworkUnavailable -> "No connection"

    // ---------------------------------------------------------------- local
    is SipError.UnknownAccount -> "That account could not be used"
    is SipError.UnknownCall -> "The call has already ended"
    is SipError.NotRegistered -> "That account is not registered yet"
    is SipError.InvalidState -> "Not possible right now"
    is SipError.EngineUnavailable -> "Calling is not available right now"
    is SipError.CallNotPermitted -> "Your phone is on another call"

    // The only generic sentence, and the only case that has earned one: Unexpected is
    // what is left when the stack reported something with no domain meaning. Every case
    // the server actually sent is named above, which is what stops this being the
    // "unknown error" that a user learns nothing from.
    is SipError.Unexpected -> "The call could not be completed"
}

/**
 * How a finished call is described once it is over.
 *
 * Past tense, because by the time this is read the call is gone: the screen is showing
 * what happened, not what is happening. [HangupReason.LOCAL_HANGUP] is the ordinary
 * ending and says the least — a user who hung up does not need to be told they did.
 *
 * These are the sentences Task 47's call log will list, which is why they read as
 * outcomes rather than as errors.
 */
fun HangupReason.userMessage(): String = when (this) {
    HangupReason.LOCAL_HANGUP -> "Call ended"
    HangupReason.REMOTE_HANGUP -> "They hung up"
    HangupReason.LOCAL_REJECTED -> "You declined the call"
    HangupReason.BUSY -> "That line was busy"
    HangupReason.DECLINED -> "The call was declined"
    HangupReason.NO_ANSWER -> "Nobody answered"
    HangupReason.CANCELLED -> "The call was cancelled"
    HangupReason.NETWORK_FAILURE -> "The connection was lost"
    HangupReason.MEDIA_FAILURE -> "The audio could not be set up"
    HangupReason.SERVER_ERROR -> "The server ended the call"
}
