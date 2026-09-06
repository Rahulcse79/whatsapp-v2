package com.whatsappv2.data.sip.call

import android.content.Context
import com.whatsappv2.core.common.logging.NoOpLogger
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.data.sip.LinphoneSipEngine
import com.whatsappv2.data.sip.network.NetworkMonitor
import com.whatsappv2.data.sip.registration.TestTarget
import com.whatsappv2.data.sip.registration.stack.RealLinphoneCoreGateway
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.registration.NetworkStatus
import com.whatsappv2.domain.registration.NetworkTransport
import com.whatsappv2.domain.testing.FakeAppSettingsRepository
import com.whatsappv2.domain.testing.FakePlatformCallRegistry
import com.whatsappv2.domain.testing.FakeSipAccountRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * One engine, two registered extensions, so a call has both ends (Task 46).
 *
 * ## Why one engine and not two
 *
 * A call needs a caller and a callee. Two `LinphoneSipEngine`s would mean two liblinphone
 * `Core`s in one process, which is not a configuration this app ever ships and not one
 * worth discovering the limits of in a test. The engine already holds many accounts —
 * `registrationState` is keyed by account — so registering both extensions on one engine
 * gives a real INVITE over the network with a real 200 OK coming back, and gives the
 * inbound side as an [com.whatsappv2.domain.engine.IncomingCall] on the other account.
 *
 * That is the whole point of the suite: everything decidable without a server is already
 * exact on the JVM against a fake gateway. What cannot be faked is whether a real INVITE,
 * a real re-INVITE and real RTP do what the fake pretends, and that needs two extensions
 * on a real registrar.
 *
 * ## What stands in for the platform
 *
 * Telecom and the settings store are fakes. They are not what is under test — the JVM
 * suite covers both exactly — and Telecom in particular cannot be driven from an
 * instrumentation test without a foreground call the platform believes in.
 */
internal class CallTestHarness(context: Context, private val target: TestTarget) {

    val platform = FakePlatformCallRegistry()

    private val accounts = FakeSipAccountRepository()
    private val gateway = RealLinphoneCoreGateway(
        context = context,
        // NoOpLogger, not the Android one: this run carries a real credential and the
        // stack is chatty (§7, DoD 12).
        logger = NoOpLogger,
    )
    private val scope = CoroutineScope(SupervisorJob())

    val engine = LinphoneSipEngine(
        gateway = gateway,
        callGateway = gateway,
        accounts = accounts,
        settings = FakeAppSettingsRepository(),
        networkMonitor = AlwaysOnline,
        scope = scope,
        logger = NoOpLogger,
        platform = platform,
    )

    /** The extension placing the call. */
    val caller: SipAccount = account(CALLER_ID, target.extension)

    /** The extension receiving it. */
    val callee: SipAccount = account(CALLEE_ID, target.secondaryExtension)

    fun start() {
        accounts.given(caller)
        accounts.given(callee)
        engine.start()
    }

    fun stop() {
        engine.stop()
        scope.cancel()
    }

    /** The address the caller dials to reach the callee, on the target's own domain. */
    fun calleeAddress(): String = "sip:${target.secondaryExtension}@${target.domain}"

    private fun account(id: String, extension: String) = SipAccount(
        id = AccountId(id),
        label = extension,
        username = extension,
        extension = null,
        authUsername = null,
        password = Secret(target.password),
        displayName = null,
        domain = target.domain,
        registrar = null,
        outboundProxy = null,
        port = target.port,
        // UDP: the target has internal_ssl_enable=false, and the transport question is
        // Task 33's, asserted there over both UDP and TCP.
        transport = Transport.UDP,
        registrationExpirySeconds = EXPIRY_SECONDS,
        stunServer = null,
        turn = null,
        natPolicy = NatPolicy.DEFAULT,
        // OPTIONAL, not MANDATORY: a target with no SRTP would fail every call here and
        // the failure would read as a broken client. Mandatory-SRTP is asserted on the
        // JVM, where the refusal can be provoked exactly (§7, DoD 13).
        srtpPolicy = SrtpPolicy.OPTIONAL,
        codecs = CodecPreferences.DEFAULT,
        isDefault = extension == target.extension,
    )

    /**
     * The link is up, and stays up.
     *
     * A real monitor would be one more thing that can make this suite red for a reason
     * that is not the SIP.
     */
    private object AlwaysOnline : NetworkMonitor {
        override val status: Flow<NetworkStatus> =
            flowOf(NetworkStatus.Available(NETWORK_ID, NetworkTransport.WIFI))
    }

    private companion object {
        const val CALLER_ID = "integration-caller"
        const val CALLEE_ID = "integration-callee"
        const val EXPIRY_SECONDS = 600
        const val NETWORK_ID = 1L
    }
}
