package com.whatsappv2.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.di.ApplicationScope
import com.whatsappv2.domain.call.AudioRoute
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.engine.CallSnapshot
import com.whatsappv2.domain.engine.SipCallController
import com.whatsappv2.domain.engine.SipMediaController
import com.whatsappv2.domain.model.CallId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Audio focus, routing and the proximity sensor, for as long as a call lasts (Task 40).
 *
 * ## Why it watches the engine rather than being called
 *
 * Because there is no single caller. A call starts from the dialler, from a notification
 * action, from Telecom's own answer button on a lock screen or a car display; audio has to
 * follow the call in every one of those cases. Watching `activeCalls` is the only place
 * that is true of all of them.
 *
 * ## Where the decisions are
 *
 * In [AudioRoutePolicy], which is pure. This class reads devices off `AudioManager`,
 * applies the policy's answer through the engine — and therefore through Telecom, which is
 * what actually owns routing — and holds the proximity lock. Nothing here decides anything
 * that could be decided without a device.
 */
@Singleton
class CallAudioCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calls: SipCallController,
    private val media: SipMediaController,
    private val proximity: ProximityLock,
    @ApplicationScope private val scope: CoroutineScope,
    private val logger: Logger,
) {

    private val handler = Handler(Looper.getMainLooper())

    /** The call audio is currently following, if any. */
    private var activeCall: CallId? = null

    /** The route the user last asked for, so a device change does not silently undo it. */
    private var chosenRoute: AudioRoute? = null

    /** True when focus loss muted the call, so regaining it can unmute exactly that. */
    private var mutedByFocusLoss = false

    /** The last route this coordinator asked for, to tell its own choices from the user's. */
    private var lastApplied: AudioRoute? = null

    private var focusRequest: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        onFocusChanged(change)
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            // The arriving device wins: plugging in mid-call is a clearer instruction than
            // any button pressed earlier (Task 40's second and third done-whens).
            val arrived = addedDevices?.firstNotNullOfOrNull { it.toRoute() }
            applyRoute(arrived)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            // Unplugging falls back to whatever is left, which must never be nothing.
            applyRoute(arrived = null)
        }
    }

    /**
     * Begins following calls.
     *
     * Called once from `Application.onCreate`, beside the SIP stack. It holds nothing
     * until a call exists — no focus, no sensor, no callback registered — so starting it
     * early costs a coroutine and nothing else.
     */
    fun start() {
        scope.launch {
            calls.activeCalls.collect { active ->
                val call = active.firstOrNull { it.needsAudio }
                when {
                    call == null -> if (activeCall != null) end()
                    call.callId != activeCall -> begin(call)
                    else -> follow(call)
                }
            }
        }
    }

    /**
     * True once this call has audio to route.
     *
     * Established calls, and early media — a 183 with SDP means the network is already
     * sending sound, and a ringback with no audio focus is a ringback the user may not
     * hear over whatever else is playing.
     */
    private val CallSnapshot.needsAudio: Boolean
        get() = state.isEstablished || state is CallState.Outgoing.EarlyMedia

    /**
     * Notices a route this coordinator did not choose.
     *
     * That is how a user's choice is recognised without being told about it: every
     * automatic route goes out through [applyRoute], which records what it asked for, so a
     * route in the call's own controls that differs came from somewhere else — the in-call
     * screen, a car display, a headset button. It is then respected until a device change
     * makes it impossible.
     */
    private fun follow(call: CallSnapshot) {
        val current = call.state.controlsOrNull?.audioRoute ?: return
        if (current == lastApplied) return

        chosenRoute = current
        lastApplied = current
        // The earpiece is the only route where a phone is at an ear, and so the only one
        // where the screen should go dark.
        if (current == AudioRoute.EARPIECE) proximity.acquire() else proximity.release()
    }

    private fun begin(call: CallSnapshot) {
        // A second call replacing the first releases the first's focus and listener before
        // taking new ones. Without this, a call-waiting hand-off (Task 56) would register
        // the device callback twice and abandon focus once.
        if (activeCall != null) end()

        activeCall = call.callId
        chosenRoute = null
        lastApplied = null
        mutedByFocusLoss = false

        requestFocus()
        audioManager()?.registerAudioDeviceCallback(deviceCallback, handler)
        applyRoute(arrived = null)
        proximity.acquire()
        logger.info(TAG, "Call audio started for ${call.callId}")
    }

    private fun end() {
        proximity.release()
        audioManager()?.unregisterAudioDeviceCallback(deviceCallback)
        abandonFocus()
        activeCall = null
        chosenRoute = null
        lastApplied = null
        mutedByFocusLoss = false
        logger.info(TAG, "Call audio released")
    }

    private fun applyRoute(arrived: AudioRoute?) {
        val callId = activeCall ?: return
        val route = AudioRoutePolicy.routeAfterDeviceChange(currentDevices(), chosenRoute, arrived)
        lastApplied = route

        scope.launch {
            // Asked of the engine rather than set on AudioManager: Telecom owns routing,
            // and two things setting it would fight over the SCO link.
            media.setAudioRoute(callId, route)
            if (route == AudioRoute.EARPIECE) proximity.acquire() else proximity.release()
        }
    }

    private fun onFocusChanged(change: Int) {
        val callId = activeCall ?: return

        when (AudioRoutePolicy.actionFor(change)) {
            FocusAction.MUTE -> {
                mutedByFocusLoss = true
                logger.info(TAG, "Audio focus lost; muting the microphone")
                scope.launch { media.setMuted(callId, muted = true) }
            }

            FocusAction.RESUME -> if (mutedByFocusLoss) {
                mutedByFocusLoss = false
                // Only what focus loss muted. Unmuting a call the user muted themselves
                // would put a live microphone in a room they thought was private.
                scope.launch { media.setMuted(callId, muted = false) }
            }

            FocusAction.IGNORE -> Unit
        }
    }

    private fun requestFocus() {
        val audio = audioManager() ?: return

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener, handler)
            .build()

        focusRequest = request
        val granted = audio.requestAudioFocus(request)
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // Not fatal: the call still has audio, it is simply sharing. Logged because a
            // refusal here is usually another calling app holding focus.
            logger.warn(TAG, "Audio focus was not granted")
        }
    }

    private fun abandonFocus() {
        val audio = audioManager() ?: return
        focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun currentDevices(): AudioDevices {
        val audio = audioManager() ?: return AudioDevices()
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        return AudioDevices(
            hasBluetooth = outputs.any { it.toRoute() == AudioRoute.BLUETOOTH },
            hasWiredHeadset = outputs.any { it.toRoute() == AudioRoute.WIRED_HEADSET },
            hasEarpiece = outputs.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE },
        )
    }

    /**
     * The route a device belongs to, or null for one this app does not route to.
     *
     * BLE audio is Bluetooth as far as the user is concerned — it appears as its own
     * device type from API 31, and treating it as anything else would leave a connected
     * earbud looking unavailable.
     */
    private fun AudioDeviceInfo.toRoute(): AudioRoute? = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioRoute.BLUETOOTH
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        -> AudioRoute.WIRED_HEADSET

        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoute.SPEAKER
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.EARPIECE
        else -> bleRouteOrNull()
    }

    private fun AudioDeviceInfo.bleRouteOrNull(): AudioRoute? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
            AudioRoute.BLUETOOTH
        } else {
            null
        }

    private fun audioManager(): AudioManager? = context.getSystemService(AudioManager::class.java)

    private companion object {
        const val TAG = "CallAudioCoordinator"
    }
}
