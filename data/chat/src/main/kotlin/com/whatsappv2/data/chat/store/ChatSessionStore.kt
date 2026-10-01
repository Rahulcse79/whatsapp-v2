package com.whatsappv2.data.chat.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.data.account.crypto.CredentialCipher
import com.whatsappv2.data.chat.di.ChatSessionPreferences
import com.whatsappv2.domain.chat.ChatExtension
import com.whatsappv2.domain.chat.ChatSession
import com.whatsappv2.domain.chat.CoralServerUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a chat identity lives between launches.
 *
 * ## Three storage decisions, each for a stated reason
 *
 * | Value | Where | Why |
 * |---|---|---|
 * | Origin | This module's own DataStore | A deployment fact, not a secret. Survives sign-out (D2). |
 * | `userId`, `displayName`, `deviceId`, expiry | The same DataStore | Not sensitive. |
 * | **Token** | **A separate file, AES-GCM encrypted** | A credential: whoever holds it is the user. |
 *
 * The token is not in DataStore at all, encrypted or otherwise. `DataStoreAppSettingsRepository`'s
 * own KDoc says that store is for nothing sensitive, and that rule is not bent by pointing
 * out that the value would have been ciphertext — the next person to add a field would read
 * the precedent, not the caveat.
 *
 * ## Its own DataStore, not `:data:settings`'
 *
 * A second file, qualified by [com.whatsappv2.data.chat.di.ChatSessionPreferences]. Sharing
 * one would let a sign-out clear a user's theme, and would put chat identity in a file whose
 * stated contract is "app preferences".
 *
 * ## Sign-out clears the credential first
 *
 * [clearSession] deletes the token file **before** clearing the identity, so a process death
 * in between leaves an identity with no token — which [observeSession] reads as signed out.
 * The other order would leave a usable credential with no visible session, which is the one
 * outcome worth designing against.
 */
