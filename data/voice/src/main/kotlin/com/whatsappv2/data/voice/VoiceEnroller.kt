package com.whatsappv2.data.voice

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.domain.voice.EnrolmentProgress
import com.whatsappv2.domain.voice.EnrolmentRules
import com.whatsappv2.domain.voice.SpeakerEmbedder
import com.whatsappv2.domain.voice.VoiceEnrolment
import com.whatsappv2.domain.voice.VoiceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * Records the user and turns them into a voice profile, entirely on the device
 * (ADR-013).
 *
 * ## Only speech is counted, and only speech is kept
 *
 * "Record a minute of your voice" has to mean a minute of *voice*. A simple energy test
 * drops the silence between sentences, so the progress bar measures what the model will
 * actually be given and a user who pauses is not quietly enrolling a profile built
 * mostly of room tone. It also keeps the embedding honest: the vector is a mean, and
 * silence pulls it toward the room rather than the speaker.
 *
 * The threshold is relative to the loudest frame seen so far rather than absolute,
 * because a fixed one is wrong on every microphone gain.
 *
 * ## The audio does not outlive the function
 *
 * Samples are accumulated in memory, turned into 192 floats, and dropped. Nothing is
 * written to disk, nothing is uploaded, and there is no file for anything else to read:
 * at most ninety seconds of PCM exists on the heap, and only while this is running. That
 * is the privacy property, and it is structural rather than promised.
 */
@Singleton
class VoiceEnroller @Inject constructor(
    private val embedder: SpeakerEmbedder,
    private val logger: Logger,
) : VoiceEnrolment {
    /**
     * Records until [EnrolmentRules.MAXIMUM_SECONDS] of speech, then builds the profile.
     *
     * Cancel the collecting coroutine to abandon it; the recorder is released either way
     * and nothing is stored. The caller hands [EnrolmentProgress.Ready]'s profile to the
     * repository — this class deliberately cannot write one, so there is exactly one
     * place a profile is replaced.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun enrol(): Flow<EnrolmentProgress> = flow {
        if (!embedder.isAvailable) {
            emit(EnrolmentProgress.Failed(EnrolmentProgress.Failed.Reason.NO_MODEL))
            return@flow
        }

        val recorder = open()
        if (recorder == null) {
            emit(EnrolmentProgress.Failed(EnrolmentProgress.Failed.Reason.NO_MICROPHONE))
            return@flow
        }

        val speech = ArrayList<Short>(Fbank.SAMPLE_RATE * EnrolmentRules.TARGET_SECONDS)
        val chunk = ShortArray(CHUNK_SAMPLES)
        var loudest = 0.0
        var lastReported = -1

        try {
            recorder.startRecording()
            emit(EnrolmentProgress.Recording(0, 0f))

            while (speech.size < Fbank.SAMPLE_RATE * EnrolmentRules.MAXIMUM_SECONDS) {
                coroutineContext.ensureActive()
                val read = recorder.read(chunk, 0, chunk.size)
                if (read <= 0) continue

                val level = levelOf(chunk, read)
                if (level > loudest) loudest = level
                if (isSpeech(level, loudest)) {
                    for (i in 0 until read) speech.add(chunk[i])
                }

                val seconds = speech.size / Fbank.SAMPLE_RATE
                if (seconds != lastReported) {
                    lastReported = seconds
                    emit(EnrolmentProgress.Recording(seconds, EnrolmentRules.progress(seconds)))
                }
            }
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }

        val seconds = speech.size / Fbank.SAMPLE_RATE
        if (!EnrolmentRules.isEnough(seconds)) {
            logger.info(TAG, "Enrolment abandoned: only ${seconds}s of speech")
            emit(EnrolmentProgress.Failed(EnrolmentProgress.Failed.Reason.TOO_LITTLE_SPEECH))
            return@flow
        }

        emit(EnrolmentProgress.Building)
        val samples = ShortArray(speech.size) { speech[it] }
        speech.clear()

        val embedding = embedder.embed(samples)
        val profile = embedding?.let { VoiceProfile.of(it, seconds, System.currentTimeMillis()) }
        if (profile == null) {
            logger.warn(TAG, "Enrolment produced no embedding")
            emit(EnrolmentProgress.Failed(EnrolmentProgress.Failed.Reason.FAILED))
            return@flow
        }
        logger.info(TAG, "Voice profile built from ${seconds}s of speech")
        emit(EnrolmentProgress.Ready(profile))
    }.flowOn(Dispatchers.IO)

    private fun levelOf(chunk: ShortArray, read: Int): Double {
        var sumOfSquares = 0.0
        for (i in 0 until read) sumOfSquares += chunk[i].toDouble() * chunk[i]
        return sqrt(sumOfSquares / read)
    }

    /**
     * Whether a frame carries speech rather than room tone.
     *
     * Relative to the loudest frame so far, so it works at any microphone gain, with an
     * absolute floor so a completely silent room cannot enrol a profile out of its own
     * hiss.
     */
    private fun isSpeech(level: Double, loudest: Double): Boolean =
        level > loudest * SPEECH_FRACTION && level > ABSOLUTE_FLOOR

    @SuppressLint("MissingPermission")
    private fun open(): AudioRecord? = runCatching {
        val minimum = AudioRecord.getMinBufferSize(
            Fbank.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        AudioRecord(
            // The same preset the call path uses, so the profile is enrolled through the
            // processing the gate will later see: platform AEC and NS engaged. Enrolling
            // on a raw microphone would build a profile of audio that never reaches the
            // gate.
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            Fbank.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum, CHUNK_SAMPLES * 2) * BUFFERS,
        ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
    }.getOrElse {
        logger.warn(TAG, "Could not open the microphone for enrolment: ${it.message}")
        null
    }

    private companion object {
        const val TAG = "VoiceProfile"

        /** 100 ms. Short enough that the speech test tracks pauses, long enough to be cheap. */
        const val CHUNK_SAMPLES = Fbank.SAMPLE_RATE / 10

        const val BUFFERS = 4

        /** A frame counts as speech above this fraction of the loudest frame so far. */
        const val SPEECH_FRACTION = 0.12

        /** And never below this, so a silent room's own noise cannot enrol a profile. */
        const val ABSOLUTE_FLOOR = 150.0
    }
}
