package com.whatsappv2.data.voice

import android.content.Context
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.domain.voice.VoiceProfile
import com.whatsappv2.domain.voice.VoiceProfileRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The voice profile, in one small file in this app's private storage (ADR-013).
 *
 * ## A file and not DataStore
 *
 * The payload is 192 floats and three scalars — 787 bytes, written when the user enrols
 * and at no other time. DataStore's value is a transactional key-value store with a Flow
 * per key, and none of that applies to a single blob that is replaced whole. A file is
 * also the shape that makes "delete" obviously complete.
 *
 * ## It is private, and that is the whole of the privacy story
 *
 * `filesDir` is this app's sandbox: no other app can read it without root, and
 * `allowBackup` aside, nothing copies it anywhere. The *audio* never gets this far —
 * enrolment turns it into an embedding and discards it — so even a reader of this file
 * learns a vector, not a recording.
 *
 * ## Versioned, because a model change invalidates every profile
 *
 * [FORMAT_VERSION] covers the file layout *and* the model that produced the vector. A
 * profile from a different embedding model is not merely stale, it is meaningless — the
 * dimensions might still be 192 and the numbers would simply be wrong. A version this
 * does not recognise is deleted and the user is asked to enrol again, which is the only
 * honest recovery.
 */
@Singleton
class FileVoiceProfileStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) : VoiceProfileRepository {

    private val file: File get() = File(context.filesDir, FILE_NAME)

    /** The one copy. Everything reads this; [replace] and [delete] are the only writers. */
    private val cached = MutableStateFlow(load())

    override val profile: Flow<VoiceProfile?> = cached.asStateFlow()

    override suspend fun current(): VoiceProfile? = cached.value

    override suspend fun replace(profile: VoiceProfile) {
        withContext(Dispatchers.IO) {
            runCatching {
                DataOutputStream(file.outputStream().buffered()).use { out ->
                    out.writeInt(FORMAT_VERSION)
                    out.writeInt(profile.enrolledSeconds)
                    out.writeLong(profile.createdAtEpochMillis)
                    out.writeInt(profile.embedding.size)
                    for (value in profile.embedding) out.writeFloat(value)
                }
            }.onFailure { logger.warn(TAG, "Could not write the voice profile: ${it.message}") }
        }
        cached.value = profile
        logger.info(TAG, "Voice profile replaced: ${profile.enrolledSeconds}s of speech")
    }

    override suspend fun delete() {
        withContext(Dispatchers.IO) { runCatching { file.delete() } }
        cached.value = null
        logger.info(TAG, "Voice profile deleted")
    }

    /**
     * Reads the profile at construction.
     *
     * Synchronous, and deliberately: this is a `@Singleton` built by Hilt before anything
     * asks for a profile, the file is under a kilobyte, and the alternative — a Flow that
     * is null for the first few milliseconds — is a gate that briefly believes there is
     * no profile at exactly the moment a call is starting.
     */
    private fun load(): VoiceProfile? {
        val source = file
        if (!source.exists()) return null
        return runCatching {
            DataInputStream(source.inputStream().buffered()).use { input ->
                val version = input.readInt()
                if (version != FORMAT_VERSION) {
                    logger.info(TAG, "Voice profile is version $version, this build reads $FORMAT_VERSION")
                    return@use null
                }
                val seconds = input.readInt()
                val createdAt = input.readLong()
                val size = input.readInt()
                if (size != VoiceProfile.DIMENSIONS) return@use null
                val embedding = FloatArray(size) { input.readFloat() }
                VoiceProfile.of(embedding, seconds, createdAt)
            }
        }.getOrElse {
            logger.warn(TAG, "The voice profile could not be read and is being discarded: ${it.message}")
            runCatching { source.delete() }
            null
        }.also { if (it == null && source.exists()) runCatching { source.delete() } }
    }

    private companion object {
        const val TAG = "VoiceProfile"
        const val FILE_NAME = "voice-profile.bin"

        /**
         * Bump when the layout changes **or when the embedding model does**. A vector
         * from a different model has the right shape and the wrong meaning.
         */
        const val FORMAT_VERSION = 1
    }
}
