package com.whatsappv2.data.sip

import app.cash.turbine.test
import com.whatsappv2.core.common.logging.NoOpLogger
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.errorOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.data.sip.registration.FakeLinphoneCoreGateway
import com.whatsappv2.data.sip.registration.LinphoneCoreGateway
import com.whatsappv2.data.sip.registration.RegistrationStateMapper
import com.whatsappv2.data.sip.registration.StackRegistrationEvent
import com.whatsappv2.data.sip.registration.StackRegistrationState
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.RegistrationFailure
import com.whatsappv2.domain.model.RegistrationState
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.repository.SipAccountRepository
import com.whatsappv2.domain.testing.FakeSipAccountRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The callback-to-Flow mapping, exercised on the JVM.
 *
 * Task 27's done-when asks for exactly this, and it is only possible because the SDK sits
 * behind [com.whatsappv2.data.sip.registration.LinphoneCoreGateway]: liblinphone cannot
 * run here, so without that seam none of this could be tested before a device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinphoneSipEngineTest {

    private val gateway = FakeLinphoneCoreGateway()
    private val repository = FakeSipAccountRepository()

    private val account = SipAccount(
        id = AccountId("acct-1"),
        label = "Work",
        username = "alice",
        extension = null,
        authUsername = null,
        password = Secret("hunter22"),
        displayName = null,
        domain = "sip.example.com",
        registrar = null,
        outboundProxy = null,
        port = null,
        transport = Transport.TLS,
        registrationExpirySeconds = 600,
        stunServer = null,
        turn = null,
        natPolicy = NatPolicy.DEFAULT,
        srtpPolicy = SrtpPolicy.OPTIONAL,
        codecs = CodecPreferences.DEFAULT,
        isDefault = true,
    )

    private fun engine(scope: TestScope) =
        LinphoneSipEngine(gateway, repository, scope, NoOpLogger).also { repository.given(account) }

    // ---------------------------------------------------------------- lifecycle

    @Test
    fun `nothing works before the engine is started`() = runTest {
        // Constructing the engine must not start a native stack as a side effect of
        // dependency injection, before the app has decided it needs one.
        val engine = engine(this)
        assertEquals(SipError.EngineUnavailable, engine.register(account).errorOrNull())
        assertEquals(0, gateway.startCount)
    }

    @Test
    fun `starting twice does not create a second stack`() = runTest {
        val engine = engine(this)
        engine.start()
        engine.start()
        assertEquals(1, gateway.startCount)
        engine.stop()
    }

    // ---------------------------------------------------------------- registration

    @Test
    fun `registering reports Registering immediately, before the stack answers`() = runTest {
        // The UI must show something is happening the moment the user presses save.
        val engine = engine(this)
        engine.start()

        engine.register(account)

        assertEquals(RegistrationState.Registering, engine.registrationState.value[account.id])
        engine.stop()
    }

    @Test
    fun `the stack's Ok event becomes Registered`() = runTest(StandardTestDispatcher()) {
        val engine = engine(this)
        engine.start()
        engine.register(account)
        advanceUntilIdle()

        gateway.emit(account.id.value, StackRegistrationState.OK)
        advanceUntilIdle()

        assertEquals(
            RegistrationState.Registered(600),
            engine.registrationState.value[account.id],
        )
        engine.stop()
    }

    @Test
    fun `a wrong password becomes Failed with AuthenticationFailed`() = runTest(StandardTestDispatcher()) {
        // Task 27 done-when, and Task 31 depends on it: the user must be told to check
        // their password, not shown a generic error.
        val engine = engine(this)
        engine.start()
        engine.register(account)
        advanceUntilIdle()

        gateway.emit(account.id.value, StackRegistrationState.FAILED, statusCode = 401)
        advanceUntilIdle()

        val state = assertIs<RegistrationState.Failed>(engine.registrationState.value[account.id])
        assertEquals(RegistrationFailure.AUTHENTICATION_FAILED, state.reason)
        assertTrue(state.reason.requiresUserAction)
        engine.stop()
    }

    @Test
    fun `credentials are fetched per registration and never retained`() = runTest {
        val engine = engine(this)
        engine.start()

        engine.register(account)

        // The password reached the gateway, which needs it, and came from the repository
        // rather than from anything the engine holds.
        assertEquals("hunter22", gateway.addedAccounts.single().password)

        // And the engine kept none of it. Structural rather than behavioural on purpose:
        // by the time a cached credential shows up in behaviour it has already been in
        // memory for the life of the process, which is what Task 18 forbids.
        assertTrue(
            "hunter22" !in reachableStrings(engine),
            "the engine is holding on to a decrypted password",
        )
        engine.stop()
    }

    @Test
    fun `the account is described to the stack with its effective values`() = runTest {
        val engine = engine(this)
        engine.start()
        engine.register(account)

        val added = gateway.addedAccounts.single()
        assertEquals("alice", added.username)
        // Auth username falls back to the username, and TLS implies port 5061.
        assertEquals("alice", added.authUsername)
        assertEquals("tls", added.transport)
        assertEquals("sip:sip.example.com:5061", added.registrarUri)
        assertEquals(600, added.expirySeconds)
        engine.stop()
    }

    @Test
    fun `unregistering removes the account from the stack`() = runTest {
        val engine = engine(this)
        engine.start()
        engine.register(account)

        val unregistering = launch { engine.unregister(account.id) }
        runCurrent()
        gateway.emit(account.id.value, StackRegistrationState.CLEARED)
        unregistering.join()

        assertEquals(listOf(account.id.value), gateway.removedKeys)
        engine.stop()
    }

    @Test
    fun `unregistering waits for the registrar to acknowledge`() = runTest {
        // The SipRegistrar contract, and Task 29 depends on it: logout stops the
        // foreground service next, and returning before the `Expires: 0` is answered
        // would let that stop cut the request off. A registrar that never hears it keeps
        // ringing this device until the binding lapses.
        val engine = engine(this)
        engine.start()
        engine.register(account)
        gateway.emit(account.id.value, StackRegistrationState.OK)
        runCurrent()

        val unregistering = launch { engine.unregister(account.id) }
        runCurrent()
        assertTrue(unregistering.isActive, "unregister returned before the registrar answered")

        gateway.emit(account.id.value, StackRegistrationState.CLEARED)
        unregistering.join()

        assertEquals(RegistrationState.Unregistered, engine.registrationState.value[account.id])
        engine.stop()
    }

    @Test
    fun `unregistering completes even when the registrar never answers`() = runTest {
        // Bounded, because the alternative is a logout that hangs on an unreachable
        // server. The account is reported unregistered regardless: leaving a stale
        // Registered behind would keep the foreground service alive with nothing to hold
        // open (§6) and show a registration that no longer exists.
        val engine = engine(this)
        engine.start()
        engine.register(account)
        gateway.emit(account.id.value, StackRegistrationState.OK)
        advanceUntilIdle()

        assertIs<Outcome.Success<Unit>>(engine.unregister(account.id))

        assertEquals(RegistrationState.Unregistered, engine.registrationState.value[account.id])
        engine.stop()
    }

    @Test
    fun `logging out leaves the stack holding no credentials`() = runTest {
        // Task 29's third done-when, where a decrypted password actually lives: the
        // stack's auth store. Asserts what is still held, not what was once handed over -
        // an append-only record could never answer the question.
        val engine = engine(this)
        engine.start()
        engine.register(account)
        assertEquals("hunter22", gateway.heldAccounts.getValue(account.id.value).password)

        engine.unregister(account.id)

        assertTrue(
            gateway.heldAccounts.isEmpty(),
            "the stack is still holding a logged-out account's password",
        )
        engine.stop()
    }

    @Test
    fun `unregistering an unknown account succeeds quietly`() = runTest {
        // The SipEngine contract: repeating an operation that already succeeded must not
        // fail, and a caller cannot act on "that was already gone".
        val engine = engine(this)
        engine.start()
        assertIs<Outcome.Success<Unit>>(engine.unregister(AccountId("never-registered")))
        engine.stop()
    }

    @Test
    fun `refreshing an unknown account is reported`() = runTest {
        val engine = engine(this)
        engine.start()
        assertEquals(SipError.UnknownAccount, engine.refreshRegistration(AccountId("nope")).errorOrNull())
        engine.stop()
    }

    @Test
    fun `state updates flow to a collector`() = runTest(StandardTestDispatcher()) {
        val engine = engine(this)
        engine.start()

        engine.registrationState.test {
            assertTrue(awaitItem().isEmpty())

            engine.register(account)
            advanceUntilIdle()
            // expectMostRecentItem, not awaitItem: StateFlow conflates, so asserting a
            // specific intermediate emission is a race rather than a property. That the
            // state PASSES THROUGH Registering is covered by its own test, which reads the
            // value synchronously before the stack answers.
            assertEquals(RegistrationState.Registering, expectMostRecentItem()[account.id])

            gateway.emit(account.id.value, StackRegistrationState.OK)
            advanceUntilIdle()
            assertEquals(RegistrationState.Registered(600), expectMostRecentItem()[account.id])

            cancelAndIgnoreRemainingEvents()
        }
        engine.stop()
    }

    @Test
    fun `stopping clears every registration`() = runTest(StandardTestDispatcher()) {
        val engine = engine(this)
        engine.start()
        engine.register(account)
        gateway.emit(account.id.value, StackRegistrationState.OK)
        advanceUntilIdle()

        engine.stop()

        assertTrue(engine.registrationState.value.isEmpty())
        assertEquals(1, gateway.stopCount)
    }
}

