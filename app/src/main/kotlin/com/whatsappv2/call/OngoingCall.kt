package com.whatsappv2.call

import com.whatsappv2.domain.engine.SipCallController
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.telecom.SipConnectionService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which call the in-call screen should be showing (Task 45, §6).
 *
 * ## Two sources, neither of them the screen's memory
 *
 * A killed process takes its activities, its ViewModels and any `SavedStateHandle` with
 * it, so none of those can say whether a call is still up — they can only say what was
 * true before. The two things that can are the platform's Telecom connections and the
 * engine's own list, and both are rebuilt from what is actually still running rather than
 * from what the app remembered.
 *
 * Telecom is asked first because it outlives more: the engine is re-created empty with the
 * process and takes a moment to catch up, while a connection the platform is still holding
 * is a call that is still there right now.
 *
 * ## An empty answer is an answer
 *
 * [current] returns null when nothing is up, and the screen finishes on it. A call screen
 * that cannot say which call it is showing is worse than no screen: it cannot be
 * dismissed, and it invites the user to press buttons that go nowhere.
 */
@Singleton
class OngoingCall @Inject constructor(
    private val calls: SipCallController,
) {

    /**
     * The call to show, preferring [requested] when it is still real.
     *
     * A stale id — the screen reopened for a call that has since ended — is treated as no
     * request at all rather than as an error, because by the time this is asked the user
     * has already been shown a notification for whatever is actually happening.
     */
    fun current(requested: CallId? = null): CallId? {
        val live = liveCallIds()
        return requested?.takeIf { it in live } ?: live.firstOrNull()
    }

    /**
     * The call a *fresh* intent means, for a screen that is already open.
     *
     * Deliberately not [current], and the difference is where the intent came from.
     * [current] answers `onCreate`, whose intent may be one the system stored before the
     * process died — so a named call is checked against what is actually up, and a stale
     * one falls back to whatever is live. This answers `onNewIntent`, whose intent was
     * built moments ago by a route that had just placed or answered a call. Checking that
     * one would reintroduce the defect it exists to fix: the engine publishes a new call
     * asynchronously, so for a beat the named call is not in [liveCallIds] yet, the
     * fallback picks "the first live call" — and that is precisely the *old* one the
     * screen is already wrongly showing.
     *
     * Null when the intent names nothing and nothing is up, which is a caller's cue to
     * leave the screen where it is rather than to finish it: the screen it is on is still
     * a call.
     */
    fun next(requested: CallId?): CallId? = requested ?: current()

    /**
     * Everything either source calls live.
     *
     * The union rather than one or the other: during startup the engine has not caught up
     * with Telecom, and at the very end of a call Telecom has already let go while the
     * engine is still tearing down. Taking both means the screen is never blank in the gap.
     */
    private fun liveCallIds(): Set<CallId> =
        SipConnectionService.liveCallIds() + calls.activeCalls.value.map { it.callId }
}
