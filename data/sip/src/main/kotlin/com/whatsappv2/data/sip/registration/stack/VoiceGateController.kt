package com.whatsappv2.data.sip.registration.stack

import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.domain.voice.SpeakerEmbedder
import com.whatsappv2.domain.voice.SpeakerGate
import com.whatsappv2.domain.voice.VoiceProfile
import org.pjsip.pjsua2.AudioMedia
import org.pjsip.pjsua2.AudioMediaPort
import org.pjsip.pjsua2.MediaFormatAudio
import org.pjsip.pjsua2.MediaFrame
import org.pjsip.pjsua2.pjmedia_type
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Keeps the microphone open only while the enrolled user is the one talking (ADR-013).
 *
 * RNNoise, in the capture callback, removes *noise*. The one thing a noise suppressor
 * structurally cannot remove is other people's speech, because speech is what it keeps —
 * measured, babble is RNNoise's worst class by a distance. This closes that gap.
 *
 * ## Where it sits, and why not inside rec_cb()
 *
 * The gate is applied with `pjmedia_conf_adjust_rx_level` on the capture slot: the level
 * the **bridge receives from the microphone**. That is after RNNoise (which runs in
 * `rec_cb`, before the frame reaches the bridge at all) and before every encoder, so a
 * closed gate means Lyra encodes digital silence on every leg — which is what "before
 * Lyra" has to mean.
 *
 * It is not a patch inside `rec_cb` itself, and the trade is deliberate. A level
 * adjustment is one existing call on one slot for the whole device; a gate in `rec_cb`
 * would be a new vendored patch, a JNI bridge, and a native rebuild, to put the same
 * zeroes in the same samples a few microseconds earlier. `adjustRxLevel(0)` is exact
 * silence rather than attenuation: pjsua passes `(level-1)*128`, so 0 becomes
 * `rx_adj_level = 0` and `conference.c` computes `sample * 0 / 128`.
 *
 * It is also independent of the user's own mute, which works by `stopTransmit` on each
 * call. The two compose instead of fighting: either can silence the microphone and
 * neither can undo the other.
 *
 * ## Everything slow is off the audio thread
 *
 * [Tap.onFrameReceived] runs on a pjmedia thread and does one thing: copy 320 samples
 * into a ring buffer under a short lock. The embedding — tens of milliseconds — runs on
 * [worker], four times a second, over the trailing two seconds. The audio path only ever
 * reads a decision somebody else made.
 *
 * The tap asks the bridge for **16 kHz** audio even though the bridge mixes at 48, so
 * pjmedia's own resampler does the conversion at the quality `RealPjsipCoreGateway`
 * already configured. Doing it here would be a second resampler to get wrong.
 *
 * ## Fails open, everywhere
 *
 * No profile, no model, a model that throws, a window too short to score, a port the
 * stack refuses — every one of those leaves the microphone alone. The gate also starts
 * open and reopens on a single matching window. Muting the user is the failure this
 * feature cannot have, and it is the one they could not diagnose.
 */
