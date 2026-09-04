package com.whatsappv2.data.sip

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.domain.call.AudioRoute
import com.whatsappv2.domain.engine.CallSnapshot
import com.whatsappv2.domain.engine.ConferenceSession
import com.whatsappv2.domain.engine.IncomingCall
import com.whatsappv2.domain.engine.PushToken
import com.whatsappv2.domain.engine.SipEngine
import com.whatsappv2.domain.engine.SipError
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A [SipEngine] that does nothing, because no SIP stack is embedded yet.
 *
 * **Temporary. Replaced by the liblinphone implementation in Task 27.**
 *
 * It exists so the dependency graph is complete and the app runs: account management,
 * the list and the editor are all finished, and none of them should be blocked on the
 * SIP stack landing.
 *
 * Every operation fails with [SipError.EngineUnavailable] and every stream is empty,
 * which is **truthful** rather than convenient: there really is no engine, so every
 * account shows as Offline and no call can be placed. A stub that reported success, or
 * pretended accounts were registered, would let screens be built against behaviour that
 * does not exist and would hide the gap until the real stack arrived.
 */
@Singleton
class UnavailableSipEngine @Inject constructor() : SipEngine {

    override val registrationState: StateFlow<Map<AccountId, RegistrationState>> =
        MutableStateFlow(emptyMap())

    override val activeCalls: StateFlow<List<CallSnapshot>> = MutableStateFlow(emptyList())

    override val incomingCalls: Flow<IncomingCall> = emptyFlow()

    override val conferences: StateFlow<List<ConferenceSession>> = MutableStateFlow(emptyList())

    override suspend fun register(account: SipAccount) = unavailable()

    override suspend fun unregister(accountId: AccountId) = unavailable()

    override suspend fun refreshRegistration(accountId: AccountId) = unavailable()

    override suspend fun setPushToken(token: PushToken?) = unavailable()

    override suspend fun placeCall(
        accountId: AccountId,
        target: SipUri,
        media: MediaProfile,
    ): Outcome<CallId, SipError> = failure(SipError.EngineUnavailable)

    override suspend fun answer(callId: CallId, media: MediaProfile) = unavailable()

    override suspend fun reject(callId: CallId, reason: HangupReason) = unavailable()

    override suspend fun hangup(callId: CallId, reason: HangupReason) = unavailable()

    override suspend fun setHold(callId: CallId, held: Boolean) = unavailable()

    override suspend fun sendDtmf(callId: CallId, digit: DtmfDigit) = unavailable()

    override suspend fun transfer(
        callId: CallId,
        target: SipUri,
        type: TransferType,
        consultationCallId: CallId?,
    ) = unavailable()

    override suspend fun setMuted(callId: CallId, muted: Boolean) = unavailable()

    override suspend fun setAudioRoute(callId: CallId, route: AudioRoute) = unavailable()

    override suspend fun setVideoEnabled(callId: CallId, enabled: Boolean) = unavailable()

    override suspend fun switchCamera(callId: CallId) = unavailable()

    override suspend fun joinConference(
        accountId: AccountId,
        conferenceUri: SipUri,
        media: MediaProfile,
    ): Outcome<CallId, SipError> = failure(SipError.EngineUnavailable)

    override suspend fun shutdown() = Unit

    private fun unavailable(): Outcome<Unit, SipError> = failure(SipError.EngineUnavailable)
}
