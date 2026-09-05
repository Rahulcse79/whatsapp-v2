package com.whatsappv2.data.sip.registration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whatsappv2.core.common.logging.NoOpLogger
import com.whatsappv2.data.sip.registration.stack.RealLinphoneCoreGateway
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Registration against a real FreeSWITCH, on a real device (Task 33).
 *
 * ## What this suite is for, and what it is not for
 *
 * Everything decidable without a server is already asserted on the JVM, at 100% of
 * `data/sip/registration`, against a fake gateway. This suite exists for the one thing
 * that cannot be: whether [RealLinphoneCoreGateway] — the class that actually talks to
 * liblinphone — does what the fake pretends it does. Task 27 said explicitly that the
 * SDK-touching classes are "verified on-device from Task 33". This is that.
 *
 * So the assertions here are deliberately coarse. There is no attempt to re-test the
 * state mapping or the backoff sequence; those are exact on the JVM, where the randomness
 * and the clock are injected. What is proved here is that a REGISTER leaves the device,
 * that a registrar answers, and that the answer arrives back through the seam.
 *
 * ## Skips rather than fails
 *
 * With no target configured every test here reports skipped, naming the properties to
 * set. See [TestTarget] and `docs/testing.md`.
 */
@RunWith(AndroidJUnit4::class)
class RegistrationIntegrationTest {

    private lateinit var target: TestTarget
    private lateinit var gateway: RealLinphoneCoreGateway

    @Before
    fun setUp() {
        target = TestTarget.requireConfigured()
        gateway = RealLinphoneCoreGateway(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            // NoOpLogger, not the Android one: this run carries a real credential and
            // the stack is chatty (§7, DoD 12).
            logger = NoOpLogger,
        )
        gateway.start()
    }

    @After
    fun tearDown() {
        if (::gateway.isInitialized) gateway.stop()
    }

    // ---------------------------------------------------------------- transports

    @Test
    fun registersOverUdp() = runBlocking {
        assertEquals(StackRegistrationState.OK, register(transport = "udp")?.state)
    }

    @Test
    fun registersOverTcp() = runBlocking {
        assertEquals(StackRegistrationState.OK, register(transport = "tcp")?.state)
    }

    /**
     * TLS is **not** covered, and that is a property of the deployment rather than of the
     * client.
     *
     * The test target has `internal_ssl_enable=false` and holds no SIP TLS certificate, so
     * a TLS REGISTER against it is refused at the socket — which proves nothing about
     * whether this client can do TLS. Enabling it is Q9 in `docs/architecture.md`, owned
     * by Infra. Asserting two transports and saying so is honest; asserting three by
     * pointing the third at a port with nothing listening is not.
     *
     * This test is here rather than absent so the gap is visible in the report instead of
     * being something a reader has to notice is missing.
     */
    @Test
    @Ignore("Q9: the test target has internal_ssl_enable=false and no SIP TLS certificate")
    fun registersOverTls() = runBlocking {
        // Written, not stubbed: the day Q9 is done, deleting one annotation runs it. An
        // @Ignore shows up as skipped in the report, so the gap is visible rather than
        // being something a reader has to notice is missing.
        assertEquals(StackRegistrationState.OK, register(transport = "tls")?.state)
    }

    // ---------------------------------------------------------------- credentials

    @Test
    fun aWrongPasswordIsRejectedRatherThanTimingOut() = runBlocking {
        // The distinction the whole failure taxonomy rests on: a rejection carries a
        // status code, a dead network does not, and only one of them is worth telling
        // someone to go and change their password over.
        val event = register(transport = "udp", password = "definitely-not-the-password")

        assertNotNull(event, "the registrar must answer a bad password, not ignore it")
        assertEquals(StackRegistrationState.FAILED, event.state)
        assertTrue(
            event.statusCode == UNAUTHORIZED || event.statusCode == PROXY_AUTH_REQUIRED,
            "expected a 401 or 407, got ${event.statusCode}",
        )
    }

    // ---------------------------------------------------------------- recovery

    @Test
    fun recoversAfterATransportDrop() = runBlocking {
        // ADR-005 prefers a client-side drop to restarting a shared server: a restart
        // disrupts anyone else using it, and a real registrar restart is a scheduled
        // manual test instead.
        assertEquals(StackRegistrationState.OK, register(transport = "udp")?.state)

        val recovered = awaitState(StackRegistrationState.OK) {
            gateway.setNetworkReachable(false)
            gateway.setNetworkReachable(true)
        }

        assertNotNull(recovered, "the registration did not come back after the transport dropped")
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Adds the account and waits for the registrar's answer, whatever it is.
     *
     * The REGISTER is sent from `onSubscription`, which runs *after* this collector is
     * registered and before any value is delivered. `registrationEvents` is a
     * `SharedFlow` with `replay = 0`, so adding the account first would race: a registrar
     * on the same LAN can answer before the collector attaches, and the test would hang
     * for twenty seconds waiting for an event that has already been and gone.
     */
    private suspend fun register(
        transport: String,
        password: String = target.password,
    ): StackRegistrationEvent? = withTimeoutOrNull(REGISTER_TIMEOUT_MILLIS) {
        gateway.registrationEvents
            .onSubscription {
                gateway.addAccount(
                    StackAccount(
                        key = ACCOUNT_KEY,
                        username = target.extension,
                        authUsername = target.extension,
                        password = password,
                        domain = target.domain,
                        registrarUri = target.registrarUri,
                        proxyUri = null,
                        transport = transport,
                        expirySeconds = EXPIRY_SECONDS,
                    ),
                )
            }
            // The first thing that is not "still trying".
            .first { it.state != StackRegistrationState.PROGRESS }
    }

    /** Waits for a state, driving [action] once subscribed for the same reason as above. */
    private suspend fun awaitState(
        state: StackRegistrationState,
        action: () -> Unit,
    ): StackRegistrationEvent? = withTimeoutOrNull(RECOVERY_TIMEOUT_MILLIS) {
        gateway.registrationEvents
            .onSubscription { action() }
            .first { it.state == state }
    }

    private companion object {
        const val ACCOUNT_KEY = "integration"
        const val EXPIRY_SECONDS = 60

        /** Generous: a first REGISTER includes the challenge round trip. */
        const val REGISTER_TIMEOUT_MILLIS = 20_000L

        /** Longer: the stack rebuilds its transports before it re-registers. */
        const val RECOVERY_TIMEOUT_MILLIS = 60_000L

        const val UNAUTHORIZED = 401
        const val PROXY_AUTH_REQUIRED = 407
    }
}
