package com.whatsappv2.data.chat.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.whatsappv2.core.common.logging.NoOpLogger
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.data.account.crypto.CipherError
import com.whatsappv2.data.account.crypto.CredentialCipher
import com.whatsappv2.data.chat.ROBOLECTRIC_SDK
import com.whatsappv2.domain.chat.ChatSession
import com.whatsappv2.domain.chat.CoralServerUrl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The chat identity across a restart, against a real DataStore and a real file.
 *
 * Read back through a **second** store instance throughout, because the claim under test is
 * that a session survives process death — an in-memory fake would pass while proving
 * nothing about storage, which is the same reason `DataStoreAppSettingsRepositoryTest`
 * works this way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class ChatSessionStoreTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val directory = File(context.cacheDir, "chat-session-${System.nanoTime()}")
    private val tokenPath = File(directory, "token.enc")
    private val cipher = BreakableCipher()

    /** One file per class run: Robolectric reuses the application, so a fixed name leaks between cases. */
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create {
        File(directory, "chat.preferences_pb").apply { parentFile?.mkdirs() }
    }

    private fun store() = ChatSessionStore(
        dataStore = dataStore,
        cipher = cipher,
        tokenFile = PrivateChatTokenFile(tokenPath, NoOpLogger),
        logger = NoOpLogger,
    )

    private val session = ChatSession(
        userId = "sample-user",
        displayName = "Sample User",
        token = Secret("a-bearer-token"),
        expiresAtMs = 1_800_000_000_000,
        deviceId = "BF6625949EAA4D5F94CAA18641BE8E74",
        departments = listOf("Ghaziabad"),
    )

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `a read mark is remembered, and never moves backwards`() = runTest {
        val store = store()

        store.markRead("c1", 500, 6)
        assertEquals(mapOf("c1" to (500L to 6)), store.observeReadMarks().first())

        store.markRead("c1", 900, 7)
        assertEquals(mapOf("c1" to (900L to 7)), store.observeReadMarks().first())

        // Scrolling up through history must not un-read the newest message, and neither
        // must an older page that finished loading late.
        store.markRead("c1", 100, 1)
        assertEquals(mapOf("c1" to (900L to 7)), store.observeReadMarks().first())
    }

    @Test
    fun `read marks are per conversation`() = runTest {
        val store = store()

        store.markRead("c1", 500, 2)
        store.markRead("c2", 700, 9)

        assertEquals(mapOf("c1" to (500L to 2), "c2" to (700L to 9)), store.observeReadMarks().first())
    }

    @Test
    fun `signing out forgets read marks, so the next person's badges are their own`() = runTest {
        val store = store()
        assertTrue(store.save(session, CoralServerUrl.DEFAULT))
        store.markRead("c1", 500, 3)

        store.clearSession()

        assertEquals(emptyMap(), store.observeReadMarks().first())
    }

    @Test
    fun `a malformed read mark costs that row its mark, not the whole map`() = runTest {
        val store = store()
        store.markRead("c1", 500, 4)
        // A ULID cannot contain '=' or a newline, so the only way this appears is corruption.
        dataStore.edit {
            it[stringPreferencesKey("chat_read_marks")] = "c1=500,4\nrubbish\nc2=notanumber"
        }

        // The badge comes back for the broken rows. The Chats list does not come down.
        assertEquals(mapOf("c1" to (500L to 4)), store.observeReadMarks().first())
    }

    @Test
    fun `a mark written before the baseline existed reads as a zero baseline`() = runTest {
        val store = store()
        // The old one-field format, as an upgrading device would have on disk.
        dataStore.edit { it[stringPreferencesKey("chat_read_marks")] = "c1=500" }

        // Zero rather than dropping the row: the whole of today's total then shows, which is
        // what an upgrade should do rather than hiding messages that arrived in between.
        assertEquals(mapOf("c1" to (500L to 0)), store.observeReadMarks().first())
    }

    @Test
    fun `a saved session comes back whole from a second store`() = runTest {
        assertTrue(store().save(session, CoralServerUrl.DEFAULT))

        assertEquals(session, store().currentSession())
    }

    @Test
    fun `no session means signed out, not an error`() = runTest {
        assertNull(store().currentSession())
    }

    @Test
    fun `the token is never written in the clear`() = runTest {
        store().save(session, CoralServerUrl.DEFAULT)

        // Two assertions, because there are two files it could leak into and only one of
        // them is the one this design argues about.
        assertFalse(
            "a-bearer-token" in tokenPath.readText(),
            "the token file holds the plaintext token",
        )
        val preferences = File(directory, "chat.preferences_pb").readBytes().decodeToString()
        assertFalse("a-bearer-token" in preferences, "the token reached DataStore in the clear")
        assertFalse(
            "a-bearer-token".reversed() in preferences,
            "the token reached DataStore at all - even encrypted, it does not belong there",
        )
    }

    @Test
    fun `sign-out forgets the session and keeps the origin - decision D2`() = runTest {
        val url = CoralServerUrl.parse("https://host.example:8443").valueOrThrow()
        store().save(session, url)

        store().clearSession()

        assertNull(store().currentSession())
        assertEquals(url, store().currentServerUrl())
        assertFalse(tokenPath.exists(), "the credential outlived sign-out")
    }

    @Test
    fun `the departments survive a restart, so the directory is not empty on relaunch`() = runTest {
        // They arrive ONLY in the login response - the platform has no endpoint that
        // lists them - so a session restored without them means an empty directory until
        // the user signs in again, which looks like an account with no colleagues.
        store().save(session, CoralServerUrl.DEFAULT)

        assertEquals(listOf("Ghaziabad"), assertNotNull(store().currentSession()).departments)
    }

    @Test
    fun `an account in no department round-trips as empty rather than as everything`() = runTest {
        store().save(session.copy(departments = emptyList()), CoralServerUrl.DEFAULT)

        assertEquals(emptyList(), assertNotNull(store().currentSession()).departments)
    }

    @Test
    fun `the origin defaults to the shipped deployment until somebody changes it`() = runTest {
        assertEquals(CoralServerUrl.DEFAULT, store().currentServerUrl())
    }

    @Test
    fun `a URL saved with no session behind it survives, so a failed first sign-in keeps the host`() = runTest {
        val url = CoralServerUrl.parse("https://typed.example").valueOrThrow()

        store().saveServerUrl(url)

        assertEquals(url, store().currentServerUrl())
        assertNull(store().currentSession())
    }

    @Test
    fun `a stored origin that no longer parses falls back rather than stranding the user`() = runTest {
        // What a build whose validation tightened would read. Failing here would leave
        // somebody on a screen they cannot leave, with a value they cannot see.
        dataStore.edit { it[stringPreferencesKey("coral_server_origin")] = "ftp://nope" }

        assertEquals(CoralServerUrl.DEFAULT, store().currentServerUrl())
    }

    @Test
    fun `an unreadable token reads as signed out, not as a broken session`() = runTest {
        store().save(session, CoralServerUrl.DEFAULT)
        cipher.broken = true

        // A Keystore key invalidated by a device restore or a lock-screen change. The
        // credential is genuinely unrecoverable, so a session built around it would be a
        // chat tab that 401s on everything it touches.
        assertNull(store().currentSession())
    }

    @Test
    fun `a session that cannot be encrypted is not half-saved`() = runTest {
        cipher.broken = true

        assertFalse(store().save(session, CoralServerUrl.DEFAULT))

        cipher.broken = false
        assertNull(store().currentSession())
    }

    @Test
    fun `a session with no display name and no expiry round-trips as null, not as blanks`() = runTest {
        val minimal = session.copy(displayName = null, expiresAtMs = null)

        store().save(minimal, CoralServerUrl.DEFAULT)

        val read = assertNotNull(store().currentSession())
        assertNull(read.displayName)
        assertNull(read.expiresAtMs)
    }

    @Test
    fun `a later save replaces an earlier session rather than merging with it`() = runTest {
        store().save(session, CoralServerUrl.DEFAULT)

        store().save(session.copy(userId = "other-user", displayName = null), CoralServerUrl.DEFAULT)

        val read = assertNotNull(store().currentSession())
        assertEquals("other-user", read.userId)
        assertNull(read.displayName, "the previous display name survived a different user's sign-in")
    }

    private fun <T> Outcome<T, *>.valueOrNull(): T? = (this as? Outcome.Success)?.value

    private fun <T> Outcome<T, *>.valueOrThrow(): T =
        valueOrNull() ?: error("expected a success, got $this")
}

/**
 * A cipher that is reversible and inspectable, standing in for the Keystore one.
 *
 * The Android Keystore cannot be exercised on the JVM, which is exactly why
 * `:data:account` put a seam there in the first place. This reverses the string rather than
 * hashing it so a test can assert the plaintext is **absent** from a file and mean it.
 */
private class BreakableCipher : CredentialCipher {

    /** Simulates a Keystore key invalidated by a restore or a lock-screen change. */
    var broken = false

    override fun encrypt(secret: Secret): Outcome<String, CipherError> =
        if (broken) failure(CipherError.KeyInvalidated) else success(secret.reveal().reversed())

    override fun decrypt(ciphertext: String): Outcome<Secret, CipherError> =
        if (broken) failure(CipherError.KeyInvalidated) else success(Secret(ciphertext.reversed()))

    override fun resetKey() = Unit
}
