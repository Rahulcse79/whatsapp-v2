package com.whatsappv2.data.sip.recording.stack

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import com.whatsappv2.data.sip.recording.AllocatedRecording
import com.whatsappv2.data.sip.recording.RecordingStore
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.recording.Recording
import com.whatsappv2.domain.recording.RecordingError
import com.whatsappv2.domain.recording.RecordingId
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recordings on disk, encrypted with a Keystore key (Task 58, §7, DoD 12).
 *
 * In its own `stack` package for the same reason `AndroidKeystoreSecretKeyProvider` and
 * `RealPjsipCoreGateway` are in theirs: the Android Keystore cannot run on the JVM, so
 * keeping this beside the testable recorder would drag that package's coverage gate down
 * until the gate measured nothing.
 *
 * ## The shape of the protection
 *
 * The stack can only write plaintext — PJSIP opens a file and writes a WAV or MKV
 * into it, and there is no hook to encrypt on the way through. So the window exists and is
 * closed rather than denied: the plaintext lives in the app's private `filesDir` for the
 * length of the call, and [seal] rewrites it as AES-GCM ciphertext and **deletes the
 * plaintext before reporting success**. A crash in that window leaves a `.unsealed.wav`
 * file, which [sweepAbandoned] removes on the next start rather than leaving to be found later.
 *
 * The key never leaves the Keystore, so a copy of the file taken off the device — by an
 * `adb` pull on a debug build, by anything that gets at the app's data — is unreadable.
 *
 * ## Excluded from backup
 *
 * `android:allowBackup="false"` covers the app, and `data_extraction_rules.xml` names this
 * directory explicitly so a future decision to allow backup cannot quietly start copying
 * recordings off the device.
 *
 * ## Metadata is the filename
 *
 * Id, call, start and end are encoded into the name. A second store — a database row per
 * recording — would be a copy of the truth that can disagree with what is on disk, and the
 * failure mode of that disagreement is a row pointing at a file nobody deletes.
 */
@Singleton
internal class EncryptedRecordingStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) : RecordingStore {

    private val directory: File
        get() = File(context.filesDir, DIRECTORY).apply { mkdirs() }

    init {
        sweepAbandoned()
    }

    override fun allocate(callId: CallId): Outcome<AllocatedRecording, RecordingError> {
        val id = RecordingId(UUID.randomUUID().toString())
        return try {
            val plaintext = File(directory, "${id.value}$PLAINTEXT_SUFFIX")
            success(AllocatedRecording(id = id, callId = callId, plaintextPath = plaintext.absolutePath))
        } catch (e: SecurityException) {
            failure(RecordingError.StorageUnavailable(e.message.orEmpty()))
        }
    }

    override fun discard(allocated: AllocatedRecording) {
        // The id is safe to log; the path is not (§7, DoD 12).
        if (File(allocated.plaintextPath).delete()) {
            logger.info(TAG, "Discarded the slot for ${allocated.id}; the recording never started")
        }
    }

    override fun seal(
        allocated: AllocatedRecording,
        startedAtEpochMillis: Long,
        endedAtEpochMillis: Long,
    ): Outcome<Recording?, RecordingError> {
        val plaintext = File(allocated.plaintextPath)
        // Nothing was written: a recording stopped within a few milliseconds of starting.
        // Not an error — there is simply no artefact — but the empty file still goes.
        if (!plaintext.exists() || plaintext.length() == 0L) {
            plaintext.delete()
            return success(null)
        }

        val key = when (val loaded = keyOrNull()) {
            null -> {
                // Unreadable is not a reason to keep plaintext lying around. The recording
                // is lost either way; the choice is whether it is lost safely.
                plaintext.delete()
                return failure(RecordingError.StorageUnavailable("the recording key is unavailable"))
            }
            else -> loaded
        }

        val sealed = File(directory, nameOf(allocated, startedAtEpochMillis, endedAtEpochMillis))
        return try {
            encrypt(plaintext, sealed, key)
            // Before success is reported, not after: a plaintext copy beside the encrypted
            // one is the whole protection undone.
            plaintext.delete()
            success(
                Recording(
                    id = allocated.id,
                    callId = allocated.callId,
                    startedAtEpochMillis = startedAtEpochMillis,
                    endedAtEpochMillis = endedAtEpochMillis,
                    sizeBytes = sealed.length(),
                ),
            )
        } catch (e: java.io.IOException) {
            plaintext.delete()
            sealed.delete()
            failure(RecordingError.StorageUnavailable(e.message.orEmpty()))
        } catch (e: java.security.GeneralSecurityException) {
            plaintext.delete()
            sealed.delete()
            failure(RecordingError.StorageUnavailable(e.message.orEmpty()))
        }
    }

    override fun list(): List<Recording> = directory.listFiles()
        .orEmpty()
        .mapNotNull(::toRecording)
        .sortedByDescending { it.startedAtEpochMillis }

    override fun delete(id: RecordingId): Outcome<Unit, RecordingError> {
        directory.listFiles()
            .orEmpty()
            .filter { it.name.startsWith("${id.value}$FIELD_SEPARATOR") }
            .forEach { it.delete() }
        return success(Unit)
    }

    override fun purgeOlderThan(cutoffEpochMillis: Long): Outcome<Int, RecordingError> {
        val removed = list()
            .filter { it.startedAtEpochMillis < cutoffEpochMillis }
            .count { delete(it.id) is Outcome.Success }
        return success(removed)
    }

