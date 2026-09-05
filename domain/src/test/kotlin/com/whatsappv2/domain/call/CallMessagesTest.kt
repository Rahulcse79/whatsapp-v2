package com.whatsappv2.domain.call

import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.engine.TransportFailureKind
import com.whatsappv2.domain.model.HangupReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The whole table, case by case (Task 44).
 *
 * Coverage of [SipError] is the compiler's job, not this file's: the `when` in
 * [userMessage] has no `else`, so a new case will not build until it has a sentence.
 * What is asserted here is the part a compiler cannot check — that the sentence is the
 * right one, that no two cases share it, and that nothing from the wire leaks into it.
 *
 * [HangupReason] is an enum, so its coverage *is* checkable, and the last test walks
 * `entries` to prove no reason was left out of the table below.
 */
class CallMessagesTest {

    private data class Case(val error: SipError, val expected: String)

    /** Every [SipError] case, in the order the taxonomy declares them. */
    private val errorCases = listOf(
        Case(SipError.AuthenticationFailed(401), "Your username or password was rejected"),
        Case(SipError.Forbidden, "That account is not allowed to make this call"),
        Case(SipError.NotFound(404), "That address does not exist"),
        Case(SipError.BadRequest(400), "That address could not be dialled"),
        Case(SipError.Busy(486), "That line is busy"),
        Case(SipError.Declined, "The call was declined"),
        Case(SipError.TemporarilyUnavailable, "They are unavailable right now"),
        Case(SipError.Timeout, "Nobody answered"),
        Case(SipError.Cancelled, "The call was cancelled"),
        Case(
            SipError.ServiceUnavailable(retryAfterSeconds = 30),
            "The server is busy — try again shortly",
        ),
        Case(SipError.ServerError(500), "The server could not complete the call"),
        Case(
            SipError.MediaNegotiationFailed(responseCode = 488, encryptionRequired = false),
            "No audio format both ends support",
        ),
        Case(
            SipError.MediaNegotiationFailed(responseCode = null, encryptionRequired = true),
            "The call could not be encrypted, so it was not connected",
        ),
        Case(
            SipError.TransportFailure(TransportFailureKind.TLS_HANDSHAKE_FAILED),
            "Could not reach the server",
        ),
        Case(SipError.NetworkUnavailable, "No connection"),
        Case(SipError.UnknownAccount, "That account could not be used"),
        Case(SipError.UnknownCall, "The call has already ended"),
        Case(SipError.NotRegistered, "That account is not registered yet"),
        Case(SipError.InvalidState("resuming a call that is not held"), "Not possible right now"),
        Case(SipError.EngineUnavailable, "Calling is not available right now"),
        Case(SipError.CallNotPermitted, "Your phone is on another call"),
        Case(SipError.Unexpected("stack said 199"), "The call could not be completed"),
    )

    @Test
    fun `each error is named by its own sentence`() {
        for (case in errorCases) {
            assertEquals(case.expected, case.error.userMessage(), "for ${case.error}")
        }
    }

    @Test
    fun `no two errors are given the same sentence`() {
        // "Exactly one user-facing string" cuts both ways: a case that borrowed another's
        // sentence would tell the user the wrong thing while still passing the test above.
        val sentences = errorCases.map { it.error.userMessage() }
        assertEquals(sentences.size, sentences.toSet().size, "duplicate sentences: $sentences")
    }

    @Test
    fun `a mandatory-SRTP failure says so, rather than reading as a codec problem`() {
        // §7 / DoD 13. These two share a case and must not share a sentence: one is a
        // security outcome and the other is an ordinary negotiation failure.
        val encrypted = SipError.MediaNegotiationFailed(responseCode = null, encryptionRequired = true)
        val plain = SipError.MediaNegotiationFailed(responseCode = 488, encryptionRequired = false)

        assertTrue(encrypted.userMessage().contains("encrypted"))
        assertFalse(plain.userMessage().contains("encrypted"))
    }

    @Test
    fun `nothing from the wire reaches the user`() {
        // The response code and the stack's own words are in the log, where a bug report
        // can find them. Reading either out loud helps nobody and, for Unexpected, is
        // what the taxonomy explicitly forbids.
        val leaky = listOf(
            SipError.Unexpected(detail = "SAL_OP_FAILED", responseCode = 199),
            SipError.InvalidState(detail = "resuming a call that is not held"),
            SipError.ServerError(502),
            SipError.ServiceUnavailable(retryAfterSeconds = 120),
        )

        for (error in leaky) {
            val message = error.userMessage()
            assertFalse(message.any { it.isDigit() }, "$error read a number out: $message")
            assertFalse(message.contains("SAL_OP_FAILED"), "$error leaked stack detail: $message")
            assertFalse(message.contains("resuming"), "$error leaked its detail: $message")
        }
    }

    @Test
    fun `no error is left with an unknown-error sentence`() {
        // Task 44's second done-when. Unexpected is the one case allowed a general
        // sentence, because it is what is left when the stack reported nothing with a
        // domain meaning — every case the server actually sent is named.
        val named = errorCases.filterNot { it.error is SipError.Unexpected }

        for (case in named) {
            val message = case.error.userMessage()
            assertTrue(message.isNotBlank(), "${case.error} has no sentence")
            assertFalse(
                message == SipError.Unexpected("").userMessage(),
                "${case.error} falls back to the general sentence",
            )
        }
    }

    // ---------------------------------------------------------------- hangup reasons

    @Test
    fun `every reason a call can end is described`() {
        val expected = mapOf(
            HangupReason.LOCAL_HANGUP to "Call ended",
            HangupReason.REMOTE_HANGUP to "They hung up",
            HangupReason.LOCAL_REJECTED to "You declined the call",
            HangupReason.BUSY to "That line was busy",
            HangupReason.DECLINED to "The call was declined",
            HangupReason.NO_ANSWER to "Nobody answered",
            HangupReason.CANCELLED to "The call was cancelled",
            HangupReason.NETWORK_FAILURE to "The connection was lost",
            HangupReason.MEDIA_FAILURE to "The audio could not be set up",
            HangupReason.SERVER_ERROR to "The server ended the call",
        )

        // The enum is the source of truth, so a reason added without a sentence fails
        // here rather than reaching a call log as a blank line.
        assertEquals(HangupReason.entries.toSet(), expected.keys, "a reason is missing from the table")

        for ((reason, sentence) in expected) {
            assertEquals(sentence, reason.userMessage(), "for $reason")
        }
    }

    @Test
    fun `no two reasons are given the same sentence`() {
        val sentences = HangupReason.entries.map { it.userMessage() }
        assertEquals(sentences.size, sentences.toSet().size, "duplicate sentences: $sentences")
    }
}
