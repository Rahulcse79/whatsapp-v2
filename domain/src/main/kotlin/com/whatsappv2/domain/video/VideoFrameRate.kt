package com.whatsappv2.domain.video

/**
 * The frame rate the user has asked outgoing video to run at.
 *
 * ## Why this is a choice at all, when the ladders had settled on one number
 *
 * Every rung of every ladder was pinned to 15 fps, and that was not arbitrary tuning — see
 * [VideoQualityProfiles]. The camera produces 30 fps whatever is asked of it, the encoder is
 * fed all 30 and drops what it does not want, and 15 is the only rate below 30 that divides
 * it evenly: every second frame, a constant 66.7 ms apart.
 *
 * A rate that does not divide 30 arrives as judder even when not a single frame is lost.
 * 20 fps is a 3:2 pattern — 33 ms, 67 ms, 33 ms, 67 ms — and 25 fps is 6:5. No amount of
 * bandwidth fixes that, because nothing is missing; the frames are simply unevenly spaced.
 *
 * So the setting exists because people ask for it and because a higher rate genuinely is
 * smoother on a good link, and [dividesCameraRate] exists so the screen offering it can say
 * which of the six are even and which are not. It says so rather than hiding the uneven
 * ones: 25 fps on a link that can carry it still looks better than 15 to most people, and
 * that is the user's call to make with the facts in front of them.
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
     * True for 5, 10, 15 and 30 — every sixth, third, second and every frame. False for
     * 20 and 25, which the encoder can only reach by dropping frames on an uneven pattern.
     */
    val dividesCameraRate: Boolean get() = CAMERA_FPS % fps == 0

    companion object {
        /**
         * What the camera delivers, measured rather than requested.
         *
         * `capture 29.8` was true on all four handsets while the policy was asking for 15, 20
         * and 24 on different legs. The camera does not honour a frame-rate request from this
         * layer; the encoder decides which of the 30 it keeps.
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
