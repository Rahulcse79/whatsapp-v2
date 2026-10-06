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
 * The gate breaks the `capture -> leg` connection, one per call, through
 * `RealPjsipCoreGateway.applyCaptureRouting`. That is after RNNoise (which runs in
 * `rec_cb`, before the frame reaches the bridge at all) and before every encoder, so a
 * closed gate means Lyra encodes digital silence on every leg — which is what "before
 * Lyra" has to mean.
 *
 * **It deliberately does not attenuate the capture slot**, which is what it used to do and
 * what made it unusable. `conference.c` applies `rx_adj_level` in place on the source
 * port's own buffer *before* distributing the frame to any listener, and this gate's own
 * analysis tap is one of those listeners — so closing the gate fed the embedder digital
 * silence and it could never reopen. Breaking the per-call connections instead leaves
 * `capture -> tap` untouched, which is what makes a closed gate recoverable at all.
 *
 * It is not a patch inside `rec_cb` either, and that trade is still deliberate: a gate
 * there would be a new vendored patch, a JNI bridge and a native rebuild, to put the same
 * zeroes in the same samples a few microseconds earlier.
 *
 * The user's own mute wants the same edge, so neither writes it directly: both are weighed
 * by one owner on every change. Either can silence the microphone and neither can undo the
 * other — switching filtering off does not un-mute, and un-muting does not defeat the
 * gate.
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
 *
 * That was the failure this feature first shipped with, and it is the reason the gated edge
 * moved: the reopen promised above could not happen while closing the gate silenced the
 * very tap the reopen decision reads. See [Tap] for the measurement. Whether the gate runs
 * at all is now the user's, through `AppSettings.liveCallFilteringEnabled`.
 */
internal class VoiceGateController(
    private val embedder: SpeakerEmbedder,
    private val logger: Logger,
    /** Runs a block on the PJSIP thread, which is the only one that may touch pjsua2. */
    private val onPjsip: (String, () -> Unit) -> Unit,
    /**
     * Where a decision goes: true to let the microphone through, false to hold it.
     *
     * A callback rather than a level written here, and that is the whole fix. This gate
     * used to close by attenuating the capture slot it was itself listening to; the owner
     * on the other side of this breaks `capture -> leg` instead and leaves `capture -> tap`
     * alone, so the tap goes on hearing the microphone and a closed gate can still reopen.
     * Invoked on [worker]; the implementation hops to the PJSIP thread.
     */
    private val onDecision: (Boolean) -> Unit,
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
        runCatching { onDecision(true) }
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

            val startedAt = System.nanoTime()
            val live = embedder.embed(window)
            val tookMillis = (System.nanoTime() - startedAt) / 1_000_000

            val score = live?.let { target.similarityTo(it) }
            val wasOpen = gate.isOpen
            val nowOpen = gate.onWindow(score)

            // Every hop, at debug. Four lines a second is a lot for a log and exactly
            // right for this one: the score and the time it took are the only way to see
            // what the gate is doing on a real handset in a real room, and both were
            // asked for by name. `isLoggable` keeps it free when the level is off.
            logger.debug(
                TAG,
                "window score=${score?.let { "%.3f".format(it) } ?: "none"} " +
                    "took=${tookMillis}ms open=$nowOpen",
            )

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
        // No level is written here any more. Attenuating the capture slot silenced this
        // gate's own analysis tap - see the note on [Tap] - so the decision goes to the
        // owner of the `capture -> leg` connections instead.
        runCatching { onDecision(open) }
            .onFailure { logger.warn(TAG, "Could not ${if (open) "open" else "close"} the gate: ${it.message}") }
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
        /**
         * Why the gate does not attenuate the slot its own tap listens to.
         *
         * `pjmedia_conf_adjust_rx_level` on the capture slot looks like the obvious way to
         * gate a microphone, and it was how this shipped. `conference.c:2838-2857` applies
         * `rx_adj_level` **in place on the source port's own buffer**, before the frame is
         * distributed to any listener — and [Tap] is a listener of that same slot. So the
         * instant the gate closed, the tap read the zeroes the gate had just written, the
         * embedder scored digital silence for ever, and the score could never climb back
         * over the threshold.
         *
         * On a handset it was not subtle. A call to 9196 with a profile enrolled
         * (2026-10-05, M23/1001):
         *
         *     13:29:02.363  window score=-0.139 took=254ms open=true
         *     13:29:02.997  window score=-0.139 took=137ms open=false
         *     13:29:02.997  Voice gate closed: this is not the enrolled speaker
         *     ... every window to the end of the call: open=false
         *
         * Identical to three decimals in all 19 windows. [stop] reopened the level, so each
         * new call got its first few seconds and then went silent — which is what "voice not
         * clear" turned out to be.
         *
         * Fails-open covers a gate that never closes, not one that cannot un-close. The fix
         * was to gate a different edge from the one the tap listens to: the per-call
         * `capture -> leg` connections, owned by `RealPjsipCoreGateway.applyCaptureRouting`.
         * `pjmedia_conf_adjust_conn_level` (`conference.h:901`) would express the same thing
         * as a level rather than a disconnect, and is unexposed in the Java bindings —
         * reaching it needs a vendored patch and a native rebuild, which buys nothing here
         * because the connection is already the app's to make and break.
         */

        const val TAG = "VoiceGate"
        const val PORT_NAME = "coralx-voice-gate"
        const val SAMPLE_RATE = 16_000
        const val FRAME_MILLIS = 20L
        val WINDOW_SAMPLES = SAMPLE_RATE * SpeakerGate.WINDOW_MILLIS / 1_000

    }
}
