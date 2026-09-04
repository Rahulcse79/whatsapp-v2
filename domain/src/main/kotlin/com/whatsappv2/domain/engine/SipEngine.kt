package com.whatsappv2.domain.engine

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.call.AudioRoute
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.DtmfDigit
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.RegistrationState
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.TransferType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The single most important interface in the codebase (§4.3).
 *
 * It is the seam between the application and whichever SIP stack is embedded
 * (liblinphone, per ADR-001). Everything above it — use cases, ViewModels, UI — is
 * written against these types and can therefore be built, run and tested with no SIP
 * server, no network, and no device, by substituting `FakeSipEngine` (Task 11).
 *
 * ## Contract
 *
 * **Types.** Only `:domain` types appear in these signatures. No `org.linphone.*` or
 * `org.pjsip.*` type may cross this boundary, enforced by an architecture test
 * (Task 12, DoD 3). SIP response codes are mapped to [SipError] inside `:data:sip`.
 *
 * **Threading.** Every `suspend` function is main-safe: implementations move to their
 * own dispatcher internally, so callers need no `withContext`. Flows emit on an
 * unspecified dispatcher — collect with `flowOn`/`collectLatest` as needed, and never
 * assume the main thread.
 *
 * **Cancellation.** Cancelling a `suspend` call abandons the *result*, not necessarily
 * the SIP transaction: a cancelled [SipCallController.placeCall] may still have put an
 * INVITE on the wire. Implementations must leave the engine in a consistent state and
 * surface the resulting call through [activeCalls] regardless. Never assume cancelling
 * a call to this interface cancelled the call.
 *
 * **Errors are values.** These return [Outcome], not exceptions. A dropped registration
 * is an expected outcome of a mobile network, not an exceptional one, and a caller that
 * can forget a `catch` will.
 *
 * **Idempotence.** Repeating an operation that already succeeded must not fail:
 * unregistering an unregistered account, or hanging up an ended call, succeeds quietly.
 *
 * The role interfaces below exist for interface segregation: a ViewModel that only
 * places calls should not have `register` in scope, and one that only toggles the
 * speaker should not have `transfer`. [SipEngine] composes them, so there is still one
 * thing to inject and one thing to fake.
 */
interface SipEngine : SipRegistrar, SipCallController, SipMediaController, SipConferenceController {

    /**
     * Releases every resource: transports, sockets, media, and the native stack.
     *
     * After this the engine is unusable and every operation returns
     * [SipError.EngineUnavailable]. Called when the last account logs out and no call
     * is active — a stack held open indefinitely is a battery bug (§6).
     */
    suspend fun shutdown()
}

/** Registration and account lifecycle (§5.1). */
interface SipRegistrar {

    /**
     * Registration state per account, as the engine currently understands it.
     *
     * A [StateFlow], so a late collector — a screen opened after registration
     * completed — immediately sees the current value instead of waiting for the next
     * change. Accounts absent from the map have never been registered.
     *
     * Must never report [RegistrationState.Registered] while the transport is down
     * (§6): an honest "offline" is more useful than an optimistic lie.
     */
    val registrationState: StateFlow<Map<AccountId, RegistrationState>>

    /**
     * Registers [account], replacing any existing registration for the same
     * [SipAccount.id].
     *
     * Returns once the registrar has responded, not when the REGISTER was sent.
     * Retries and backoff are **not** this function's job — it reports the outcome of
     * one attempt, and [com.whatsappv2.domain.registration.RegistrationBackoff]
     * (Task 26) decides what happens next. Mixing the two is how a retry loop ends up
     * with two competing schedules.
     */
    suspend fun register(account: SipAccount): Outcome<Unit, SipError>

    /**
     * Unregisters cleanly with `Expires: 0` and forgets the account's credentials.
     *
     * Succeeds if the account was not registered. Returns only once the registrar has
     * acknowledged, so a caller may stop the foreground service afterwards without
     * cutting the request off (Task 29).
     */
    suspend fun unregister(accountId: AccountId): Outcome<Unit, SipError>

    /**
     * Re-sends REGISTER for an account already known to the engine, keeping the same
     * credentials. Used by the expiry refresh and by network-change recovery
     * (Tasks 28, 30).
     */
    suspend fun refreshRegistration(accountId: AccountId): Outcome<Unit, SipError>

    /**
     * Publishes push parameters for RFC 8599 (`pn-provider`, `pn-param`, `pn-prid`) so
     * the server can wake the app for an incoming call (ADR-004, Task 38).
     *
     * Call again on FCM token rotation. Passing `null` clears them.
     */
    suspend fun setPushToken(token: PushToken?): Outcome<Unit, SipError>
}

/**
 * RFC 8599 push parameters, sent on the `Contact` header at REGISTER.
 *
 * Carries no credentials — the push payload only ever says "wake up and re-register"
 * (ADR-004).
 */
data class PushToken(
    /** `pn-provider`, e.g. `fcm`. */
    val provider: String,

    /** `pn-param` — the FCM sender or project identifier. */
    val param: String,

    /** `pn-prid` — the device registration token. */
    val prid: String,
) {
    init {
        require(provider.isNotBlank()) { "push provider must not be blank" }
        require(prid.isNotBlank()) { "push token must not be blank" }
    }

    /** Redacted: the token identifies a device (§7, DoD 12). */
    override fun toString(): String = "PushToken(provider=$provider, prid=***${prid.takeLast(TAIL)})"

    private companion object {
        const val TAIL = 4
    }
}

/** Placing, answering and controlling individual calls (§5.2). */
interface SipCallController {

