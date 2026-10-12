package com.whatsappv2.domain.video

/**
 * The frame rate the user has asked outgoing video to run at.
 *
 * ## Why this is a choice at all, when the ladders had settled on one number
 *
 * Every rung of every ladder was pinned to 15 fps, and that was not arbitrary tuning — see
 * [VideoQualityProfiles]. 15 is the only rate below 30 that divides a 30 fps camera evenly:
 * every second frame, a constant 66.7 ms apart.
 *
 * **Whether the camera honours the request is the device's answer, not ours**, and the two
 * handset families measured here disagree. On the Samsungs (M14, M23) `capture` held at
 * 29.8 while the policy asked for 15, 20 and 24 on different legs: the camera ran at 30,
 * the encoder was fed all 30, and it kept what it wanted. On the TC15, 2026-10-12, `cap`
 * — which `vid_port.c` increments in the camera's own frame callback, before the pipeline
 * touches the frame — read 5.00, 9.98, 14.86, 19.88, 24.91 and 29.90 against requests of
 * 5, 10, 15, 20, 25 and 30. That camera runs at whatever it is asked for.
 *
 * The distinction is what [dividesCameraRate] is really about. On a camera fixed at 30, a
 * rate that does not divide 30 can only be reached by dropping frames on an uneven pattern
 * — 20 fps is 3:2, 33 ms, 67 ms, 33 ms, 67 ms, and 25 fps is 6:5 — which arrives as judder
 * even when not a single frame is lost, and no amount of bandwidth fixes it because nothing
 * is missing. On a camera that runs at 20 the frames are evenly spaced and there is nothing
 * to see. So the screen offering these rates names the *risk* rather than asserting the
 * fault, and offers the uneven ones rather than hiding them: 25 fps on a link that can
 * carry it looks better than 15 to most people, and that is the user's call to make.
 *
 * ## What the number reaches
 *
 * Not the encoder directly. [VideoQualityProfiles.forShape] builds the ladder at this rate,
 * so the rung the policy picks already carries it and the budget arithmetic is done in the
 * same terms — see [VideoQualityProfile.atFrameRate] for the bitrate that comes with it.
 * `AdaptiveVideoPolicy` is still free to step down the ladder; this sets what the rungs
 * mean, not what the call is guaranteed to get.
 */
enum class VideoFrameRate(val fps: Int) {
    /**
     * The floor, for a link that cannot carry motion at all.
     *
     * Divides the camera's rate evenly (every sixth frame), so it judders no more than 15
     * does. Offered because a still-ish picture that arrives is worth more than a smooth one
     * that does not: on a leg that the adaptive policy has already driven to the bottom rung,
     * the remaining choice is between few frames and none.
     */
    FPS_5(5),
    FPS_10(10),
    FPS_15(15),
    FPS_20(20),
    FPS_25(25),
    FPS_30(30),

    ;

    /**
     * Whether [CAMERA_FPS] divides evenly into this rate.
     *
     * True for 5, 10, 15 and 30 — every sixth, third, second and every frame. False for 20
     * and 25, which a camera fixed at 30 can only reach by dropping frames on an uneven
     * pattern. A camera that runs at the requested rate itself is unaffected, which is why
     * the screen reading this states a risk and not a defect.
     */
    val dividesCameraRate: Boolean get() = CAMERA_FPS % fps == 0

    companion object {
        /**
         * The rate a camera that cannot be steered delivers.
         *
         * `capture 29.8` was true on all four Samsung handsets while the policy was asking
         * for 15, 20 and 24 on different legs — there the encoder decides which of the 30 it
         * keeps. It is the worst case rather than the only case: the TC15's camera tracked
         * every requested rate to within 0.1 fps. Used only to say which rates divide it,
         * never to predict what a given device will do.
         */
        const val CAMERA_FPS: Int = 30

        /**
         * 15 fps, which is where the ladders were pinned before this was a choice.
         *
         * It divides the camera's rate, it is what every measurement behind
         * [VideoQualityProfiles] was taken at, and it is the rate a four-party mesh on these
         * handsets can actually sustain on three legs at once.
         */
        val DEFAULT: VideoFrameRate = FPS_15

        /** The rate whose [fps] is [fps], or null — for reading back a stored number. */
        fun ofFps(fps: Int): VideoFrameRate? = entries.firstOrNull { it.fps == fps }
    }
}
