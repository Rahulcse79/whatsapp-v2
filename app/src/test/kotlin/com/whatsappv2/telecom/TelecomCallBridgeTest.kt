package com.whatsappv2.telecom

import com.whatsappv2.core.common.logging.NoOpLogger
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.testing.FakeSipEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What the platform's call state changes do to the engine (ADR-009).
 *
 * The one rule under test is the one a handset found the hard way: Telecom holds every
 * other active call whenever a call goes active, a local conference is several active
 * calls, and a bridge that carried those holds faithfully ended every eight-way mix with
 * two members held and silent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TelecomCallBridgeTest {

    private val engine = FakeSipEngine()
    private val target = requireNotNull(SipUri.parse("sip:9196@sip.example.com").getOrNull())

    private fun account() = SipAccount(
        id = AccountId("acct-1"),
        label = "Local",
        username = "1001",
        extension = null,
        authUsername = null,
        password = Secret("1234"),
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
        srtpPolicy = SrtpPolicy.DISABLED,
        codecs = CodecPreferences.DEFAULT,
        isDefault = true,
    )

    private fun TestScope.bridge() = TelecomCallBridge(engine, engine, engine, NoOpLogger, this)

    private suspend fun connectedCall(): CallId {
        val id = requireNotNull(engine.placeCall(account().id, target, MediaProfile.AUDIO).getOrNull())
        engine.simulateRemoteAnswer(id)
        return id
    }

    private fun holdsAsked(): List<String> =
        engine.invocations.filter { it.operation == FakeSipEngine.Operation.SET_HOLD }.map { it.detail }

    @Test
    fun `a platform hold on an ordinary call reaches the engine`() = runTest(UnconfinedTestDispatcher()) {
        engine.givenRegistered(account())
        val call = connectedCall()

        bridge().onHoldChanged(call, held = true)

        assertEquals(listOf("${call.value}:true"), holdsAsked())
        assertIs<CallState.Held>(engine.activeCalls.value.single().state)
    }

    @Test
    fun `a platform hold on a mixed call is declined, and the member stays live`() =
        runTest(UnconfinedTestDispatcher()) {
            engine.givenRegistered(account())
            val first = connectedCall()
            val second = connectedCall()
            engine.mixCalls(setOf(first, second))
            assertEquals(setOf(first, second), engine.mixedCalls.value)

            bridge().onHoldChanged(first, held = true)

            assertTrue(holdsAsked().isEmpty(), "the stack was asked to hold a conference member: ${holdsAsked()}")
            assertTrue(engine.activeCalls.value.all { it.state is CallState.Connected })
        }

    @Test
    fun `a platform resume is always carried`() = runTest(UnconfinedTestDispatcher()) {
        engine.givenRegistered(account())
        val first = connectedCall()
        val second = connectedCall()
        engine.mixCalls(setOf(first, second))

        bridge().onHoldChanged(first, held = false)

        assertEquals(listOf("${first.value}:false"), holdsAsked())
    }
}
