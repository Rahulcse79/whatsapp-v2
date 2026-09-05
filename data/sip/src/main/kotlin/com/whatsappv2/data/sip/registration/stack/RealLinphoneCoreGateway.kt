package com.whatsappv2.data.sip.registration.stack

import android.content.Context
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.data.sip.registration.LinphoneCoreGateway
import com.whatsappv2.data.sip.registration.StackAccount
import com.whatsappv2.data.sip.registration.StackRegistrationEvent
import com.whatsappv2.data.sip.registration.StackRegistrationState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.linphone.core.Account
import org.linphone.core.AuthInfo
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.RegistrationState
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real SIP stack, behind the gateway seam.
 *
 * **This is the only class in the project that touches liblinphone's registration API.**
 *
 * It sits in its own package for the same reason `AndroidKeystoreSecretKeyProvider` does:
 * it cannot run on the JVM, so keeping it beside the testable mapper would drag that
 * package's coverage gate down until the gate measured nothing.
 * Everything above it works in this module's own types, which is what keeps DoD 3
 * ("no SDK import outside `:data:sip`") true and what lets the engine be tested on the
 * JVM at all.
 *
 * ## One core, one account per identity
 *
 * A single [Core] holds every transport, and each configured account maps to exactly one
 * liblinphone [Account]. Re-registering an existing identity replaces its params rather
 * than adding a second account — two bindings for one identity fight over the same
 * registrar record, and the loser's calls go to a device that is no longer listening.
 *
 * ## Threading
 *
 * liblinphone's callbacks arrive on the thread that iterates the core. Events are
 * published to a buffered [MutableSharedFlow] rather than handled inline, so nothing this
 * module does can block that iteration — a blocked core stops processing SIP entirely.
 */
@Singleton
internal class RealLinphoneCoreGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) : LinphoneCoreGateway {

    private val events = MutableSharedFlow<StackRegistrationEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
        // Dropping the oldest is the right failure mode: a registration state that has
        // been superseded is not worth blocking the SIP core to deliver.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val registrationEvents: Flow<StackRegistrationEvent> = events.asSharedFlow()

    private var core: Core? = null

    /** Our account key to the stack's account, so a re-register can replace in place. */
    private val accountsByKey = mutableMapOf<String, Account>()

    /**
     * Our account key to the credential the core is holding for it.
     *
     * Tracked purely so it can be taken back out. The core's auth store has no notion of
     * our account keys, and `removeAccount` alone leaves the password sitting in it for
     * the life of the process - which is exactly what Task 29 forbids after a logout.
     */
    private val authInfoByKey = mutableMapOf<String, AuthInfo>()