/** The translation itself, independent of the engine's bookkeeping. */
class RegistrationStateMapperTest {

    private fun event(
        state: StackRegistrationState,
        statusCode: Int? = null,
    ) = StackRegistrationEvent("acct-1", state, statusCode, message = null)

    @Test
    fun `a refresh keeps the account usable`() {
        // The binding is still valid throughout, so reporting Registering would make a
        // healthy account flicker every cycle.
        val state = RegistrationStateMapper.toDomain(
            event(StackRegistrationState.REFRESHING),
            requestedExpirySeconds = 300,
            retryScheduled = false,
        )
        assertEquals(RegistrationState.Registered(300), state)
        assertTrue(state.isUsable)
        assertTrue(RegistrationStateMapper.isUsable(StackRegistrationState.REFRESHING))
    }

    @Test
    fun `Cleared is a successful logout, not a failure`() {
        // It is the acknowledgement of Expires: 0. Reporting it as failed would make
        // every logout look like an error.
        assertEquals(
            RegistrationState.Unregistered,
            RegistrationStateMapper.toDomain(
                event(StackRegistrationState.CLEARED),
                requestedExpirySeconds = 300,
                retryScheduled = false,
            ),
        )
    }

    @Test
    fun `every stack state maps to something`() {
        for (state in StackRegistrationState.entries) {
            RegistrationStateMapper.toDomain(event(state), 300, retryScheduled = false)
        }
    }