@Singleton
internal class ChatSessionStore @Inject constructor(
    @ChatSessionPreferences private val dataStore: DataStore<Preferences>,
    private val cipher: CredentialCipher,
    private val tokenFile: ChatTokenFile,
    private val logger: Logger,
) {

    /**
     * The stored session, or null when nobody is signed in.
     *
     * Null is also what an unreadable token produces — a Keystore key invalidated by a
     * device restore or a lock-screen change. That is signed out, and it is the correct
     * answer: the credential is genuinely unrecoverable, so presenting a session built
     * around it would offer the user a chat tab that 401s on everything it touches.
     */
    fun observeSession(): Flow<ChatSession?> = preferences().map { it.toSession() }

    suspend fun currentSession(): ChatSession? = observeSession().first()

    fun observeServerUrl(): Flow<CoralServerUrl> = preferences().map { it.toServerUrl() }

    suspend fun currentServerUrl(): CoralServerUrl = observeServerUrl().first()

    /**
     * Persists [session] and the [url] it was obtained from.
     *
     * The token is written first. A failure to encrypt must not leave an identity claiming
     * a session this device cannot use, so it returns false and writes nothing else.
     */
    suspend fun save(session: ChatSession, url: CoralServerUrl): Boolean {
        val ciphertext = cipher.encrypt(session.token).getOrNull()
        if (ciphertext == null) {
            // No token value in the message, and none in the exception either — the
            // cipher's own errors are typed and carry no plaintext.
            logger.error(TAG, "Could not encrypt the chat token; the session was not saved")
            return false
        }
        if (!tokenFile.write(ciphertext)) return false

        dataStore.edit { preferences ->
            preferences[ORIGIN] = url.origin
            preferences[USER_ID] = session.userId
            preferences[DEVICE_ID] = session.deviceId
            session.displayName?.let { preferences[DISPLAY_NAME] = it } ?: preferences.remove(DISPLAY_NAME)
            session.expiresAtMs?.let { preferences[EXPIRES_AT] = it } ?: preferences.remove(EXPIRES_AT)
            // Everything but the SIP password, which is a credential and is needed only
            // at sign-in. See ChatExtension: it is null on a session read back from here.
            //
            // An if/else rather than `?: run {}`: the elvis took its value from
            // `remove()`, which returns the old entry, and unboxing a null one threw.
            val extension = session.extension
            if (extension == null) {
                preferences.remove(EXTENSION)
                preferences.remove(EXTENSION_NAME)
                preferences.remove(EXTENSION_DOMAIN)
                preferences.remove(EXTENSION_PORT)
                preferences.remove(EXTENSION_SECURE)
            } else {
                preferences[EXTENSION] = extension.number
                preferences[EXTENSION_DOMAIN] = extension.domain
                preferences[EXTENSION_PORT] = extension.port
                preferences[EXTENSION_SECURE] = extension.secure
                val name = extension.name
                if (name == null) preferences.remove(EXTENSION_NAME) else preferences[EXTENSION_NAME] = name
            }
            // A Set, because DataStore has no list type and the order of departments
            // carries no meaning - they are a filter, not a sequence.
            preferences[DEPARTMENTS] = session.departments.toSet()
        }
        return true
    }

    /**
     * Forgets the identity and the token. **Keeps the origin** — decision D2.
     *
     * Pins go with the identity. They are this person's five conversations, and leaving
     * them would show the next person who signs in a pinned section built by somebody
     * else — or, worse, five ids that resolve to nothing.
     */
    suspend fun clearSession() {
        tokenFile.delete()
        dataStore.edit { preferences ->
            preferences.remove(USER_ID)
            preferences.remove(DISPLAY_NAME)
            preferences.remove(DEVICE_ID)
            preferences.remove(EXPIRES_AT)
            preferences.remove(EXTENSION)
            preferences.remove(EXTENSION_NAME)
            preferences.remove(EXTENSION_DOMAIN)
            preferences.remove(EXTENSION_PORT)
            preferences.remove(EXTENSION_SECURE)
            preferences.remove(DEPARTMENTS)
            preferences.remove(PINNED)
        }
    }

    /** The pinned conversation ids, newest first. */
    fun observePinned(): Flow<List<String>> = preferences().map { it.toPinned() }

    /**
     * Adds [id] to the front of the pinned list, or returns false when it is full.
     *
     * The read and the write are one `edit`, which is the whole reason this lives here:
     * counting in a caller and writing afterwards races a second pin and ends with six.
     */
    suspend fun pin(id: String, max: Int): Boolean {
        var pinned = true
        dataStore.edit { preferences ->
            val current = preferences.toPinned()
            when {
                id in current -> Unit
                current.size >= max -> pinned = false
                else -> preferences[PINNED] = (listOf(id) + current).joinToString(PIN_SEPARATOR)
            }
        }
        return pinned
    }

    suspend fun unpin(id: String) {
        dataStore.edit { preferences ->
            val remaining = preferences.toPinned() - id
            if (remaining.isEmpty()) {
                preferences.remove(PINNED)
            } else {
                preferences[PINNED] = remaining.joinToString(PIN_SEPARATOR)
            }
        }
    }

    /** Remembers an origin with no session behind it, so a failed first sign-in keeps the host typed. */
    suspend fun saveServerUrl(url: CoralServerUrl) {
        dataStore.edit { it[ORIGIN] = url.origin }
    }

    private fun preferences(): Flow<Preferences> = dataStore.data.catch { error ->
        // A corrupt preferences file must not take the chat tab down, let alone the app.
        // Falling back to empty means "signed out", which is recoverable by signing in.
        if (error is IOException) {
            logger.error(TAG, "Chat session store unreadable; treating as signed out")
            emit(emptyPreferences())
        } else {
            throw error
        }
    }

    private fun Preferences.toSession(): ChatSession? {
        val userId = this[USER_ID] ?: return null
        val deviceId = this[DEVICE_ID] ?: return null
        val ciphertext = tokenFile.read() ?: return null
        val token = cipher.decrypt(ciphertext).getOrNull() ?: return null

        return ChatSession(
            userId = userId,
            displayName = this[DISPLAY_NAME],
            token = token,
            expiresAtMs = this[EXPIRES_AT],
            deviceId = deviceId,
            extension = toExtension(),
            departments = this[DEPARTMENTS].orEmpty().sorted(),
        )
    }

    /**
     * The stored extension, without its password — see [ChatExtension].
     *
     * A stored number with no domain is not half an extension, it is a store written by an
     * older build: both are required, so it reads as absent rather than as broken.
     */
    private fun Preferences.toExtension(): ChatExtension? {
        val number = this[EXTENSION] ?: return null
        val domain = this[EXTENSION_DOMAIN] ?: return null

        return ChatExtension(
            number = number,
            name = this[EXTENSION_NAME],
            sipPassword = null,
            domain = domain,
            port = this[EXTENSION_PORT] ?: 0,
            secure = this[EXTENSION_SECURE] ?: false,
        )
    }

    /**
     * One string rather than a `stringSet`, because the order IS the data here — unlike
     * [DEPARTMENTS], where it carries no meaning. A ULID contains no separator.
     */
    private fun Preferences.toPinned(): List<String> =
        this[PINNED]?.split(PIN_SEPARATOR).orEmpty().filter { it.isNotBlank() }

    /**
     * The stored origin, or the shipped default.
     *
     * A stored value that no longer parses falls back rather than failing: a build whose
     * validation rules tightened must not strand somebody on a screen they cannot leave.
     */
    private fun Preferences.toServerUrl(): CoralServerUrl =
        this[ORIGIN]?.let { CoralServerUrl.parse(it).getOrNull() } ?: CoralServerUrl.DEFAULT

    private companion object {
        const val TAG = "ChatSessionStore"

        val ORIGIN = stringPreferencesKey("coral_server_origin")
        val USER_ID = stringPreferencesKey("chat_user_id")
        val DISPLAY_NAME = stringPreferencesKey("chat_display_name")
        val DEVICE_ID = stringPreferencesKey("chat_device_id")
        val EXPIRES_AT = longPreferencesKey("chat_token_expires_at")
        val EXTENSION = stringPreferencesKey("chat_extension")
        val EXTENSION_NAME = stringPreferencesKey("chat_extension_name")
        val EXTENSION_DOMAIN = stringPreferencesKey("chat_extension_domain")
        val EXTENSION_PORT = intPreferencesKey("chat_extension_port")
        val EXTENSION_SECURE = booleanPreferencesKey("chat_extension_secure")
        val DEPARTMENTS = stringSetPreferencesKey("chat_departments")
        val PINNED = stringPreferencesKey("chat_pinned_conversations")

        /** Not a character a ULID can contain, so no id can split itself in two. */
        const val PIN_SEPARATOR = "\n"
    }
}

