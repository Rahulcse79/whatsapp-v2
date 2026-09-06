package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.call.HoldParty
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.TransferType
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.testing.FakeSipAccountRepository
import com.whatsappv2.domain.testing.FakeSipEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Blind and attended transfer (Tasks 55 and 57, DoD 10). */
class TransferCallUseCaseTest {

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
        transport = Transport.UDP,
        registrationExpirySeconds = 3_600,
        stunServer = null,
        turn = null,
        natPolicy = NatPolicy.DEFAULT,
        srtpPolicy = SrtpPolicy.OPTIONAL,
        codecs = CodecPreferences.DEFAULT,
        isDefault = true,
    )

    private val bob = requireNotNull(SipUri.parse("sip:bob@example.com").getOrNull())

    private fun engine() = FakeSipEngine().givenRegistered(account)
    private fun accounts() = FakeSipAccountRepository().given(account)
    private fun useCase(engine: FakeSipEngine) = TransferCallUseCase(engine, accounts())

    private suspend fun connected(engine: FakeSipEngine): CallId =
        engine.simulateIncomingCall(account.id, bob).callId
            .also { engine.answer(it, MediaProfile.AUDIO) }

    // ================================================================ blind

    @Test
    fun `a bare extension is completed against the account's own domain`() = runTest {
        val engine = engine()
        val callId = connected(engine)

        assertIs<Outcome.Success<Unit>>(useCase(engine).blind(callId, "1002"))

        // Dialling 1002 and transferring to 1002 must mean the same address (Task 55).
        val transfer = engine.invocations.last { it.operation == FakeSipEngine.Operation.TRANSFER }
        assertTrue(transfer.detail.endsWith("sip:1002@sip.example.com"))
    }

    @Test
    fun `a full URI is transferred as written`() = runTest {
        val engine = engine()
        val callId = connected(engine)

        useCase(engine).blind(callId, "sip:1002@other.example.com")

        val transfer = engine.invocations.last { it.operation == FakeSipEngine.Operation.TRANSFER }
        assertTrue(transfer.detail.endsWith("sip:1002@other.example.com"))
    }

    @Test
    fun `an unusable target is refused before anything is sent`() = runTest {
        val engine = engine()
        val callId = connected(engine)
        engine.clearInvocations()

        val result = useCase(engine).blind(callId, "   ")

        assertIs<TransferError.InvalidTarget>(assertIs<Outcome.Failure<TransferError>>(result).error)
        assertTrue(engine.invocations.none { it.operation == FakeSipEngine.Operation.TRANSFER })
    }

    @Test
    fun `transferring a call that is not there is refused`() = runTest {
        val engine = engine()

        val result = useCase(engine).blind(CallId("gone"), "1002")

        assertIs<TransferError.UnknownCall>(assertIs<Outcome.Failure<TransferError>>(result).error)
    }

    @Test
    fun `an engine refusal is carried through with its cause`() = runTest {
        val engine = engine()
        val callId = connected(engine)
        engine.failNext(FakeSipEngine.Operation.TRANSFER, SipError.Forbidden)

        val result = useCase(engine).blind(callId, "1002")

        val error = assertIs<TransferError.Rejected>(assertIs<Outcome.Failure<TransferError>>(result).error)
        assertEquals(SipError.Forbidden, error.cause)
    }

    // ================================================================ attended

    @Test
    fun `a consultation holds the first call before ringing the second`() = runTest {
        val engine = engine()
        val callId = connected(engine)

        val consultation = useCase(engine).startConsultation(callId, "1002")

        assertIs<Outcome.Success<CallId>>(consultation)
        assertEquals(CallState.Held(HoldParty.LOCAL), engine.activeCalls.value.single { it.callId == callId }.state)

        // The order: hold, then place. The other way round is two live calls.
        val ops = engine.invocations.map { it.operation }
        assertTrue(ops.indexOf(FakeSipEngine.Operation.SET_HOLD) < ops.indexOf(FakeSipEngine.Operation.PLACE_CALL))
    }

    @Test
    fun `a failed hold stops the consultation before a second call is placed`() = runTest {
        val engine = engine()
        val callId = connected(engine)
        engine.failNext(FakeSipEngine.Operation.SET_HOLD, SipError.EngineUnavailable)
        engine.clearInvocations()

        val result = useCase(engine).startConsultation(callId, "1002")

        assertIs<Outcome.Failure<TransferError>>(result)
        assertTrue(engine.invocations.none { it.operation == FakeSipEngine.Operation.PLACE_CALL })
    }

    @Test
    fun `completing the transfer names the consultation call in the REFER`() = runTest {
        val engine = engine()
        val callId = connected(engine)
        val consultation = requireNotNull(useCase(engine).startConsultation(callId, "1002").getOrNull())
        engine.simulateRemoteAnswer(consultation)

        assertIs<Outcome.Success<Unit>>(useCase(engine).completeAttended(callId, consultation))

        val transfer = engine.invocations.last { it.operation == FakeSipEngine.Operation.TRANSFER }
        assertTrue(transfer.detail.contains(TransferType.ATTENDED.name))
    }

    @Test
    fun `completing with a consultation that has gone is refused`() = runTest {
        val engine = engine()
        val callId = connected(engine)

        val result = useCase(engine).completeAttended(callId, CallId("gone"))

        assertIs<TransferError.UnknownCall>(assertIs<Outcome.Failure<TransferError>>(result).error)
    }

    @Test
    fun `cancelling the consultation hangs up the second call and resumes the first`() = runTest {
        val engine = engine()
        val callId = connected(engine)
        val consultation = requireNotNull(useCase(engine).startConsultation(callId, "1002").getOrNull())

        assertIs<Outcome.Success<Unit>>(useCase(engine).cancelConsultation(callId, consultation))

        // Task 57's second done-when: back to call A, connected, and B is gone.
        assertIs<CallState.Connected>(engine.activeCalls.value.single { it.callId == callId }.state)
        assertTrue(engine.activeCalls.value.none { it.callId == consultation })
    }

    @Test
    fun `cancelling resumes the first call even when hanging the second up fails`() = runTest {
        val engine = engine()
        val callId = connected(engine)
        val consultation = requireNotNull(useCase(engine).startConsultation(callId, "1002").getOrNull())
        engine.failNext(FakeSipEngine.Operation.HANGUP, SipError.EngineUnavailable)

        val result = useCase(engine).cancelConsultation(callId, consultation)

        // The failure is reported, but the user is not left on a call they cannot hear.
        assertIs<Outcome.Failure<TransferError>>(result)
        assertIs<CallState.Connected>(engine.activeCalls.value.single { it.callId == callId }.state)
    }
}
