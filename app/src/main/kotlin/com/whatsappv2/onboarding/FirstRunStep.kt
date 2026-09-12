package com.whatsappv2.onboarding

/**
 * Which of the three first-run screens is owed, or none.
 *
 * Derived from the stores on every recomposition rather than held as a step counter: the
 * question "what does this user still owe" has exactly one right answer at any moment, and
 * a counter is a second place for it to be wrong. It also means a process death mid-flow
 * resumes where it was, because the answer was never in memory.
 */
internal enum class FirstRunStep {
    /** The terms, which must be accepted before anything else is shown. */
    TERMS,

    /** What the app does, once. */
    TOUR,

    /** Microphone, notifications and the rest — the screen that already existed. */
    PERMISSIONS,

    /** Nothing owed; the app itself. */
    READY,
}

/**
 * The order, as a function rather than as control flow.
 *
 * Terms first because accepting them is the condition on everything after — showing
 * somebody the features of a thing they have not agreed to use puts the agreement after
 * the sell, which is the wrong way round. Permissions last because they are the only step
 * that asks for something rather than telling: by then the user has seen what the
 * microphone is for, which is the difference between a dialog and an ambush.
 */
internal fun firstRunStep(
    termsAccepted: Boolean,
    tourSeen: Boolean,
    permissionsNeeded: Boolean,
): FirstRunStep = when {
    !termsAccepted -> FirstRunStep.TERMS
    !tourSeen -> FirstRunStep.TOUR
    permissionsNeeded -> FirstRunStep.PERMISSIONS
    else -> FirstRunStep.READY
}
