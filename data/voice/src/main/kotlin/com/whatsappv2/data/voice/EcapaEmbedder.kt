package com.whatsappv2.data.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.domain.voice.SpeakerEmbedder
import com.whatsappv2.domain.voice.VoiceProfile
import java.io.File
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Turns speech into a 192-dimensional speaker embedding, on the device (ADR-013).
 *
 * WeSpeaker's `voxceleb_ECAPA512_LM`, vendored at `third_party/wespeaker/` and run by ONNX
 * Runtime. Nothing leaves the handset: the model is in the APK, the inference is local,
 * and the only thing that outlives a call is 192 floats in this app's private storage.
 *
 * ## Why ONNX Runtime and not the TFLite already linked
 *
 * TFLite would have needed no new dependency — Lyra links it into `libpjsua2.so`. It is
 * not used because the model could not be converted: `onnx2tf` aborts at
 * `BatchNormalization_11` on a layout mismatch in the 1-D convolution axes, and int8
 * dynamic quantisation yields a model with no CPU kernel (`ConvInteger`). Both are
 * written up in `third_party/wespeaker/README.md`. Running the ONNX unchanged also means
 * the thresholds in `SpeakerGate` describe *this* file, which is what makes the host
 * measurements in `VOICE-PROFILE.md` statements about the shipped behaviour.
 *
 * ## Never on the audio thread
 *
 * One two-second window costs tens of milliseconds. [embed] is blocking and is called
 * from a worker; the capture callback only ever reads a flag the worker set. Marked
 * `@Singleton` because an `OrtSession` holds the whole 24 MB of weights and a second one
 * would be a second copy.
 */
@Singleton
class EcapaEmbedder @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val logger: Logger,
) : SpeakerEmbedder {
    private val lock = Any()
    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var unavailable = false

    /**
     * Whether the model is loaded and usable.
     *
     * Checked rather than assumed by every caller, because the gate's contract is to fail
     * **open**: a model that will not load must leave the microphone alone, not mute it.
     */
    override val isAvailable: Boolean
        get() = synchronized(lock) { !unavailable && (session != null || tryLoad()) }

    /**
     * The embedding of [samples], L2-normalised, or null when one cannot be produced.
     *
     * Null is not an error the caller should recover from — it is "no opinion", and
     * `SpeakerGate` treats no opinion as the user. Reasons it happens: fewer than one
     * 25 ms frame of audio, or a model that did not load.
     *
     * @param samples 16 kHz mono 16-bit PCM. Two seconds is what the gate uses and what
     *   the measurements were taken at; shorter windows verify much worse.
     */
    override fun embed(samples: ShortArray): FloatArray? {
        val active = synchronized(lock) { if (tryLoad()) session else null } ?: return null
        val feats = Fbank.of(samples)
        if (feats.isEmpty()) return null

        val frames = feats.size
        val flat = FloatBuffer.allocate(frames * Fbank.N_MELS)
        for (row in feats) flat.put(row)
        flat.rewind()

        return runCatching {
            OnnxTensor.createTensor(
                environment,
                flat,
                longArrayOf(1, frames.toLong(), Fbank.N_MELS.toLong()),
            ).use { input ->
                active.run(mapOf(INPUT to input)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val out = (result[0].value as Array<FloatArray>)[0]
                    normalise(out)
                }
            }
        }.onFailure {
            logger.warn(TAG, "The speaker model failed on a window: ${it.message}")
        }.getOrNull()
    }

    /** Releases the session. The next [embed] loads it again. */
    fun close() {
        synchronized(lock) {
            runCatching { session?.close() }
            session = null
        }
    }

    private fun tryLoad(): Boolean {
        if (session != null) return true
        if (unavailable) return false
        return runCatching {
            val file = modelFile()
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                // One thread. This runs four times a second beside a live call whose
                // media threads are already the busiest things on the device; letting
                // the model fan out would take cycles from the audio that matters.
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
            }
            environment = env
            session = env.createSession(file.absolutePath, options)
            logger.info(TAG, "Speaker model loaded from ${file.name}")
            true
        }.getOrElse {
            // Latched. A model that is not in the APK will not appear later, and retrying
            // on every window would log once per 250 ms for the life of the call.
            unavailable = true
            logger.warn(TAG, "No speaker model, so the voice profile is unavailable: ${it.message}")
            false
        }
    }

    /**
     * The model, copied out of assets once.
     *
     * ONNX Runtime wants a path or a byte array. A path keeps the 24 MB mapped rather
     * than on the Java heap, which is the difference between a background load and an
     * `OutOfMemoryError` on a handset already carrying Lyra's models and a video pipeline.
     */
    private fun modelFile(): File {
        val target = File(context.filesDir, MODEL_ASSET)
        if (target.exists() && target.length() > 0) return target
        context.assets.open(MODEL_ASSET).use { source ->
            target.outputStream().use { sink -> source.copyTo(sink) }
        }
        return target
    }

    private fun normalise(values: FloatArray): FloatArray? {
        var sumOfSquares = 0.0
        for (v in values) sumOfSquares += v.toDouble() * v
        val norm = sqrt(sumOfSquares).toFloat()
        if (!norm.isFinite() || norm <= 0f) return null
        return FloatArray(values.size) { values[it] / norm }
    }

    private companion object {
        const val TAG = "VoiceProfile"
        const val INPUT = "feats"
        const val MODEL_ASSET = "voxceleb_ECAPA512_LM.onnx"
    }
}

/** The embedding width this model produces; the profile asserts the same number. */
internal const val EMBEDDING_DIMENSIONS = VoiceProfile.DIMENSIONS
