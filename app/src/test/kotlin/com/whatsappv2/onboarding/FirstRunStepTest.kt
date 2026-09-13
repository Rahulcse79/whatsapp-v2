package com.whatsappv2.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The order of the first-run screens, as a decision rather than as nesting.
 *
 * The order is the part with a reason behind it, so it is the part with a test. Driving
 * three real screens to assert which one came first would test Compose; this tests the
 * sequence.
 */
class FirstRunStepTest {

    @Test
    fun `a fresh install is shown the terms first`() {
        // Before anything else, including the tour: showing somebody the features of a
        // thing they have not agreed to use puts the agreement after the sell.
        assertEquals(
            FirstRunStep.TERMS,
            firstRunStep(termsAccepted = false, tourSeen = false, permissionsNeeded = true),
        )
    }

    @Test
    fun `the terms come before the tour even when the tour was somehow already seen`() {
        // Not "the next step after the last one", but "what is still owed" — which is the
        // whole reason this is derived from the stores rather than counted.
        assertEquals(
            FirstRunStep.TERMS,
            firstRunStep(termsAccepted = false, tourSeen = true, permissionsNeeded = false),
        )
    }

    @Test
    fun `accepting the terms leads to the tour`() {
        assertEquals(
            FirstRunStep.TOUR,
            firstRunStep(termsAccepted = true, tourSeen = false, permissionsNeeded = true),
        )
    }

    @Test
    fun `permissions are asked for last, once the user has seen what they are for`() {
        assertEquals(
            FirstRunStep.PERMISSIONS,
            firstRunStep(termsAccepted = true, tourSeen = true, permissionsNeeded = true),
        )
    }

    @Test
    fun `a returning user is shown none of it`() {
        assertEquals(
            FirstRunStep.READY,
            firstRunStep(termsAccepted = true, tourSeen = true, permissionsNeeded = false),
        )
    }
}