    /**
     * Removes plaintext files left behind by a crash mid-recording.
     *
     * On construction, so it happens before anything can add to them. An `.unsealed.wav` is
     * an unencrypted recording, and one that survives a restart is one nothing is going to
     * seal — there is no call left to attach it to.
     */
    private fun sweepAbandoned() {
        val abandoned = directory.listFiles().orEmpty().filter { it.name.endsWith(PLAINTEXT_SUFFIX) }
        if (abandoned.isEmpty()) return

        abandoned.forEach { it.delete() }
        logger.warn(TAG, "Removed ${abandoned.size} unencrypted recording(s) left by an unclean stop")
    }

    private fun encrypt(source: File, destination: File, key: SecretKey) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        destination.outputStream().use { raw ->
            // The IV first, in the clear. It is not a secret and it has to be recoverable
            // to decrypt at all; GCM's requirement is that it is never reused with the same
            // key, which a fresh cipher per file satisfies.
            raw.write(cipher.iv)
            CipherOutputStream(raw, cipher).use { encrypted -> source.inputStream().use { it.copyTo(encrypted) } }
        }
    }

    /**
     * Decrypts a sealed recording into [destination].
     *
     * Unused by the app itself today — there is no playback screen yet — and present
     * because a store that can only write is a store nobody can prove encrypts anything.
     * Its round trip is what an instrumented test asserts.
     */
    fun decrypt(id: RecordingId, destination: File): Outcome<Unit, RecordingError> {
        val sealed = directory.listFiles()
            .orEmpty()
            .firstOrNull { it.name.startsWith("${id.value}$FIELD_SEPARATOR") }
            ?: return failure(RecordingError.StorageUnavailable("no such recording"))
        val key = keyOrNull()
            ?: return failure(RecordingError.StorageUnavailable("the recording key is unavailable"))

        return try {
            sealed.inputStream().use { raw -> raw.decryptInto(destination, key) }
            success(Unit)
        } catch (e: IOException) {
            failure(RecordingError.StorageUnavailable(e.message.orEmpty()))
        } catch (e: GeneralSecurityException) {
            failure(RecordingError.StorageUnavailable(e.message.orEmpty()))
        }
    }

    /**
     * Reads the IV off the front and copies the rest out, decrypted.
     *
     * Its own function because the streams nest three deep and the reader of [decrypt]
     * should see what it does — find the file, find the key, copy it out — rather than
     * how a GCM stream is assembled.
     */
    private fun InputStream.decryptInto(destination: File, key: SecretKey) {
        // Written by `seal` as the first GCM_IV_BYTES of the file, before the ciphertext.
        val iv = ByteArray(GCM_IV_BYTES).also { read(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        CipherInputStream(this, cipher).use { plain ->
            destination.outputStream().use { plain.copyTo(it) }
        }
    }

    private fun nameOf(allocated: AllocatedRecording, startedAt: Long, endedAt: Long): String {
        // The call id is base64'd rather than written as-is: it comes from the stack for an
        // inbound call and this must not be the place a remote value chooses a filename.
        val call = Base64.encodeToString(
            allocated.callId.value.toByteArray(),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        return listOf(allocated.id.value, call, startedAt, endedAt)
            .joinToString(FIELD_SEPARATOR) + SEALED_SUFFIX
    }

    /**
     * A sealed file as a [Recording], or null if the name is not one this store wrote.
     *
     * Every unreadable field is the same answer — not one of ours — so they are gathered
     * into one exit rather than each taking its own. A directory can hold anything, and a
     * file that does not parse is a file to walk past, not an error.
     */
    private fun toRecording(file: File): Recording? {
        val fields = file.name
            .takeIf { it.endsWith(SEALED_SUFFIX) }
            ?.removeSuffix(SEALED_SUFFIX)
            ?.split(FIELD_SEPARATOR)
            ?.takeIf { it.size == FIELD_COUNT }
            ?: return null

        val started = fields[FIELD_STARTED].toLongOrNull()
        val ended = fields[FIELD_ENDED].toLongOrNull()
        val callId = decodedCallId(fields[FIELD_CALL])

        return if (started == null || ended == null || callId == null) {
            null
        } else {
            Recording(
                id = RecordingId(fields[FIELD_ID]),
                callId = CallId(callId),
                startedAtEpochMillis = started,
                endedAtEpochMillis = ended,
                sizeBytes = file.length(),
            )
        }
    }

    /** The call id `nameOf` base64'd, or null if the field is not decodable. */
    private fun decodedCallId(field: String): String? = runCatching {
        String(Base64.decode(field, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
    }.getOrNull()

    /**
     * The recording key, created on first use.
     *
     * Null rather than an exception when the Keystore is unusable — a restored device
     * invalidates keys, and that is an expected state on a phone rather than a crash.
     */
    private fun keyOrNull(): SecretKey? = try {
        val keystore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keystore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generateKey()
    } catch (e: java.security.GeneralSecurityException) {
        logger.error(TAG, "The recording key is unavailable: ${e.javaClass.simpleName}")
        null
    } catch (e: java.io.IOException) {
        logger.error(TAG, "The recording key is unavailable: ${e.javaClass.simpleName}")
        null
    }

    private fun generateKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        .apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    // Deliberately not `setUserAuthenticationRequired`: a recording has to
                    // be sealed the moment a call ends, and a locked screen at that moment
                    // is the normal case rather than the exception.
                    .build(),
            )
        }
        .generateKey()

    private companion object {
        const val TAG = "RecordingStore"

        /** Named in `data_extraction_rules.xml` so backup cannot pick it up (§7). */
        const val DIRECTORY = "recordings"

        const val PLAINTEXT_SUFFIX = RecordingFileNames.PLAINTEXT_SUFFIX
        const val SEALED_SUFFIX = ".rec"
        const val FIELD_SEPARATOR = "__"
        const val FIELD_COUNT = 4
        const val FIELD_ID = 0
        const val FIELD_CALL = 1
        const val FIELD_STARTED = 2
        const val FIELD_ENDED = 3

        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "whatsappv2.recordings"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
