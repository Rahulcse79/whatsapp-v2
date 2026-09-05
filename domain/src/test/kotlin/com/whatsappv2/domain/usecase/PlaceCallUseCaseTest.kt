package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.errorOrNull
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
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

    private fun useCase() = PlaceCallUseCase(repository, engine)

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
        engine.alwaysFail(FakeSipEngine.Operation.PLACE_CALL, SipError.NotRegistered)

        val error = useCase()("1001").errorOrNull()

        assertEquals(PlaceCallError.Rejected(SipError.NotRegistered), error)
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
}