internal class VoiceGateController(
    private val embedder: SpeakerEmbedder,
    private val logger: Logger,
    /** Runs a block on the PJSIP thread, which is the only one that may touch pjsua2. */
    private val onPjsip: (String, () -> Unit) -> Unit,
) {
    private val lock = Any()
    private val ring = ShortArray(WINDOW_SAMPLES)
    private var written = 0L

    private var tap: Tap? = null
    private var capture: AudioMedia? = null
    private var gate = SpeakerGate()
    private var profile: VoiceProfile? = null
    private var worker: java.util.concurrent.ScheduledExecutorService? = null

    /** True while the tap is attached. */
    val isRunning: Boolean get() = tap != null

    /**
     * Starts gating [captureMedia] against [against].
     *
     * Idempotent for the same profile. Called on the PJSIP thread.
     */
    fun start(captureMedia: AudioMedia, against: VoiceProfile) {
        if (tap != null && profile == against) return
        stop()

        if (!embedder.isAvailable) {
            logger.info(TAG, "Voice gate not started: no speaker model, so the microphone is left alone")
            return
        }

        val port = runCatching {
            Tap().apply {
                createPort(
                    PORT_NAME,
                    MediaFormatAudio().apply {
                        type = pjmedia_type.PJMEDIA_TYPE_AUDIO
                        clockRate = SAMPLE_RATE.toLong()
                        channelCount = 1
                        bitsPerSample = 16
                        frameTimeUsec = FRAME_MILLIS * 1_000L
                    },
                )
            }
        }.getOrElse {
            logger.warn(TAG, "Voice gate not started: the stack refused the tap port (${it.message})")
            return
        }

        val attached = runCatching { captureMedia.startTransmit(port) }
        if (attached.isFailure) {
            logger.warn(TAG, "Voice gate not started: ${attached.exceptionOrNull()?.message}")
            runCatching { port.delete() }
            return
        }

        synchronized(lock) {
            java.util.Arrays.fill(ring, 0)
            written = 0
        }
        tap = port
        capture = captureMedia
        profile = against
        gate = SpeakerGate()
        worker = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "voice-gate").apply { isDaemon = true }
        }.also {
            it.scheduleAtFixedRate(
                ::decide,
                SpeakerGate.WINDOW_MILLIS.toLong(),
                SpeakerGate.HOP_MILLIS.toLong(),
                TimeUnit.MILLISECONDS,
            )
        }
        logger.info(TAG, "Voice gate on: ${SpeakerGate.WINDOW_MILLIS}ms window every ${SpeakerGate.HOP_MILLIS}ms")
    }

    /** Detaches the tap and leaves the microphone open. Called on the PJSIP thread. */
    fun stop() {
        worker?.shutdownNow()
        worker = null
        val port = tap
        val media = capture
        tap = null
        capture = null
        profile = null
        if (port == null) return

        // Open first, then tear down: a gate that is removed while closed would leave the
        // microphone silenced with nothing left to reopen it.
        runCatching { media?.adjustRxLevel(OPEN_LEVEL) }
        runCatching { media?.stopTransmit(port) }
        runCatching { port.delete() }
        logger.info(TAG, "Voice gate off")
    }

    /**
     * One decision: embed the trailing window, score it, and apply any change.
     *
     * Runs on [worker]. Never throws: a failure here must not stop the schedule, because
     * the schedule is also what reopens the gate.
     */
    private fun decide() {
        runCatching {
            val target = profile ?: return@runCatching
            val window = snapshot() ?: return@runCatching
            val live = embedder.embed(window)
            val wasOpen = gate.isOpen
            val nowOpen = gate.onWindow(live?.let { target.similarityTo(it) })
            if (nowOpen != wasOpen) apply(nowOpen)
        }.onFailure { logger.warn(TAG, "Voice gate decision failed, leaving the microphone open: ${it.message}") }
    }

    /** The trailing [WINDOW_SAMPLES], or null until that much has been captured. */
    private fun snapshot(): ShortArray? = synchronized(lock) {
        if (written < WINDOW_SAMPLES) return null
        val out = ShortArray(WINDOW_SAMPLES)
        val head = (written % WINDOW_SAMPLES).toInt()
        System.arraycopy(ring, head, out, 0, WINDOW_SAMPLES - head)
        System.arraycopy(ring, 0, out, WINDOW_SAMPLES - head, head)
        out
    }

    private fun apply(open: Boolean) {
        val media = capture ?: return
        onPjsip("voiceGate") {
            runCatching { media.adjustRxLevel(if (open) OPEN_LEVEL else CLOSED_LEVEL) }
                .onFailure { logger.warn(TAG, "Could not ${if (open) "open" else "close"} the gate: ${it.message}") }
        }
        logger.info(TAG, if (open) "Voice gate opened" else "Voice gate closed: this is not the enrolled speaker")
    }

    /**
     * The bridge port the captured audio is copied out of.
     *
     * A SWIG director, so a strong reference has to outlive the native side — [tap] is
     * that reference, and [stop] is what releases it. Losing it would have the collector
     * free a port pjmedia is still writing into.
     */
    private inner class Tap : AudioMediaPort() {
        override fun onFrameReceived(frame: MediaFrame) {
            val buf = runCatching { frame.buf }.getOrNull() ?: return
            val bytes = buf.size
            if (bytes < 2) return
            synchronized(lock) {
                var i = 0
                while (i + 1 < bytes) {
                    // Little-endian int16, the only format this port was created with.
                    val low = buf[i].toInt() and 0xFF
                    val high = buf[i + 1].toInt()
                    ring[((written) % WINDOW_SAMPLES).toInt()] = ((high shl 8) or low).toShort()
                    written++
                    i += 2
                }
            }
        }

        /** Nothing is ever read *from* this port; it is a sink. */
        override fun onFrameRequested(frame: MediaFrame) = Unit
    }

    private companion object {
        const val TAG = "VoiceGate"
        const val PORT_NAME = "coralx-voice-gate"
        const val SAMPLE_RATE = 16_000
        const val FRAME_MILLIS = 20L
        val WINDOW_SAMPLES = SAMPLE_RATE * SpeakerGate.WINDOW_MILLIS / 1_000

        /** `pjsua_conf_adjust_rx_level` maps 0 to an exact zero and 1 to no change. */
        const val OPEN_LEVEL = 1.0f
        const val CLOSED_LEVEL = 0.0f
    }
}