    private val listener = object : CoreListenerStub() {
        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState,
            message: String,
        ) {
            val key = accountsByKey.entries.firstOrNull { it.value == account }?.key ?: return
            events.tryEmit(
                StackRegistrationEvent(
                    accountKey = key,
                    state = state.toStackState(),
                    // The SIP response code, when the failure came from a server. Null
                    // means the request never got an answer, which the mapper treats as a
                    // transport problem rather than a rejection.
                    statusCode = account.errorInfo.protocolCode.takeIf { it > 0 },
                    message = message,
                ),
            )
        }
    }

    override fun start() {
        if (core != null) return

        val created = Factory.instance().createCore(
            File(context.filesDir, CONFIG_FILE).absolutePath,
            null,
            context,
        )
        created.addListener(listener)
        created.start()
        core = created
        logger.info(TAG, "SIP core started")
    }

    override fun addAccount(account: StackAccount) {
        val core = this.core ?: run {
            logger.error(TAG, "addAccount before start")
            return
        }
        val factory = Factory.instance()

        // Both addresses are parsed before anything is registered: a malformed one after
        // the auth info was stored would leave a credential in the core for an account
        // that never exists.
        val identity = factory.createAddress("sip:${account.username}@${account.domain}")
        val server = factory.createAddress(account.registrarUri)
        if (identity == null || server == null) {
            logger.error(TAG, "Account ${account.key} has an unusable address")
            return
        }

        // The previous credential goes first. The core looks auth entries up by realm and
        // username, so a password change that left the old entry in place would let the
        // stale password answer a challenge the new one should - and would keep it in
        // memory besides.
        authInfoByKey.remove(account.key)?.let(core::removeAuthInfo)

        // Credentials live in the core's auth store, keyed by realm and username, and are
        // looked up when a challenge arrives rather than attached to the params. Held by
        // key so `removeAccount` can take this exact entry back out again.
        val authInfo = factory.createAuthInfo(
            account.authUsername,
            null,
            account.password,
            null,
            null,
            account.domain,
        )
        core.addAuthInfo(authInfo)
        authInfoByKey[account.key] = authInfo

        val params = core.createAccountParams().apply {
            identityAddress = identity
            // setServerAddress, not the deprecated setServerAddr(String): the typed form
            // parses once here rather than re-parsing inside the stack.
            serverAddress = server
            isRegisterEnabled = account.registerEnabled
            expires = account.expirySeconds
            account.proxyUri
                ?.let(factory::createAddress)
                ?.let { setRoutesAddresses(arrayOf(it)) }
        }

        val existing = accountsByKey[account.key]
        if (existing != null) {
            // Replace in place: adding a second account for the same identity would leave
            // two bindings fighting over one registrar record.
            existing.params = params
        } else {
            val created = core.createAccount(params)
            core.addAccount(created)
            accountsByKey[account.key] = created
        }
    }

    override fun removeAccount(accountKey: String) {
        val core = this.core ?: return
        val account = accountsByKey.remove(accountKey) ?: return

        // Turning registration off first is what produces the `Expires: 0`; removing the
        // account outright would drop the binding without telling the registrar, leaving
        // it to ring a device that is no longer listening until the binding expires.
        account.params = account.params.clone().apply { isRegisterEnabled = false }
        core.removeAccount(account)

        // The credential goes with it. Keeping it would mean a logged-out account's
        // password stayed decrypted in the core's auth store until the process died
        // (Task 29). Removed after the account, so the `Expires: 0` can still be signed.
        authInfoByKey.remove(accountKey)?.let(core::removeAuthInfo)
    }

    override fun refreshAccount(accountKey: String) {
        // liblinphone refreshes all registrations together; there is no per-account call.
        // Harmless: a refresh of an already-valid binding is a no-op at the registrar.
        core?.refreshRegisters()
    }

    override fun stop() {
        core?.let { running ->
            running.removeListener(listener)
            // Every credential this gateway handed over, taken back before the core is
            // released - the same rule as `removeAccount`, applied to a shutdown.
            authInfoByKey.values.forEach(running::removeAuthInfo)
            running.stop()
        }
        authInfoByKey.clear()
        accountsByKey.clear()
        core = null
        logger.info(TAG, "SIP core stopped")
    }

    /**
     * Maps the SDK enum into this module's own.
     *
     * Exhaustive with no `else`, on purpose: if the SDK adds a state, this stops
     * compiling, which is the moment to decide what it means rather than defaulting it to
     * something plausible.
     */
    private fun RegistrationState.toStackState(): StackRegistrationState = when (this) {
        RegistrationState.None -> StackRegistrationState.NONE
        RegistrationState.Progress -> StackRegistrationState.PROGRESS
        RegistrationState.Ok -> StackRegistrationState.OK
        RegistrationState.Cleared -> StackRegistrationState.CLEARED
        RegistrationState.Failed -> StackRegistrationState.FAILED
        RegistrationState.Refreshing -> StackRegistrationState.REFRESHING
    }

    private companion object {
        const val TAG = "LinphoneGateway"
        const val CONFIG_FILE = "linphone.rc"
        const val EVENT_BUFFER = 64
    }
}