    /**
     * Every call the engine currently knows about, including held and conference legs.
     *
     * A [StateFlow] of immutable [CallSnapshot]s: a ViewModel may hold one across a
     * configuration change without it mutating underneath.
     */
    val activeCalls: StateFlow<List<CallSnapshot>>

    /**
     * Inbound INVITEs, as events.
     *
     * A [Flow], not a [StateFlow], and deliberately so: ringing must be handled exactly
     * once. A replaying stream would re-ring a call the user already answered after any
     * re-collection, such as a configuration change.
     *
     * Implementations must not drop emissions when there is no collector at that instant
     * — buffer instead — because a call arriving during app startup is precisely the
     * case that matters (§2.5).
     */
    val incomingCalls: Flow<IncomingCall>

    /**
     * Places a call from [accountId] to [target].
     *
     * Returns as soon as the INVITE is accepted for sending, with the [CallId] that
     * will identify it. Progress — ringing, early media, answer — arrives through
     * [activeCalls]; do **not** wait on this function for the call to connect.
     *
     * Fails with [SipError.NotRegistered] if the account is not registered, and with
     * [SipError.MediaNegotiationFailed] when the account requires SRTP and the peer
     * cannot provide it (§7, DoD 13).
     */
    suspend fun placeCall(
        accountId: AccountId,
        target: SipUri,
        media: MediaProfile,
    ): Outcome<CallId, SipError>

    /** Answers a ringing call with the given media. */
    suspend fun answer(callId: CallId, media: MediaProfile): Outcome<Unit, SipError>

    /**
     * Rejects a ringing call.
     *
     * [reason] selects the response: [HangupReason.BUSY] sends 486, and
     * [HangupReason.LOCAL_REJECTED] sends 603 Decline. The distinction is visible to
     * the caller, so it must reflect what the user actually chose.
     */
    suspend fun reject(callId: CallId, reason: HangupReason): Outcome<Unit, SipError>

    /** Ends a call. Succeeds quietly if it has already ended. */
    suspend fun hangup(callId: CallId, reason: HangupReason): Outcome<Unit, SipError>

    /**
     * Holds or resumes, by re-INVITE with the appropriate SDP direction.
     *
     * Only the **local** side is controlled here. A remote hold arrives through
     * [activeCalls]; both sides can hold at once, which is why
     * [com.whatsappv2.domain.call.HoldParty] exists.
     */
    suspend fun setHold(callId: CallId, held: Boolean): Outcome<Unit, SipError>

    /**
     * Sends one DTMF digit.
     *
     * The transport (RFC 4733 telephone-event, or SIP INFO) comes from account
     * configuration, not from this call site (§5.1).
     */
    suspend fun sendDtmf(callId: CallId, digit: DtmfDigit): Outcome<Unit, SipError>

    /**
     * Transfers [callId] to [target].
     *
     * [TransferType.BLIND] sends REFER and returns as soon as it is accepted — success
     * here does **not** mean the transferee answered, and cannot: the transferor drops
     * out of the dialog. [TransferType.ATTENDED] requires [consultationCallId], the
     * established second call whose dialog goes into `Replaces`.
     *
     * A failed transfer must leave the original call usable (§5.2).
     */
    suspend fun transfer(
        callId: CallId,
        target: SipUri,
        type: TransferType,
        consultationCallId: CallId? = null,
    ): Outcome<Unit, SipError>
}

/**
 * Microphone, speaker and camera control for an established call (§5.2, DoD 8).
 *
 * Separate from [SipCallController] because these are device concerns, not call
 * lifecycle: they never change which state the call is in, and the in-call UI needs
 * them without needing the power to transfer or hang up. See
 * [com.whatsappv2.domain.call.CallControls], which models the same distinction.
 */
interface SipMediaController {

    /**
     * Mutes or unmutes the microphone.
     *
     * Local only: no signalling, and the peer cannot tell. Distinct from
     * [SipCallController.setHold], which stops media in an SDP-visible way.
     */
    suspend fun setMuted(callId: CallId, muted: Boolean): Outcome<Unit, SipError>

    /**
     * Selects the audio output.
     *
     * The engine may refuse a route that is not currently available — an
     * [AudioRoute.BLUETOOTH] request with no headset connected fails rather than
     * silently playing on the earpiece.
     */
    suspend fun setAudioRoute(callId: CallId, route: AudioRoute): Outcome<Unit, SipError>

    /**
     * Starts or stops sending video, by re-INVITE. Enabling on an audio-only call is an
     * escalation the peer may decline (Task 54).
     */
    suspend fun setVideoEnabled(callId: CallId, enabled: Boolean): Outcome<Unit, SipError>

    /** Switches between front and rear cameras without renegotiating. */
    suspend fun switchCamera(callId: CallId): Outcome<Unit, SipError>
}

/** Multi-party calling (§2.2, ADR-003). */
interface SipConferenceController {

    /**
     * Conferences currently joined.
     *
     * Under the dial-in MCU there is at most one, and its participant list may be empty
     * when the bridge publishes no roster — [ConferenceSession.rosterAvailable] tells
     * them apart so the UI can say "roster unavailable" instead of "nobody is here".
     */
    val conferences: StateFlow<List<ConferenceSession>>

    /**
     * Joins a conference by dialling [conferenceUri] as an ordinary call (ADR-003).
     *
     * Returns the underlying [CallId]; leaving the conference is [SipCallController.hangup]
     * on it.
     */
    suspend fun joinConference(
        accountId: AccountId,
        conferenceUri: SipUri,
        media: MediaProfile,
    ): Outcome<CallId, SipError>
}
