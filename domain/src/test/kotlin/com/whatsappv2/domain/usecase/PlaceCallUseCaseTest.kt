package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.errorOrNull
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.engine.CameraAvailability
import com.whatsappv2.domain.engine.NoCameraAvailable
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.testing.FakeSipAccountRepository
import com.whatsappv2.domain.testing.FakeSipEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Who places the call, and to where (Task 35).
 *
 * Both are decisions, both are made before any INVITE exists, and neither needs a SIP
 * stack — which is why they live in a use case and are asserted here rather than being
 * discovered on a handset.
 */
class PlaceCallUseCaseTest {

    private val repository = FakeSipAccountRepository()
    private val engine = FakeSipEngine()

    /** A device that can capture. Swapped for [NoCameraAvailable] to test the downgrade. */
    private object CameraPresent : CameraAvailability {
        override fun isCameraUsable(): Boolean = true
    }

    private fun useCase(camera: CameraAvailability = CameraPresent) =
        PlaceCallUseCase(repository, engine, camera, engine)

    private fun account(
        id: String = "acct-1",
        domain: String = "sip.example.com",
        isDefault: Boolean = true,
    ) = SipAccount(
        id = AccountId(id),
        label = "Work",
        username = "alice",
        extension = null,
        authUsername = null,
        password = Secret("hunter22"),
        displayName = null,
        domain = domain,
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
        isDefault = isDefault,
    )

    // ---------------------------------------------------------------- dialling

    @Test
    fun `a bare extension is completed against the account's domain`() = runTest {
        // The line that makes dialling 1001 work at all: a SIP URI has no meaning without
        // a domain, and the account placing the call supplies the right one.
        val work = account()
        repository.save(work)
        engine.givenRegistered(work)

        val callId = useCase()("1001").getOrNull()

        assertEquals("sip:1001@sip.example.com", targetOf(callId?.value))
    }

    @Test
    fun `a full URI is dialled as written, not rewritten to our domain`() = runTest {
        // Otherwise calling someone at another provider silently becomes a call to an
        // extension on ours that probably does not exist.
        val work = account()
        repository.save(work)
        engine.givenRegistered(work)

        val callId = useCase()("sip:bob@other.example.org").getOrNull()

        assertEquals("sip:bob@other.example.org", targetOf(callId?.value))
    }

    @Test
    fun `a user at host with no scheme is completed with one`() = runTest {
        val work = account()
        repository.save(work)
        engine.givenRegistered(work)

        val callId = useCase()("bob@other.example.org").getOrNull()

        assertEquals("sip:bob@other.example.org", targetOf(callId?.value))
    }

    @Test
    fun `surrounding whitespace does not stop a call`() = runTest {
        // People paste addresses. Failing on a trailing space is a bug the user cannot see.
        val work = account()
        repository.save(work)
        engine.givenRegistered(work)

        assertTrue(useCase()("  1001  ").getOrNull() != null)
    }

    @Test
    fun `an empty input is refused before an account is even chosen`() = runTest {
        repository.save(account())

        val error = useCase()("   ").errorOrNull()

        assertIs<PlaceCallError.InvalidTarget>(error)
    }

    // ---------------------------------------------------------------- account choice

    @Test
    fun `with no override the default account places the call`() = runTest {
        repository.save(account(id = "acct-1", isDefault = true))
        val other = account(id = "acct-2", domain = "other.example.org", isDefault = false)
        repository.save(other)
        engine.givenRegistered(account())

        val callId = useCase()("1001").getOrNull()

        // Completed against acct-1's domain, which is how we know acct-1 placed it.
        assertEquals("sip:1001@sip.example.com", targetOf(callId?.value))
    }

    @Test
    fun `an override is honoured, including for resolving the domain`() = runTest {
        // Task 36's per-call account override. It has to affect the domain too: an
        // override that changed the From identity but not the resolution would dial an
        // extension on the wrong server.
        repository.save(account(id = "acct-1", isDefault = true))
        val other = account(id = "acct-2", domain = "other.example.org", isDefault = false)
        repository.save(other)
        engine.givenRegistered(other)

        val callId = useCase()("1001", accountOverride = AccountId("acct-2")).getOrNull()

        assertEquals("sip:1001@other.example.org", targetOf(callId?.value))
    }

    @Test
    fun `an override naming an account that is gone is refused`() = runTest {
        repository.save(account())

        val error = useCase()("1001", accountOverride = AccountId("deleted")).errorOrNull()

        assertEquals(PlaceCallError.UnknownAccount(AccountId("deleted")), error)
    }

    @Test
    fun `with no accounts at all there is nothing to call from`() = runTest {
        val error = useCase()("1001").errorOrNull()

        assertEquals(PlaceCallError.NoAccountAvailable, error)
    }

    // ---------------------------------------------------------------- engine refusal

    @Test
    fun `the engine's reason survives, so the dialer can name it`() = runTest {
        // "Could not place call" is true of every failure and useful for none.
        val work = account()
        repository.save(work)
        // Registered first: the engine refuses an account it does not hold before a
        // scripted failure is ever reached, and that refusal is not the one under test.
        engine.givenRegistered(work)
        // Busy is a reason only the engine can give. NotRegistered is also the engine's
        // own answer to an unregistered account, so asserting on it would let this pass
        // without the cause having travelled at all.
        engine.alwaysFail(FakeSipEngine.Operation.PLACE_CALL, SipError.Busy(BUSY_HERE))

        val error = useCase()("1001").errorOrNull()

        assertEquals(PlaceCallError.Rejected(SipError.Busy(BUSY_HERE)), error)
    }

