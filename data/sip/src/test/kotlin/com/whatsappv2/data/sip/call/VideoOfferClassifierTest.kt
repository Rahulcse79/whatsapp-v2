package com.whatsappv2.data.sip.call

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule that decides whether the far end's video offer is a question for the user.
 *
 * Asserted here rather than on a handset because the cost of getting it wrong is a call
 * that drops thirteen seconds later on a *different* device — which is as hard to
 * attribute as a bug gets, and which is how the wrong answer survived three attempts.
 */
class VideoOfferClassifierTest {

    @Test
    fun `video on a call that never had it is an escalation`() {
        // The one case the user is asked about: an audio call, and the far end has just
        // pressed their video button.
        assertTrue(
            VideoOfferClassifier.isEscalation(
                remoteVideoCount = 1,
                videoActive = false,
                everHadVideo = false,
            ),
        )
    }

    @Test
    fun `resuming a held video call is not an escalation`() {
        // The defect. Hold deactivates video, so the unhold re-INVITE re-offers it and
        // looks exactly like an escalation to anything that only reads the offer. This is
        // what Merge sends to every held member, and prompting for it dropped the call.
        assertFalse(
            VideoOfferClassifier.isEscalation(
                remoteVideoCount = 1,
                videoActive = false,
                everHadVideo = true,
            ),
        )
    }

    @Test
    fun `a re-INVITE while video is already running is not an escalation`() {
        // A session-timer refresh, or a bridge re-syncing its legs. Carries the whole
        // offer, video included, and asks nothing of anybody.
        assertFalse(
            VideoOfferClassifier.isEscalation(
                remoteVideoCount = 1,
                videoActive = true,
                everHadVideo = true,
            ),
        )
    }

    @Test
    fun `an audio-only offer is never an escalation`() {
        // No video offered, so there is nothing to accept — whatever the history.
        listOf(false, true).forEach { ever ->
            listOf(false, true).forEach { active ->
                assertFalse(
                    VideoOfferClassifier.isEscalation(
                        remoteVideoCount = 0,
                        videoActive = active,
                        everHadVideo = ever,
                    ),
                    "audio-only offer with active=$active ever=$ever",
                )
            }
        }
    }

    @Test
    fun `more than one offered video stream is still one question`() {
        // remVideoCount is a count, not a flag. A peer offering two streams is offering
        // video; the prompt does not multiply.
        assertTrue(
            VideoOfferClassifier.isEscalation(
                remoteVideoCount = 2,
                videoActive = false,
                everHadVideo = false,
            ),
        )
    }
}
