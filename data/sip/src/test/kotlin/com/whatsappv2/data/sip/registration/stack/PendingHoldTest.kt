package com.whatsappv2.data.sip.registration.stack

import com.whatsappv2.data.sip.call.StackCallState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [PendingHold] — the answer PJSIP never gives to a hold re-INVITE.
 *
 * The defect these guard is silent by nature: a refused hold leaves the call connected,
 * which looks right, while the engine goes on waiting for an answer that has already been
 * refused. Every later press of Hold is then told one is already in flight, and the button
 * is dead for the rest of the call with nothing anywhere saying why.
 */
class PendingHoldTest {

    private val hold = PendingHold()

    @Test
    fun `nothing is outstanding before a hold is asked for`() {
        assertFalse(hold.isOutstanding)
        assertFalse(hold.refusedBy(isClient = true, method = "INVITE", statusCode = 488))
    }

    @Test
    fun `media paused by us settles the hold`() {
        hold.begin()
        hold.onMediaState(StackCallState.PAUSED)

        assertFalse(hold.isOutstanding)
    }

    @Test
    fun `the far end holding us does not settle our own hold`() {
        // A different question answered. Our re-INVITE is still unanswered, and clearing
        // here would disarm the refusal detection before the answer arrived.
        hold.begin()
        hold.onMediaState(StackCallState.PAUSED_BY_REMOTE)

        assertTrue(hold.isOutstanding)
    }

    @Test
    fun `a failed client INVITE while a hold is out is that hold being refused`() {
        hold.begin()

        assertTrue(hold.refusedBy(isClient = true, method = "INVITE", statusCode = 488))
        assertFalse(hold.isOutstanding)
    }

    @Test
    fun `a refusal is reported once, not again as the transaction winds down`() {
        hold.begin()
        assertTrue(hold.refusedBy(isClient = true, method = "INVITE", statusCode = 500))

        // COMPLETED then TERMINATED carry the same code; reporting twice would publish a
        // second failure for a hold that has already been settled.
        assertFalse(hold.refusedBy(isClient = true, method = "INVITE", statusCode = 500))
    }

    @Test
    fun `a re-INVITE we answer is not our hold failing`() {
        hold.begin()

        // A server transaction is the far end asking us something, not our request coming
        // back refused.
        assertFalse(hold.refusedBy(isClient = false, method = "INVITE", statusCode = 488))
        assertTrue(hold.isOutstanding)
    }

    @Test
    fun `another method failing is not our hold failing`() {
        hold.begin()

        assertFalse(hold.refusedBy(isClient = true, method = "INFO", statusCode = 500))
        assertTrue(hold.isOutstanding)
    }

    @Test
    fun `a success is not a refusal`() {
        hold.begin()

        assertFalse(hold.refusedBy(isClient = true, method = "INVITE", statusCode = 200))
        assertTrue(hold.isOutstanding)
    }

    @Test
    fun `a call going away settles whatever was outstanding`() {
        hold.begin()
        hold.cancel()

        assertFalse(hold.isOutstanding)
    }
}
