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
    )

    @After
    fun tearDown() {
        directory.deleteRecursively()
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