/**
 * The one file the encrypted token lives in.
 *
 * Its own class so [ChatSessionStore] can be tested without a filesystem and so the
 * "credential goes here, and nowhere else" decision has a name somebody can grep for.
 *
 * Writes are atomic: a temporary file then a rename, because a half-written ciphertext is
 * a token that decrypts to a GCM authentication failure and reads as tampering.
 */
internal interface ChatTokenFile {
    fun read(): String?
    fun write(ciphertext: String): Boolean
    fun delete()
}

/** [ChatTokenFile] over a file in the app's private storage. */
internal class PrivateChatTokenFile(
    private val file: File,
    private val logger: Logger,
) : ChatTokenFile {

    override fun read(): String? = try {
        if (file.isFile) file.readText().trim().ifEmpty { null } else null
    } catch (e: IOException) {
        logger.warn(TAG, "Could not read the chat token file", e)
        null
    }

    override fun write(ciphertext: String): Boolean = try {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(ciphertext)
        check(temporary.renameTo(file)) { "rename failed" }
        true
    } catch (e: IOException) {
        logger.error(TAG, "Could not write the chat token file", e)
        false
    } catch (e: IllegalStateException) {
        logger.error(TAG, "Could not replace the chat token file", e)
        false
    }

    override fun delete() {
        // Result ignored deliberately: "already absent" and "deleted" are the same outcome
        // for a caller, and both are the state sign-out is trying to reach.
        file.delete()
    }

    private companion object {
        const val TAG = "ChatTokenFile"
    }
}
