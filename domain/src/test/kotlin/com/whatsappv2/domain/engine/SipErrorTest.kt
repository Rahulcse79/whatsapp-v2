package com.whatsappv2.domain.engine

import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.RegistrationFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SipErrorTest {

    // ---------------------------------------------------------------- response mapping

    @Test
    fun `each documented response code maps to its documented error`() {
        data class Case(val code: Int, val expected: SipError)

        val cases = listOf(
            Case(401, SipError.AuthenticationFailed(401)),
            Case(407, SipError.AuthenticationFailed(407)),
            Case(403, SipError.Forbidden),
            Case(404, SipError.NotFound(404)),
            Case(410, SipError.NotFound(410)),
            Case(604, SipError.NotFound(604)),
            Case(408, SipError.Timeout),
            Case(480, SipError.TemporarilyUnavailable),
            Case(486, SipError.Busy(486)),
            Case(600, SipError.Busy(600)),
            Case(487, SipError.Cancelled),
            Case(603, SipError.Declined),
            Case(488, SipError.MediaNegotiationFailed(488, encryptionRequired = false)),
            Case(606, SipError.MediaNegotiationFailed(606, encryptionRequired = false)),
            Case(415, SipError.MediaNegotiationFailed(415, encryptionRequired = false)),
            Case(503, SipError.ServiceUnavailable(null)),
        )

        for (case in cases) {
            assertEquals(case.expected, SipError.fromResponseCode(case.code), "for code ${case.code}")
        }
    }

    @Test
    fun `unmapped codes fall back to their response class`() {
        assertIs<SipError.BadRequest>(SipError.fromResponseCode(400))
        assertIs<SipError.BadRequest>(SipError.fromResponseCode(420))
        assertIs<SipError.ServerError>(SipError.fromResponseCode(500))
        assertIs<SipError.ServerError>(SipError.fromResponseCode(502))
        assertIs<SipError.ServerError>(SipError.fromResponseCode(504))
        assertIs<SipError.ServerError>(SipError.fromResponseCode(601))
    }

    @Test
    fun `a code outside any known class is Unexpected rather than mis-classified`() {
        val error = SipError.fromResponseCode(199)
        assertIs<SipError.Unexpected>(error)
        assertEquals(199, error.responseCode)
    }

    @Test
    fun `Retry-After is preserved so the server's backoff can be honoured`() {
        // §2.1: with 5,000 clients, ignoring Retry-After produces a second stampede.
        val error = SipError.fromResponseCode(503, retryAfterSeconds = 120)
        assertEquals(SipError.ServiceUnavailable(120), error)
        assertEquals(120, (error as SipError.ServiceUnavailable).retryAfterSeconds)
    }

    @Test
    fun `a 503 without Retry-After carries null rather than a guess`() {
        assertNull((SipError.fromResponseCode(503) as SipError.ServiceUnavailable).retryAfterSeconds)
    }

    @Test
    fun `errors that came from the wire report their code`() {
        assertEquals(486, SipError.fromResponseCode(486).responseCode)
        assertEquals(403, SipError.Forbidden.responseCode)
        assertEquals(603, SipError.Declined.responseCode)
        assertEquals(408, SipError.Timeout.responseCode)
        assertEquals(487, SipError.Cancelled.responseCode)
        assertEquals(480, SipError.TemporarilyUnavailable.responseCode)
        assertEquals(503, SipError.ServiceUnavailable(null).responseCode)
    }

    @Test
    fun `locally originated errors carry no response code`() {
        val local = listOf(
            SipError.NetworkUnavailable,
            SipError.UnknownAccount,
            SipError.UnknownCall,
            SipError.NotRegistered,
            SipError.EngineUnavailable,
            SipError.CallNotPermitted,
            SipError.InvalidState("not held"),
            SipError.TransportFailure(TransportFailureKind.CONNECTION_LOST),
        )
        for (error in local) {
            assertNull(error.responseCode, "$error should carry no response code")
        }
    }

    @Test
    fun `busy and declined are distinct because the user sees different messages`() {
        assertTrue(SipError.fromResponseCode(486) != SipError.fromResponseCode(603))
    }

    // ---------------------------------------------------------------- registration mapping

    @Test
    fun `registration failures separate what the user must fix from what will retry`() {
        data class Case(val error: SipError, val expected: RegistrationFailure)

        val cases = listOf(
            Case(SipError.AuthenticationFailed(401), RegistrationFailure.AUTHENTICATION_FAILED),
            Case(SipError.Forbidden, RegistrationFailure.ACCOUNT_REJECTED),
            Case(SipError.NotFound(404), RegistrationFailure.ACCOUNT_REJECTED),
            Case(SipError.BadRequest(400), RegistrationFailure.INVALID_CONFIGURATION),
            Case(SipError.InvalidState("x"), RegistrationFailure.INVALID_CONFIGURATION),
            Case(SipError.NetworkUnavailable, RegistrationFailure.NETWORK_UNAVAILABLE),
            Case(SipError.Timeout, RegistrationFailure.TIMEOUT),
            Case(SipError.ServiceUnavailable(30), RegistrationFailure.SERVER_UNAVAILABLE),
            Case(SipError.ServerError(500), RegistrationFailure.SERVER_UNAVAILABLE),
            Case(
                SipError.TransportFailure(TransportFailureKind.TLS_HANDSHAKE_FAILED),
                RegistrationFailure.TRANSPORT_FAILURE,
            ),
        )

        for (case in cases) {
            assertEquals(case.expected, case.error.toRegistrationFailure(), "for ${case.error}")
        }
    }

    @Test
    fun `a wrong password is reported as authentication failed and not as a generic error`() {
        // Task 31 requires "Authentication failed", not "something went wrong".
        val failure = SipError.fromResponseCode(401).toRegistrationFailure()
        assertEquals(RegistrationFailure.AUTHENTICATION_FAILED, failure)
        assertTrue(failure.requiresUserAction, "retrying a wrong password forever is pointless")
    }

    @Test
    fun `transient failures do not demand user action`() {
        for (error in listOf(SipError.Timeout, SipError.NetworkUnavailable, SipError.ServiceUnavailable(null))) {
            assertTrue(!error.toRegistrationFailure().requiresUserAction, "$error should retry on its own")
        }
    }

    // ---------------------------------------------------------------- hangup mapping

    @Test
    fun `call failures map to the reason the call log records`() {
        data class Case(val error: SipError, val expected: HangupReason)

        val cases = listOf(
            Case(SipError.Busy(486), HangupReason.BUSY),
            Case(SipError.Declined, HangupReason.DECLINED),
            Case(SipError.Timeout, HangupReason.NO_ANSWER),
            Case(SipError.TemporarilyUnavailable, HangupReason.NO_ANSWER),
            Case(SipError.Cancelled, HangupReason.CANCELLED),
            Case(SipError.MediaNegotiationFailed(488, false), HangupReason.MEDIA_FAILURE),
            Case(SipError.NetworkUnavailable, HangupReason.NETWORK_FAILURE),
            Case(
                SipError.TransportFailure(TransportFailureKind.CONNECTION_LOST),
                HangupReason.NETWORK_FAILURE,
            ),
            Case(SipError.ServerError(500), HangupReason.SERVER_ERROR),
            Case(SipError.NotFound(404), HangupReason.SERVER_ERROR),
            // Telecom refused before the INVITE, so nothing failed on the wire: the call
            // was called off before it started (Task 35, §3).
            Case(SipError.CallNotPermitted, HangupReason.CANCELLED),
        )

        for (case in cases) {
            assertEquals(case.expected, case.error.toHangupReason(), "for ${case.error}")
        }
    }

    @Test
    fun `reasons that precede an answer are classified as such`() {
        for (error in listOf(SipError.Busy(486), SipError.Declined, SipError.Timeout, SipError.Cancelled)) {
            assertTrue(error.toHangupReason().endedBeforeAnswer, "$error ends the call before answer")
        }
    }

    // ---------------------------------------------------------------- media policy

    @Test
    fun `a mandatory-SRTP failure is distinguishable from an ordinary codec mismatch`() {
        // DoD 13: SRTP-mandatory must FAIL the call, so the two cases cannot be conflated.
        val codecMismatch = SipError.MediaNegotiationFailed(488, encryptionRequired = false)
        val srtpRequired = SipError.MediaNegotiationFailed(null, encryptionRequired = true)
        assertTrue(codecMismatch != srtpRequired)
        assertTrue(srtpRequired.encryptionRequired)
        assertNull(srtpRequired.responseCode, "a local SRTP refusal never reaches the wire")
    }

    @Test
    fun `transport failures name what actually failed`() {
        for (kind in TransportFailureKind.entries) {
            val error = SipError.TransportFailure(kind)
            assertEquals(kind, error.detail)
            assertEquals(RegistrationFailure.TRANSPORT_FAILURE, error.toRegistrationFailure())
        }
    }
}
