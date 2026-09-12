package com.whatsappv2.data.sip.registration.stack

import com.whatsappv2.data.sip.call.StackCallState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule for reading a refused resume off the transaction callback (§2.1).
 *
 * `onCallTsxState` fires for every transaction on a call, in both directions, with every
 * state change. Exactly one of them is our resume being refused, and getting the guard
 * wrong in either direction is a stuck call: too loose and a failed BYE returns a running
 * call to "held"; too tight and a refused resume shows "resuming" for the rest of its life.
 */
class PendingResumeTest {

    @Test
    fun `a refused resume is reported once, while one is outstanding`() {
        val resume = PendingResume()
        resume.begin()

        assertTrue(resume.refusedBy(isClient = true, method = "INVITE", statusCode = NOT_ACCEPTABLE_HERE))
        // COMPLETED → TERMINATED restates the same code. Once answered is answered.
        assertFalse(resume.refusedBy(isClient = true, method = "INVITE", statusCode = NOT_ACCEPTABLE_HERE))
        assertFalse(resume.isOutstanding)
    }

    @Test
    fun `a failed INVITE with no resume outstanding is somebody else's problem`() {
        // A video escalation the far end refused, for instance: real, and not a resume.
        val resume = PendingResume()

        assertFalse(resume.refusedBy(isClient = true, method = "INVITE", statusCode = NOT_ACCEPTABLE_HERE))
    }

    @Test
    fun `a re-INVITE we refused is a server transaction, not our resume failing`() {
        val resume = PendingResume()
        resume.begin()

        assertFalse(resume.refusedBy(isClient = false, method = "INVITE", statusCode = NOT_ACCEPTABLE_HERE))
        assertTrue(resume.isOutstanding, "the resume is still in flight")
    }

    @Test
    fun `only an INVITE can be the resume`() {
        val resume = PendingResume()
        resume.begin()

        assertFalse(resume.refusedBy(isClient = true, method = "BYE", statusCode = CALL_DOES_NOT_EXIST))
        assertFalse(resume.refusedBy(isClient = true, method = "INFO", statusCode = NOT_IMPLEMENTED))
        assertFalse(resume.refusedBy(isClient = true, method = "", statusCode = 0))
        assertTrue(resume.isOutstanding)
    }

    @Test
    fun `a provisional or a success is not a refusal`() {
        val resume = PendingResume()
        resume.begin()

        assertFalse(resume.refusedBy(isClient = true, method = "INVITE", statusCode = TRYING))
        assertFalse(resume.refusedBy(isClient = true, method = "INVITE", statusCode = OK))
        assertTrue(resume.isOutstanding, "a 200 is answered by media, not by this")
    }

    @Test
    fun `media running again settles the resume, and a later refusal is not blamed on it`() {
        val resume = PendingResume()
        resume.begin()
        resume.onMediaState(StackCallState.STREAMS_RUNNING)

        assertFalse(resume.isOutstanding)
        assertFalse(resume.refusedBy(isClient = true, method = "INVITE", statusCode = NOT_ACCEPTABLE_HERE))
    }

    @Test
    fun `a hold restated while the re-INVITE is in flight does not settle it`() {
        // PJSIP re-reports LOCAL_HOLD during the flight. Clearing on that would disarm
        // the refusal check before the answer arrived, which is the bug this exists for.
        val resume = PendingResume()
        resume.begin()
        resume.onMediaState(StackCallState.PAUSED)

        assertTrue(resume.isOutstanding)
        assertTrue(resume.refusedBy(isClient = true, method = "INVITE", statusCode = NOT_ACCEPTABLE_HERE))
    }

    @Test
    fun `a cancelled resume is not outstanding`() {
        val resume = PendingResume()
        resume.begin()
        resume.cancel()

        assertFalse(resume.isOutstanding)
        assertFalse(resume.refusedBy(isClient = true, method = "INVITE", statusCode = NOT_ACCEPTABLE_HERE))
    }

    private companion object {
        const val TRYING = 100
        const val OK = 200
        const val CALL_DOES_NOT_EXIST = 481
        const val NOT_ACCEPTABLE_HERE = 488
        const val NOT_IMPLEMENTED = 501
    }
}
