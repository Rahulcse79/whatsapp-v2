package com.whatsappv2.onboarding

/**
 * What [SignInGate] is showing.
 *
 * Three states rather than a boolean, because "the stored session has not been read yet"
 * and "there is no session" are different answers and only one of them should put a login
 * form on screen. Collapsing them is what makes a login flash on every cold start.
 */
internal enum class SignInGateState {
    /** The disk has not answered yet. Draw nothing — see [SignInGate]. */
    Unknown,

    /** Nobody is signed in. The login replaces the app. */
    SignedOut,

    /** There is a session. The app is reachable. */
    SignedIn,
}