    @Test
    fun `a failure without a status code is a transport problem, not a rejection`() {
        // The difference between "check your password" and "check your network".
        val error = RegistrationStateMapper.toSipError(event(StackRegistrationState.FAILED))
        assertIs<SipError.TransportFailure>(error)
    }

    @Test
    fun `a status code is mapped through the single error taxonomy`() {
        assertEquals(
            SipError.fromResponseCode(408),
            RegistrationStateMapper.toSipError(event(StackRegistrationState.FAILED, 408)),
        )
        assertEquals(
            SipError.fromResponseCode(503),
            RegistrationStateMapper.toSipError(event(StackRegistrationState.FAILED, 503)),
        )
    }

    @Test
    fun `retryScheduled distinguishes trying again from needing the user`() {
        val retrying = RegistrationStateMapper.toDomain(
            event(StackRegistrationState.FAILED, 408),
            requestedExpirySeconds = 300,
            retryScheduled = true,
        )
        assertTrue(assertIs<RegistrationState.Failed>(retrying).retryScheduled)
    }
}

/**
 * Every string reachable from an object's own bookkeeping.
 *
 * Deliberately does **not** follow [LinphoneCoreGateway] or [SipAccountRepository]. Those
 * two are supposed to hold a credential — the stack while an account is registered, the
 * store always and encrypted — and each is asserted separately. What this measures is
 * everything else, which is where a cached password would hide.
 *
 * A guard rather than a proof: it walks this project's own classes, so a credential
 * squirrelled away inside a framework type would slip past it. It catches the thing that
 * actually happens — a field added to the engine to "avoid decrypting on every refresh".
 */
private fun reachableStrings(root: Any): Set<String> = StringWalk().apply { visit(root) }.found

private class StringWalk {
    private val seen = IdentityHashMap<Any, Boolean>()
    val found: MutableSet<String> = mutableSetOf()

    fun visit(value: Any?) {
        if (value == null || seen.put(value, true) != null) return
        when {
            value is String -> found += value
            value is Map<*, *> -> {
                value.keys.forEach(::visit)
                value.values.forEach(::visit)
            }
            value is Iterable<*> -> value.forEach(::visit)
            value is LinphoneCoreGateway || value is SipAccountRepository -> Unit
            value.javaClass.name.startsWith(PROJECT_PACKAGE) -> visitFields(value)
        }
    }

    private fun visitFields(value: Any) {
        value.javaClass.declaredFields.forEach { field ->
            field.isAccessible = true
            visit(field.get(value))
        }
    }

    private companion object {
        const val PROJECT_PACKAGE = "com.whatsappv2"
    }
}
