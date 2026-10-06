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
import kotlinx.coroutines.flow.FlowCollector
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
 * The threshold is relative to how loud this person has recently been rather than
 * absolute, because a fixed one is wrong on every microphone gain. [SpeechLevel] owns that
 * rule and explains why "recently" has to decay.
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
     * Set by [requestFinish] and cleared at the start of every [enrol].
     *
     * Volatile because it is written from the main thread, by the button, and read by the
     * recording loop on [Dispatchers.IO]. A single boolean needs nothing stronger: the
     * loop re-reads it once per 100 ms chunk and a late read costs one chunk.
     */
    @Volatile
    private var finishRequested = false

    /**
     * Records until [EnrolmentRules.MAXIMUM_SECONDS] of speech, or until [requestFinish],
     * then builds the profile.
     *
     * Cancel the collecting coroutine to abandon it; the recorder is released either way
     * and nothing is stored. The caller hands [EnrolmentProgress.Ready]'s profile to the
     * repository — this class deliberately cannot write one, so there is exactly one
     * place a profile is replaced.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun enrol(): Flow<EnrolmentProgress> = flow {
        // Cleared here rather than in requestFinish, so a finish asked for after the last
        // recording ended cannot cut the next one short before it has captured anything.
        finishRequested = false

        if (!embedder.isAvailable) {
            emit(EnrolmentProgress.Failed(EnrolmentProgress.Failed.Reason.NO_MODEL))
            return@flow
        }

        val recorder = open()
        if (recorder == null) {
            emit(EnrolmentProgress.Failed(EnrolmentProgress.Failed.Reason.NO_MICROPHONE))
            return@flow
        }

        val samples = try {
            recorder.startRecording()
            record(recorder)
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }

        val seconds = samples.size / Fbank.SAMPLE_RATE
        if (!EnrolmentRules.isEnough(seconds)) {
            logger.info(TAG, "Enrolment abandoned: only ${seconds}s of speech")
            emit(EnrolmentProgress.Failed(EnrolmentProgress.Failed.Reason.TOO_LITTLE_SPEECH))
            return@flow
        }

        emit(EnrolmentProgress.Building)
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

    /**
     * Collects speech until the target is reached, reporting progress as it goes.
     *
     * Returns only the frames that carried speech, so the number the user watches is the
     * number the model is given.
     */
    private suspend fun FlowCollector<EnrolmentProgress>.record(recorder: AudioRecord): ShortArray {
        val speech = ArrayList<Short>(Fbank.SAMPLE_RATE * EnrolmentRules.TARGET_SECONDS)
        val chunk = ShortArray(CHUNK_SAMPLES)
        val target = Fbank.SAMPLE_RATE * EnrolmentRules.MAXIMUM_SECONDS
        val deadline = System.nanoTime() + WALL_CLOCK_LIMIT_SECONDS * NANOS_PER_SECOND
        var loudest = 0.0
        var lastReported = -1

        emit(EnrolmentProgress.Recording(0, 0f))
        while (speech.size < target && System.nanoTime() <= deadline && !finishRequested) {
            coroutineContext.ensureActive()
            val read = recorder.read(chunk, 0, chunk.size)
            if (read <= 0) continue

            val level = levelOf(chunk, read)
            // Decays, rather than only ever rising. The bar for "this is speech" is a
            // fraction of this, so when it was an all-time maximum a single loud transient
            // at the start - the handset being picked up, a door, a headset's pairing tone -
            // put the bar above the user's ordinary speaking level and left it there for
            // the rest of the recording. Measured on a handset: the same person on the same
            // phone counted 26 seconds of speech in 48 after a quiet start, and 5 seconds in
            // 180 after a loud one, with the microphone open and working throughout. The
            // user is given no way to tell why, because from the outside it looks like the
            // counter has simply stopped.
            loudest = SpeechLevel.nextReference(loudest, level)
            if (SpeechLevel.isSpeech(level, loudest)) {
                for (i in 0 until read) speech.add(chunk[i])
            }

            val seconds = speech.size / Fbank.SAMPLE_RATE
            if (seconds != lastReported) {
                lastReported = seconds
                emit(EnrolmentProgress.Recording(seconds, EnrolmentRules.progress(seconds)))
            }
        }
        return ShortArray(speech.size) { speech[it] }
    }

    /**
     * Ends the recording loop at its next chunk, so [enrol] builds from what it has.
     *
     * Only a flag: the loop owns the recorder and does its own release, so reaching in to
     * stop the hardware from here would be a second owner for the one thing that must have
     * exactly one.
     */
    override fun requestFinish() {
        finishRequested = true
    }

    private fun levelOf(chunk: ShortArray, read: Int): Double {
        var sumOfSquares = 0.0
        for (i in 0 until read) sumOfSquares += chunk[i].toDouble() * chunk[i]
        return sqrt(sumOfSquares / read)
    }


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

        // What counts as speech lives in [SpeechLevel], which is testable without a
        // microphone. It used to be two constants and a one-line predicate here, which is
        // how it stayed wrong long enough to be measured wrong twice.

        /**
         * How long recording may run in total, however little speech arrives.
         *
         * Five minutes against a 90-second target: somebody reading aloud with ordinary
         * pauses reaches the target in two to three, so this only ever fires when
         * something is wrong - a silent room, a muted microphone, a handset held too far
         * away. When it does, the result is the honest "that was not enough speech"
         * rather than a recorder left open.
         */
        const val WALL_CLOCK_LIMIT_SECONDS = 300L

        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
