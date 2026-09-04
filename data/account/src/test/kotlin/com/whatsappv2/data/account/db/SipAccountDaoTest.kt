package com.whatsappv2.data.account.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.data.account.crypto.AesGcmCredentialCipher
import com.whatsappv2.data.account.crypto.InMemorySecretKeyProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class SipAccountDaoTest {

    private lateinit var database: SipAccountDatabase
    private lateinit var dao: SipAccountDao

    private val cipher = AesGcmCredentialCipher(InMemorySecretKeyProvider())

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SipAccountDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.sipAccountDao()
    }

    @After
    fun tearDown() = database.close()

    private fun entity(
        id: String = "acct-1",
        username: String = "alice",
        domain: String = "sip.example.com",
        password: String = "hunter22",
        isDefault: Boolean = false,
        createdAt: Long = 1_000L,
    ) = SipAccountEntity(
        id = id,
        label = "Work",
        username = username,
        extension = null,
        authUsername = null,
        passwordCiphertext = checkNotNull(cipher.encrypt(Secret(password)).getOrNull()),
        displayName = null,
        domain = domain,
        registrar = null,
        outboundProxy = null,
        port = null,
        transport = "UDP",
        registrationExpirySeconds = 3_600,
        stunServer = null,
        turnServer = null,
        turnUsername = null,
        turnPasswordCiphertext = null,
        iceEnabled = true,
        stunEnabled = true,
        keepaliveIntervalSeconds = 30,
        srtpPolicy = "OPTIONAL",
        audioCodecs = "OPUS,PCMU",
        videoCodecs = "VP8",
        isDefault = isDefault,
        createdAtEpochMillis = createdAt,
    )

    private suspend fun allAccounts(): List<SipAccountEntity> {
        var result: List<SipAccountEntity> = emptyList()
        dao.observeAll().test {
            result = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        return result
    }

    // ---------------------------------------------------------------- CRUD

    @Test
    fun `insert then read back returns every field`() = runTest {
        val account = entity()
        dao.insert(account)
        assertEquals(account, dao.findById("acct-1"))
    }

    @Test
    fun `update replaces the stored row`() = runTest {
        dao.insert(entity())
        dao.update(entity().copy(label = "Home", registrationExpirySeconds = 600))

        val stored = assertNotNull(dao.findById("acct-1"))
        assertEquals("Home", stored.label)
        assertEquals(600, stored.registrationExpirySeconds)
    }

    @Test
    fun `delete removes the row`() = runTest {
        dao.insert(entity())
        dao.delete(entity())
        assertNull(dao.findById("acct-1"))
        assertEquals(0, dao.count())
    }

    @Test
    fun `observeAll emits on every change`() = runTest {
        dao.observeAll().test {
            assertTrue(awaitItem().isEmpty())

            dao.insert(entity())
            assertEquals(1, awaitItem().size)

            dao.insert(entity(id = "acct-2", username = "bob", createdAt = 2_000L))
            assertEquals(listOf("acct-1", "acct-2"), awaitItem().map { it.id })

            dao.deleteById("acct-1")
            assertEquals(listOf("acct-2"), awaitItem().map { it.id })

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeById emits null once the account is gone`() = runTest {
        dao.insert(entity())
        dao.observeById("acct-1").test {
            assertNotNull(awaitItem())
            dao.deleteById("acct-1")
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `accounts are ordered by creation, not by id`() = runTest {
        dao.insert(entity(id = "zzz", username = "zoe", createdAt = 1_000L))
        dao.insert(entity(id = "aaa", username = "amy", createdAt = 2_000L))
        assertEquals(listOf("zzz", "aaa"), allAccounts().map { it.id })
    }

    // ---------------------------------------------------------------- identity

    @Test
    fun `two accounts cannot share a SIP identity`() = runTest {
        // They would fight over the same registrar binding (Task 22).
        dao.insert(entity(id = "acct-1", username = "alice", domain = "sip.example.com"))
        assertFailsWith<SQLiteConstraintException> {
            dao.insert(entity(id = "acct-2", username = "alice", domain = "sip.example.com"))
        }
    }

    @Test
    fun `the same username on a different domain is a different account`() = runTest {
        dao.insert(entity(id = "acct-1", username = "alice", domain = "sip.example.com"))
        dao.insert(entity(id = "acct-2", username = "alice", domain = "sip.other.com"))
        assertEquals(2, dao.count())
    }

    @Test
    fun `findByIdentity locates an existing account`() = runTest {
        dao.insert(entity())
        assertEquals("acct-1", dao.findByIdentity("alice", "sip.example.com")?.id)
        assertNull(dao.findByIdentity("alice", "elsewhere.com"))
    }

    // ---------------------------------------------------------------- default account

    @Test
    fun `setDefault leaves exactly one default`() = runTest {
        dao.insert(entity(id = "acct-1", username = "alice", isDefault = true))
        dao.insert(entity(id = "acct-2", username = "bob", createdAt = 2_000L))

        dao.setDefault("acct-2")

        assertEquals(listOf("acct-2"), allAccounts().filter { it.isDefault }.map { it.id })
    }

    @Test
    fun `deleting the default promotes another account`() = runTest {
        // An app with accounts but no default cannot place a call.
        dao.insert(entity(id = "acct-1", username = "alice", isDefault = true))
        dao.insert(entity(id = "acct-2", username = "bob", createdAt = 2_000L))

        dao.deleteAndPromoteDefault("acct-1")

        val remaining = allAccounts()
        assertEquals(listOf("acct-2"), remaining.map { it.id })
        assertTrue(remaining.single().isDefault, "the surviving account must become default")
    }

    @Test
    fun `deleting a non-default account promotes nobody`() = runTest {
        dao.insert(entity(id = "acct-1", username = "alice", isDefault = true))
        dao.insert(entity(id = "acct-2", username = "bob", createdAt = 2_000L))

        dao.deleteAndPromoteDefault("acct-2")

        assertEquals(listOf("acct-1"), allAccounts().map { it.id })
    }

    @Test
    fun `deleting the last account leaves no default to promote`() = runTest {
        dao.insert(entity(isDefault = true))
        dao.deleteAndPromoteDefault("acct-1")
        assertEquals(0, dao.count())
    }

    @Test
    fun `observeDefault tracks the default account`() = runTest {
        dao.insert(entity(id = "acct-1", username = "alice", isDefault = true))
        dao.insert(entity(id = "acct-2", username = "bob", createdAt = 2_000L))

        dao.observeDefault().test {
            assertEquals("acct-1", awaitItem()?.id)
            dao.setDefault("acct-2")
            assertEquals("acct-2", awaitItem()?.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------------------------------------------------------------- secrecy

    @Test
    fun `a raw dump of the database contains no plaintext password`() = runTest {
        // Task 17 done-when #3, and the reason the column holds ciphertext rather than a
        // password: anyone who can read the file must not be able to read the credential.
        dao.insert(entity(password = "sup3r-secret-pw"))

        val dump = buildString {
            database.openHelper.readableDatabase
                .query("SELECT * FROM sip_accounts")
                .use { cursor ->
                    while (cursor.moveToNext()) {
                        for (column in 0 until cursor.columnCount) {
                            append(cursor.getColumnName(column)).append('=')
                            append(runCatching { cursor.getString(column) }.getOrNull())
                            append(' ')
                        }
                    }
                }
        }

        assertTrue(dump.isNotEmpty(), "the dump must contain the row, or it proves nothing")
        assertFalse("sup3r-secret-pw" in dump, "the password appeared in the database")
        assertTrue("password_ciphertext=" in dump)
    }
}
