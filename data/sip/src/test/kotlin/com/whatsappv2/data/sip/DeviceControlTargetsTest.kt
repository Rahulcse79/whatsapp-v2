package com.whatsappv2.data.sip

import com.whatsappv2.domain.model.CallId
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Which calls a device-level control reaches (ADR-009).
 *
 * The whole of the rule that decides whether one microphone is muted or one seventh of
 * it. Pure, so it is decidable here rather than on a handset with six peers — which is
 * what it took to find the defect in the first place.
 */
class DeviceControlTargetsTest {

    private val a = CallId("a")
    private val b = CallId("b")
    private val c = CallId("c")

    @Test
    fun `a call in the mix carries the control to the whole conference`() {
        assertEquals(setOf(a, b, c), deviceControlTargets(mixed = setOf(a, b, c), callId = b))
    }

    @Test
    fun `a call outside the mix is on its own`() {
        // A third call on hold beside a merged pair is not part of the conference, and
        // muting it must not silence the people in one.
        assertEquals(setOf(c), deviceControlTargets(mixed = setOf(a, b), callId = c))
    }

    @Test
    fun `with no conference the control reaches only the call it named`() {
        assertEquals(setOf(a), deviceControlTargets(mixed = emptySet(), callId = a))
    }

    @Test
    fun `the result always contains the call, so a control can never reach nothing`() {
        // The property that matters for a control the user just pressed: whatever the
        // membership says, the call they are looking at is acted on.
        listOf(emptySet(), setOf(a), setOf(a, b), setOf(b, c)).forEach { mixed ->
            assertEquals(true, a in deviceControlTargets(mixed, a), "membership $mixed dropped the call")
        }
    }
}
