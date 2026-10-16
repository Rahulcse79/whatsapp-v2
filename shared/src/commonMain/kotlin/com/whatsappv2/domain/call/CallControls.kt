package com.whatsappv2.domain.call

/**
 * The orthogonal attributes of a live call (§4.4).
 *
 * These are deliberately **not** states. Mute, audio route, video and recording each
 * vary independently and none of them changes what the call may do next — folding them
 * into [CallState] would multiply the state count by sixteen and make the transition
 * table unreadable, while gaining nothing.
 *
 * They are booleans, and that is fine: §4.4 forbids booleans modelling call *phase*,
 * not booleans modelling a toggle that genuinely is on or off.
 */
data class CallControls(
    val isMuted: Boolean = false,
    val audioRoute: AudioRoute = AudioRoute.EARPIECE,
    val isVideoEnabled: Boolean = false,
    val isRecording: Boolean = false,
) {
    companion object {
        /** An audio call, unmuted, on the earpiece, not recording. */
        val DEFAULT: CallControls = CallControls()
    }
}
