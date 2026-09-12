package com.whatsappv2.audio

import com.whatsappv2.domain.call.AudioRoute
import com.whatsappv2.domain.model.PreferredAudioRoute

/** Which output devices are attached right now. */
data class AudioDevices(
    val hasBluetooth: Boolean = false,
    val hasWiredHeadset: Boolean = false,
    /** Almost every handset has one; a tablet may not. */
    val hasEarpiece: Boolean = true,
) {
    /** Every route that could be selected right now. */
    val available: Set<AudioRoute>
        get() = buildSet {
            if (hasBluetooth) add(AudioRoute.BLUETOOTH)
            if (hasWiredHeadset) add(AudioRoute.WIRED_HEADSET)
            if (hasEarpiece) add(AudioRoute.EARPIECE)
            // Every device has a loudspeaker; it is the fallback when nothing else exists.
            add(AudioRoute.SPEAKER)
        }
}

/** What to do when audio focus changes. */
enum class FocusAction {
    /** Keep going. Focus was granted or regained. */
    RESUME,

    /**
     * Mute the microphone and stop playing.
     *
     * A cellular call arriving is the case that matters: the SIP call must not keep
     * sending audio into a conversation the user has left (Task 40's fourth done-when).
     */
    MUTE,

    /** Duck. Nothing to do for a voice call, which cannot meaningfully be quieter. */
    IGNORE,
}

/**
 * Where call audio should go, and what a focus change means (Task 40, DoD 8).
 *
 * Pure and Android-free, so both questions can be asserted on the JVM. The values that
 * come in are read off `AudioManager` and `AudioFocusRequest` by [CallAudioCoordinator],
 * which does no deciding of its own.
 */
object AudioRoutePolicy {

    /**
     * The route to use when the user has not chosen one on this call.
     *
     * [PreferredAudioRoute.AUTOMATIC]: Bluetooth beats a wired headset beats the earpiece.
     * That order is what people expect from every phone they have used: a connected
     * headset is where they are listening, and the earpiece is the fallback that always
     * exists. The speaker is never automatic — it is a deliberate choice, and choosing it
     * for someone puts their call on the desk in front of a room.
     *
     * The other two values are that deliberate choice, made once in Settings rather than
     * on every call: a desk phone in a workshop starts on the speaker, a phone that is
     * always at an ear starts on the earpiece with the headset left for music. The
     * setting is "where calls start" — it seeds the route, and a headset arriving
     * mid-call still wins in [routeAfterDeviceChange], because plugging one in is a
     * physical act that says more than a preference set last month.
     *
     * Until this parameter existed, the Settings control wrote a value nothing read.
     */
    fun preferredRoute(
        devices: AudioDevices,
        preference: PreferredAudioRoute = PreferredAudioRoute.AUTOMATIC,
    ): AudioRoute = when (preference) {
        PreferredAudioRoute.SPEAKER -> AudioRoute.SPEAKER
        PreferredAudioRoute.EARPIECE -> if (devices.hasEarpiece) AudioRoute.EARPIECE else AudioRoute.SPEAKER
        PreferredAudioRoute.AUTOMATIC -> when {
            devices.hasBluetooth -> AudioRoute.BLUETOOTH
            devices.hasWiredHeadset -> AudioRoute.WIRED_HEADSET
            devices.hasEarpiece -> AudioRoute.EARPIECE
            else -> AudioRoute.SPEAKER
        }
    }

    /**
     * The route after the set of devices changed mid-call.
     *
     * Two rules, and the tension between them is the whole problem:
     *
     * - **A headset that arrives wins**, even over an explicit choice. Someone who plugs
     *   in during a call is telling the phone where they want the audio, and more clearly
     *   than a button they pressed a minute ago.
     * - **A choice that is still possible is kept.** Speaker stays speaker while nothing
     *   changes underneath it; unplugging the headset that was chosen falls back to the
     *   preferred route rather than to silence.
     *
     * @param chosen the route the user last selected, or null if they never did.
     * @param preference the Settings choice for where calls start, used only when there
     *   is no [chosen] route still possible.
     */
    fun routeAfterDeviceChange(
        devices: AudioDevices,
        chosen: AudioRoute?,
        arrived: AudioRoute? = null,
        preference: PreferredAudioRoute = PreferredAudioRoute.AUTOMATIC,
    ): AudioRoute = when {
        // A headset appearing is a physical act, and it wins.
        arrived == AudioRoute.BLUETOOTH || arrived == AudioRoute.WIRED_HEADSET -> arrived
        chosen != null && chosen in devices.available -> chosen
        else -> preferredRoute(devices, preference)
    }

    /**
     * Whether the proximity sensor may blank the screen.
     *
     * ## The earpiece is not sufficient on its own
     *
     * The rule used to be exactly `route == EARPIECE`, on the reasoning that the earpiece
     * is the only route where a phone is against a face. That is true of a voice call and
     * false of a video one: a video call routed to the earpiece is held *in front of* a
     * face, at arm's length, and the proximity sensor sits at the top of the handset where
     * a hand passes over it constantly. The screen went black in the middle of the picture
     * the user was watching, and stayed black until they moved away.
     *
     * So video vetoes it. There is no video call on any handset where blanking the display
     * is the right answer, whatever the audio route happens to be.
     *
     * @param hasVideo true when the call has a video stream, negotiated or requested.
     */
    fun screenMayBlank(route: AudioRoute, hasVideo: Boolean): Boolean =
        route == AudioRoute.EARPIECE && !hasVideo

    /**
     * What a focus change means.
     *
     * `LOSS` and `LOSS_TRANSIENT` both mute: an incoming cellular call takes focus
     * transiently, and a SIP call that keeps its microphone open during it is a microphone
     * recording a conversation the user thinks is private. Ducking is ignored because a
     * voice call has nothing useful to duck to.
     *
     * @param focusChange `AudioManager.AUDIOFOCUS_*`, passed as an Int so this file needs
     *   no Android import and can be tested on the JVM.
     */
    fun actionFor(focusChange: Int): FocusAction = when (focusChange) {
        FOCUS_GAIN -> FocusAction.RESUME
        FOCUS_LOSS, FOCUS_LOSS_TRANSIENT -> FocusAction.MUTE
        FOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusAction.IGNORE
        else -> FocusAction.IGNORE
    }

    /**
     * `AudioManager.AUDIOFOCUS_*`, mirrored rather than imported.
     *
     * The same trade `TelecomPolicy` makes with the route constants: these are part of the
     * public platform contract and have not changed since Froyo, and copying them is what
     * lets the decision be made somewhere a test can reach.
     */
    const val FOCUS_GAIN = 1
    const val FOCUS_LOSS = -1
    const val FOCUS_LOSS_TRANSIENT = -2
    const val FOCUS_LOSS_TRANSIENT_CAN_DUCK = -3
}
