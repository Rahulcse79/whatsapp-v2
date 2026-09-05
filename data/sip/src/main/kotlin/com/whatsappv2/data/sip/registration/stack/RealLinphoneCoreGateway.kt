package com.whatsappv2.data.sip.registration.stack

import android.content.Context
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.data.sip.call.LinphoneCallGateway
import com.whatsappv2.data.sip.call.StackCallEvent
import com.whatsappv2.data.sip.call.StackCallState
import com.whatsappv2.data.sip.registration.LinphoneCoreGateway
import com.whatsappv2.data.sip.registration.StackAccount
import com.whatsappv2.data.sip.registration.StackPushParameters
import com.whatsappv2.data.sip.registration.StackRegistrationEvent
import com.whatsappv2.data.sip.registration.StackRegistrationState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.linphone.core.Account
import org.linphone.core.AuthInfo
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.Reason
import org.linphone.core.RegistrationState
import java.io.File
import java.util.UUID
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
) : LinphoneCoreGateway, LinphoneCallGateway {

    private val events = MutableSharedFlow<StackRegistrationEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
        // Dropping the oldest is the right failure mode: a registration state that has
        // been superseded is not worth blocking the SIP core to deliver.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val registrationEvents: Flow<StackRegistrationEvent> = events.asSharedFlow()

    private val callEventFlow = MutableSharedFlow<StackCallEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val callEvents: Flow<StackCallEvent> = callEventFlow.asSharedFlow()

    /** Our call key to the stack's call, so terminate can find the right one. */
    private val callsByKey = mutableMapOf<String, Call>()

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

        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State,
            message: String,
        ) {
            // Released first, and before the mapping: it is the stack's signal that it
            // will not touch this call again, and it maps to no app state — so a version
            // that checked it after the mapping's early return never ran, and leaked one
            // entry per call for the life of the process.
            if (state == Call.State.Released) {
                callsByKey.entries.firstOrNull { it.value == call }?.let { callsByKey -= it.key }
                return
            }

            val mapped = state.toStackCallState() ?: return
            // An inbound INVITE is the one call this gateway has never seen before, so it
            // is the one place a key is minted rather than looked up. Everything above
            // this line addresses calls by the app's id and never by the stack's object.
            val key = callsByKey.entries.firstOrNull { it.value == call }?.key
                ?: if (mapped == StackCallState.INCOMING_RECEIVED) {
                    UUID.randomUUID().toString().also { callsByKey[it] = call }
                } else {
                    return
                }

            callEventFlow.tryEmit(
                StackCallEvent(
                    callKey = key,
                    // Which of our accounts placed it. Empty when the core did not
                    // attribute the call to one, which the engine treats as unknown.
                    // The account hangs off the call's params, not off the call: the
                    // stack resolves it there for an inbound INVITE just as `placeCall`
                    // sets it there for an outbound one.
                    accountKey = accountsByKey.entries
                        .firstOrNull { it.value == call.params.account }
                        ?.key
                        .orEmpty(),
                    remoteUri = call.remoteAddress.asStringUriOnly(),
                    remoteDisplayName = call.remoteAddress.displayName,
                    state = mapped,
                    statusCode = call.errorInfo.protocolCode.takeIf { it > 0 },
                    message = message,
                    // What the peer offered, read from the remote parameters rather than
                    // from ours: ours say what we would accept, not what was asked for.
                    videoOffered = mapped == StackCallState.INCOMING_RECEIVED &&
                        call.remoteParams?.isVideoEnabled == true,
                ),
            )
        }
    }

    override fun placeCall(
        callKey: String,
        accountKey: String,
        destination: String,
        videoEnabled: Boolean,
    ) {
        val activeCore = core ?: run {
            logger.error(TAG, "placeCall before the core was started")
            return
        }
        val address = Factory.instance().createAddress(destination) ?: run {
            logger.error(TAG, "Unparseable destination for call $callKey")
            return
        }

        val params = activeCore.createCallParams(null) ?: run {
            logger.error(TAG, "The core refused to create call params")
            return
        }
        params.isVideoEnabled = videoEnabled
        // Bind the call to the requested identity rather than whichever account the core
        // considers default - a per-call account override (Task 36) is meaningless
        // otherwise.
        accountsByKey[accountKey]?.let { params.account = it }

        activeCore.inviteAddressWithParams(address, params)
            ?.let { callsByKey[callKey] = it }
            ?: logger.error(TAG, "The core refused the INVITE for $callKey")
    }

    override fun answerCall(callKey: String, videoEnabled: Boolean) {
        val call = callsByKey[callKey] ?: run {
            logger.warn(TAG, "Answer for a call the stack no longer has: $callKey")
            return
        }
        val activeCore = core ?: return

        // acceptWithParams, not accept(): a plain accept answers with whatever the core's
        // defaults say, which on a video-capable build can add a video stream the caller
        // never offered and the user never asked for.
        val params = activeCore.createCallParams(call) ?: run {
            logger.error(TAG, "The core refused to create answer params for $callKey")
            return
        }
        params.isVideoEnabled = videoEnabled
        call.acceptWithParams(params)
    }

    override fun rejectCall(callKey: String, busy: Boolean) {
        // Busy Here versus Decline. The caller hears the difference, so the choice is the
        // caller's and never a default picked here.
        callsByKey[callKey]?.decline(if (busy) Reason.Busy else Reason.Declined)
    }

    override fun setMicrophoneMuted(callKey: String, muted: Boolean) {
        // Per call, not on the core: a core-wide mute would silence a second call the
        // user never muted (Task 56).
        callsByKey[callKey]?.microphoneMuted = muted
    }

    /**
     * Holds by re-INVITE (Task 41).
     *
     * `pause()` and nothing else: the stack writes the SDP direction, which is `sendonly`
     * while only we hold and `inactive` once both ends do. Setting a direction by hand
     * through call params would re-derive a rule the stack already applies, and the
     * both-hold case is exactly where a hand-rolled version gets it wrong.
     *
     * A non-zero return means the stack refused — a call in a state that cannot be paused.
     * Logged rather than thrown: the engine's own state is unchanged, so the screen
     * continues to show a call that is not held, which is the truth.
     */
    override fun pauseCall(callKey: String) {
        val call = callsByKey[callKey] ?: run {
            logger.warn(TAG, "Hold for a call the stack no longer has: $callKey")
            return
        }
        if (call.pause() != OK) logger.warn(TAG, "The stack refused to hold $callKey")
    }

    /** Resumes a call this app holds. The far end's own hold is not ours to lift. */
    override fun resumeCall(callKey: String) {
        val call = callsByKey[callKey] ?: run {
            logger.warn(TAG, "Resume for a call the stack no longer has: $callKey")
            return
        }
        if (call.resume() != OK) logger.warn(TAG, "The stack refused to resume $callKey")
    }

    /**
     * Sends one DTMF digit (Task 43).
     *
     * The transport is set on the core immediately before the digit, because that is
     * where liblinphone keeps it — `setUseRfc2833ForDtmf` and `setUseInfoForDtmf` are
     * core-wide settings, not call params. Setting both on every digit is what makes a
     * change in Settings take effect on the next digit rather than the next call, and it
     * is the only way the two flags cannot be left in a stale combination.
     *
     * Exactly one is enabled. With both on, liblinphone sends the digit by both carriers
     * at once, and an IVR that counts keypresses hears two.
     */
    override fun sendDtmf(callKey: String, digit: Char, useInfo: Boolean) {
        val call = callsByKey[callKey] ?: run {
            logger.warn(TAG, "DTMF for a call the stack no longer has: $callKey")
            return
        }
        core?.apply {
            useRfc2833ForDtmf = !useInfo
            useInfoForDtmf = useInfo
        }
        // The digit itself is never logged: a DTMF sequence is a PIN or a card number as
        // often as it is a menu choice (§7, DoD 12).
        if (call.sendDtmf(digit) != OK) logger.warn(TAG, "The stack refused a DTMF digit on $callKey")
    }

    override fun terminateCall(callKey: String) {
        // Idempotent: a call the stack has already released is one the caller wanted gone.
        callsByKey[callKey]?.terminate()
    }

    /**
     * liblinphone's call states, reduced to the ones this app branches on.
     *
     * Null for the states that carry no decision - `Released`, the `Updating` family, the
     * pausing intermediates. Emitting them would make every consumer re-check that they do
     * not matter.
     */
    private fun Call.State.toStackCallState(): StackCallState? = when (this) {
        // PushIncomingReceived is the same INVITE seen one step earlier - the core knows a
        // call is coming because a push woke it, before the INVITE itself has arrived
        // (Task 38). Both mean "a call is arriving", and the app has one answer to that.
        Call.State.IncomingReceived, Call.State.PushIncomingReceived ->
            StackCallState.INCOMING_RECEIVED

        Call.State.OutgoingInit -> StackCallState.OUTGOING_INIT
        Call.State.OutgoingProgress -> StackCallState.OUTGOING_PROGRESS
        Call.State.OutgoingRinging -> StackCallState.OUTGOING_RINGING
        Call.State.OutgoingEarlyMedia -> StackCallState.OUTGOING_EARLY_MEDIA
        Call.State.Connected -> StackCallState.CONNECTED
        Call.State.StreamsRunning -> StackCallState.STREAMS_RUNNING

        // Three states where there used to be one. Which end is holding decides which end
        // can resume, and collapsing them is how "resume did nothing" bugs happen (Task
        // 41). `Pausing` stays absent: it is the intermediate before `Paused`, and the app
        // has nothing different to do while a hold is in flight.
        Call.State.Paused -> StackCallState.PAUSED
        Call.State.PausedByRemote -> StackCallState.PAUSED_BY_REMOTE
        Call.State.Resuming -> StackCallState.RESUMING
        Call.State.End -> StackCallState.ENDED
        Call.State.Error -> StackCallState.ERROR
        else -> null
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

    /**
     * Publishes RFC 8599 parameters on the `Contact` header (ADR-004, Task 38).
     *
     * Set on the core rather than assembled by hand: liblinphone owns the `Contact` header
     * and writes `pn-provider`, `pn-param` and `pn-prid` into it for every account that
     * allows push. Hand-writing them into `contactParameters` would fight the stack for
     * the same header.
     *
     * Each account is then re-registered, because a parameter the registrar has not seen
     * has no effect — the binding it must update is the one already on file.
     */
    override fun setPushParameters(parameters: StackPushParameters?) {
        val core = this.core ?: run {
            logger.warn(TAG, "Push parameters set before the core was started")
            return
        }

        core.isPushNotificationEnabled = parameters != null
        if (parameters != null) {
            // Nullable in the SDK: a core built without push support exposes no config,
            // and there is then nowhere to put the token. Warn rather than throw - the
            // registration itself is still valid, it just will not be woken by a push.
            val config = core.pushNotificationConfig
            if (config == null) {
                logger.warn(TAG, "The core exposes no push notification config")
            } else {
                config.provider = parameters.provider
                config.param = parameters.param
                config.prid = parameters.prid
            }
        }

        // The flag is per account and defaults off, so an account added before the token
        // arrived would never carry the parameters without this.
        accountsByKey.values.forEach { account ->
            account.params = account.params.clone().apply {
                pushNotificationAllowed = parameters != null
            }
        }
        core.refreshRegisters()
    }

    override fun refreshAccount(accountKey: String) {
        // liblinphone refreshes all registrations together; there is no per-account call.
        // Harmless: a refresh of an already-valid binding is a no-op at the registrar.
        core?.refreshRegisters()
    }

    override fun setNetworkReachable(reachable: Boolean) {
        // No-op before start(), which is correct: a stack that does not exist has no
        // sockets to rebind, and the first register will bind against whatever is up.
        core?.isNetworkReachable = reachable
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
        // The calls go with the core that owned them. Keeping the references would leave
        // this gateway able to terminate calls belonging to a stack that no longer exists.
        callsByKey.clear()
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

        /** What liblinphone returns from a request it accepted; anything else is -1. */
        const val OK = 0
    }
}