    @Test
    fun `media defaults to audio, because this is a voice call`() = runTest {
        val work = account()
        repository.save(work)
        engine.givenRegistered(work)

        useCase()("1001")

        assertEquals(MediaProfile.AUDIO, engine.activeCalls.value.single().media)
    }

    /** The address the engine was actually asked to dial. */
    private fun targetOf(callId: String?): String? =
        engine.activeCalls.value.firstOrNull { it.callId.value == callId }?.remote?.render()

    private companion object {
        /** 486, named so the assertion reads as intent rather than arithmetic. */
        const val BUSY_HERE = 486
    }

    // ---------------------------------------------------------------- video

    @Test
    fun `a video call is downgraded to audio when the camera cannot be used`() = runTest {
        // Task 51's second done-when, on the path the dialler's video button uses
        // (Task 74): placed as audio, never refused. Before Task 74 this use case was the
        // only video entry point that skipped the check.
        val work = account()
        repository.save(work)
        engine.givenRegistered(work)

        useCase(NoCameraAvailable)("1001", media = MediaProfile.AUDIO_VIDEO)

        assertEquals(MediaProfile.AUDIO, engine.activeCalls.value.single().media)
    }

    @Test
    fun `a video call keeps its video when the camera is usable`() = runTest {
        val work = account()
        repository.save(work)
        engine.givenRegistered(work)

        useCase()("1001", media = MediaProfile.AUDIO_VIDEO)

        assertEquals(MediaProfile.AUDIO_VIDEO, engine.activeCalls.value.single().media)
    }

    // ------------------------------------------------------- registration (Task 76)

    @Test
    fun `an unregistered account is registered before the call goes out`() = runTest {
        // The reported defect: the use case resolved an account and a target and never
        // consulted registration state, so a call on a lapsed account went to the engine,
        // which refused it — with nothing done about the cause the user could not fix.
        val work = account()
        repository.save(work)
        engine.givenRegistered(work)
        engine.simulateRegistrationExpiry(work.id)

        val outcome = useCase()("1001")

        assertTrue(outcome.getOrNull() != null, "the call is placed, not refused")
        assertTrue(
            engine.invocations.any { it.operation == FakeSipEngine.Operation.REGISTER },
            "the account was registered first",
        )
        assertEquals("sip:1001@sip.example.com", targetOf(outcome.getOrNull()?.value))
    }

    @Test
    fun `registering happens before the INVITE, not after it fails`() = runTest {
        // Order is the whole point. A REGISTER sent after a refused INVITE fixes the next
        // call and not this one, which is what the user experienced.
        val work = account()
        repository.save(work)
        engine.givenRegistered(work)
        engine.simulateRegistrationExpiry(work.id)

        useCase()("1001")

        val ops = engine.invocations.map { it.operation }
        assertTrue(
            ops.indexOf(FakeSipEngine.Operation.REGISTER) <
                ops.indexOf(FakeSipEngine.Operation.PLACE_CALL),
        )
    }

    @Test
    fun `an account that will not register is refused by name, not left to time out`() = runTest {
        // The bound exists so the user is told something within five seconds instead of
        // waiting out SIP Timer B's thirty-two. The error names the account so the dialler
        // can say which one, rather than collapsing into "the call could not be placed".
        val work = account()
        repository.save(work)
        engine.givenRegistered(work)
        engine.simulateRegistrationExpiry(work.id)
        engine.alwaysFail(FakeSipEngine.Operation.REGISTER, SipError.Timeout)

        val error = useCase()("1001").errorOrNull()

        assertEquals(PlaceCallError.NotRegistered(work.id), error)
        assertTrue(
            engine.invocations.none { it.operation == FakeSipEngine.Operation.PLACE_CALL },
            "no INVITE is sent for an account with no binding behind it",
        )
    }

    @Test
    fun `an already registered account is not re-registered`() = runTest {
        // The recovery must not add a REGISTER round trip to every ordinary call. Against
        // the reference server that would be 57 ms on the happy path for nothing.
        val work = account()
        repository.save(work)
        engine.givenRegistered(work)

        useCase()("1001")

        assertTrue(engine.invocations.none { it.operation == FakeSipEngine.Operation.REGISTER })
    }

    @Test
    fun `the override's account is the one that gets registered`() = runTest {
        // The registration has to follow the account the call will actually go out on, not
        // the default — otherwise an override on a cold account registers the wrong one.
        val work = account()
        val home = account(id = "home", domain = "home.example.com", isDefault = false)
        repository.save(work)
        repository.save(home)
        engine.givenRegistered(work)
        engine.givenRegistered(home)
        engine.simulateRegistrationExpiry(home.id)

        useCase()("1001", accountOverride = home.id)

        val registered = engine.invocations
            .filter { it.operation == FakeSipEngine.Operation.REGISTER }
            .map { it.detail }
        assertEquals(listOf(home.id.value), registered)
    }
}
