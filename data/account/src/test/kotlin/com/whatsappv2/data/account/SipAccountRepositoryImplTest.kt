package com.whatsappv2.data.account

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.whatsappv2.core.common.logging.NoOpLogger
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.errorOrNull
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.core.common.time.MutableClock
import com.whatsappv2.data.account.crypto.AesGcmCredentialCipher
import com.whatsappv2.data.account.crypto.CipherError
import com.whatsappv2.data.account.crypto.InMemorySecretKeyProvider
import com.whatsappv2.data.account.db.ROBOLECTRIC_SDK
import com.whatsappv2.data.account.db.SipAccountDatabase
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.HostPort
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.model.TurnConfiguration
import com.whatsappv2.domain.repository.AccountRepositoryError
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class SipAccountRepositoryImplTest {

    private lateinit var database: SipAccountDatabase
    private lateinit var repository: SipAccountRepositoryImpl

    private val keys = InMemorySecretKeyProvider()
    private val cipher = AesGcmCredentialCipher(keys)
    private val clock = MutableClock()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SipAccountDatabase::class.java,
        ).allowMainThreadQueries().build()

        repository = SipAccountRepositoryImpl(
            dao = database.sipAccountDao(),
            cipher = cipher,
            clock = clock,
            logger = NoOpLogger,
        )
    }

    @After
    fun tearDown() = database.close()

    private fun account(
        id: String = "acct-1",
        username: String = "alice",
        domain: String = "sip.example.com",
        password: String = "hunter22",
        isDefault: Boolean = false,
        transport: Transport = Transport.UDP,
        turn: TurnConfiguration? = null,
    ) = SipAccount(
        id = AccountId(id),
        label = "Work",
        username = username,
        extension = null,
        authUsername = null,
        password = Secret(password),
        displayName = null,
        domain = domain,
        registrar = null,
        outboundProxy = null,
        port = null,
        transport = transport,
        registrationExpirySeconds = 3_600,
        stunServer = null,
        turn = turn,
        natPolicy = NatPolicy.DEFAULT,
        srtpPolicy = SrtpPolicy.OPTIONAL,
        codecs = CodecPreferences.DEFAULT,
        isDefault = isDefault,
    )

    private suspend fun accounts(): List<SipAccount> {
        var result: List<SipAccount> = emptyList()
        repository.observeAccounts().test {
            result = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        return result
    }

    // ---------------------------------------------------------------- save and read

    @Test
    fun `a saved account can be read back with its fields intact`() = runTest {
        assertIs<Outcome.Success<Unit>>(repository.save(account()))

        val stored = assertNotNull(repository.findById(AccountId("acct-1")))
        assertEquals("Work", stored.label)
        assertEquals("alice", stored.username)
        assertEquals("sip.example.com", stored.domain)
        assertEquals(Transport.UDP, stored.transport)
        assertEquals(CodecPreferences.DEFAULT.audio, stored.codecs.audio)
    }

    @Test
    fun `saving twice updates rather than duplicating`() = runTest {
        repository.save(account())
        repository.save(account().copy(label = "Home"))

        assertEquals(1, repository.count())
        assertEquals("Home", repository.findById(AccountId("acct-1"))?.label)
    }

    @Test
    fun `editing preserves creation order`() = runTest {
        // Otherwise the list jumps around whenever an account is edited.
        clock.set(1_000L)
        repository.save(account(id = "acct-1", username = "alice"))
        clock.set(2_000L)
        repository.save(account(id = "acct-2", username = "bob"))

        clock.set(9_000L)
        repository.save(account(id = "acct-1", username = "alice").copy(label = "Edited"))

        assertEquals(listOf("acct-1", "acct-2"), accounts().map { it.id.value })
    }

    @Test
    fun `observeAccounts emits on every change`() = runTest {
        repository.observeAccounts().test {
            assertTrue(awaitItem().isEmpty())
            repository.save(account())
            assertEquals(1, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------------------------------------------------------------- credentials

    @Test
    fun `an observed account never carries a decrypted password`() = runTest {
        // Task 18 done-when #2. Accounts are held by ViewModels across configuration
        // changes, so a password on one would sit decrypted for the life of a screen.
        repository.save(account(password = "hunter22"))

        for (observed in accounts()) {
            assertEquals(0, observed.password.length, "an observed account carried a password")
        }
        assertEquals(0, assertNotNull(repository.findById(AccountId("acct-1"))).password.length)
    }

    @Test
    fun `credentialsFor returns the real password on demand`() = runTest {
        repository.save(account(password = "hunter22"))

        val credentials = assertNotNull(repository.credentialsFor(AccountId("acct-1")).getOrNull())
        assertEquals("hunter22", credentials.password.reveal())
        assertNull(credentials.turnPassword)
    }

    @Test
    fun `TURN credentials round-trip separately from the SIP password`() = runTest {
        val server = assertNotNull(HostPort.parse("turn.example.com:3478").getOrNull())
        repository.save(
            account(
                password = "sip-pw",
                turn = TurnConfiguration(server, "relay-user", Secret("turn-pw")),
            ),
        )

        val credentials = assertNotNull(repository.credentialsFor(AccountId("acct-1")).getOrNull())
        assertEquals("sip-pw", credentials.password.reveal())
        assertEquals("turn-pw", credentials.turnPassword?.reveal())

        // The observed account keeps the TURN server and username but not the secret.
        val observed = assertNotNull(repository.findById(AccountId("acct-1")))
        assertEquals("relay-user", observed.turn?.username)
        assertEquals(0, observed.turn?.password?.length)
    }

    @Test
    fun `a lost key surfaces as a prompt to re-enter, not a crash`() = runTest {
        repository.save(account(password = "hunter22"))
        keys.rotateKey()

        val error = repository.credentialsFor(AccountId("acct-1")).errorOrNull()
        assertEquals(AccountRepositoryError.CredentialsUnrecoverable, error)
        assertTrue(assertNotNull(error).requiresCredentialReEntry)
    }

    @Test
    fun `a Keystore failure is not offered as re-entry`() = runTest {
        // Retyping the password would not help: the new value could not be stored either.
        keys.failure = CipherError.KeyUnavailable("NoSuchProviderException")

        val error = repository.save(account()).errorOrNull()
        assertIs<AccountRepositoryError.CryptoFailure>(error)
        assertTrue(!error.requiresCredentialReEntry)
    }

    @Test
    fun `credentials for an unknown account report NotFound`() = runTest {
        assertEquals(
            AccountRepositoryError.NotFound,
            repository.credentialsFor(AccountId("missing")).errorOrNull(),
        )
    }

    // ---------------------------------------------------------------- identity

    @Test
    fun `a second account cannot take an existing SIP identity`() = runTest {
        repository.save(account(id = "acct-1", username = "alice", domain = "sip.example.com"))

        val error = repository.save(
            account(id = "acct-2", username = "alice", domain = "sip.example.com"),
        ).errorOrNull()

        assertEquals(
            AccountRepositoryError.DuplicateIdentity("alice", "sip.example.com"),
            error,
        )
        assertEquals(1, repository.count())
    }

    @Test
    fun `an account may keep its own identity when edited`() = runTest {
        repository.save(account(id = "acct-1", username = "alice"))
        assertIs<Outcome.Success<Unit>>(
            repository.save(account(id = "acct-1", username = "alice").copy(label = "Renamed")),
        )
    }

    // ---------------------------------------------------------------- default account

    @Test
    fun `the first account saved becomes the default`() = runTest {
        // An app holding accounts but no default cannot place a call.
        repository.save(account(id = "acct-1", username = "alice", isDefault = false))
        assertEquals("acct-1", assertNotNull(defaultAccount()).id.value)
    }

    @Test
    fun `setDefault moves the default and leaves only one`() = runTest {
        repository.save(account(id = "acct-1", username = "alice"))
        repository.save(account(id = "acct-2", username = "bob"))

        assertIs<Outcome.Success<Unit>>(repository.setDefault(AccountId("acct-2")))

        assertEquals(listOf("acct-2"), accounts().filter { it.isDefault }.map { it.id.value })
    }

    @Test
    fun `deleting the default promotes another account`() = runTest {
        repository.save(account(id = "acct-1", username = "alice"))
        repository.save(account(id = "acct-2", username = "bob"))

        assertIs<Outcome.Success<Unit>>(repository.delete(AccountId("acct-1")))

        val remaining = accounts()
        assertEquals(listOf("acct-2"), remaining.map { it.id.value })
        assertTrue(remaining.single().isDefault)
    }

    @Test
    fun `deleting or defaulting an unknown account reports NotFound`() = runTest {
        assertEquals(AccountRepositoryError.NotFound, repository.delete(AccountId("nope")).errorOrNull())
        assertEquals(AccountRepositoryError.NotFound, repository.setDefault(AccountId("nope")).errorOrNull())
    }

    @Test
    fun `observeDefaultAccount tracks the change`() = runTest {
        repository.save(account(id = "acct-1", username = "alice"))
        repository.save(account(id = "acct-2", username = "bob"))

        repository.observeDefaultAccount().test {
            assertEquals("acct-1", awaitItem()?.id?.value)
            repository.setDefault(AccountId("acct-2"))
            assertEquals("acct-2", awaitItem()?.id?.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeAccount emits null once deleted`() = runTest {
        repository.save(account())
        repository.observeAccount(AccountId("acct-1")).test {
            assertNotNull(awaitItem())
            repository.delete(AccountId("acct-1"))
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private suspend fun defaultAccount(): SipAccount? {
        var result: SipAccount? = null
        repository.observeDefaultAccount().test {
            result = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        return result
    }
}
