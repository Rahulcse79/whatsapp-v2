@file:OptIn(ExperimentalCoroutinesApi::class)

package com.whatsappv2.data.sip

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.data.sip.call.StackCallState
import com.whatsappv2.data.sip.registration.StackMediaEncryption
import com.whatsappv2.data.sip.registration.StackRegistrationState
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.engine.CallSnapshot
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SrtpPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * DoD 13, and Task 62's second done-when: **Mandatory fails rather than downgrades.**
 *
 * ## Why this is asserted here rather than against a server
 *
 * The stack's own `setMediaEncryptionMandatory(true)` refuses the *negotiation*, and
 * proving that needs a cleartext-only peer on a real network — which is the one thing an
 * automated run cannot conjure. What it can prove is the check the engine makes after the
 * fact: a call that arrives at running media without encryption, on an account that
 * requires it, is dropped.
 *
 * Both gates exist and they catch different things. This is the one a test can hold.
 */
class LinphoneSipEngineSecurityTest : LinphoneSipEngineFixture() {

    /** The account, with its media policy replaced. */
    private fun accountWith(policy: SrtpPolicy) = account.copy(srtpPolicy = policy)

    private suspend fun TestScope.registered(policy: SrtpPolicy): LinphoneSipEngine {
        val engine = engine(this)
        engine.start()
        engine.register(accountWith(policy))
        runCurrent()
        gateway.emit(account.id.value, StackRegistrationState.OK)
        runCurrent()
        return engine
    }

    @Test
    fun `the account's policy reaches the stack, and mandatory reaches it as mandatory`() = runTest {
        registered(SrtpPolicy.MANDATORY)

        assertEquals(StackMediaEncryption.MANDATORY, gateway.addedAccounts.last().mediaEncryption)
    }

    @Test
    fun `optional and disabled reach the stack as themselves`() = runTest {
        registered(SrtpPolicy.OPTIONAL)
        assertEquals(StackMediaEncryption.OPTIONAL, gateway.addedAccounts.last().mediaEncryption)

        registered(SrtpPolicy.DISABLED)
        assertEquals(StackMediaEncryption.NONE, gateway.addedAccounts.last().mediaEncryption)
    }

    @Test
    fun `a mandatory account drops a call whose media turned out to be cleartext`() = runTest {
        val engine = registered(SrtpPolicy.MANDATORY)
        val callId = requireNotNull(engine.placeCall(account.id, TARGET, MediaProfile.AUDIO).getOrNull())
        runCurrent()

        gateway.emitCall(callId.value, StackCallState.CONNECTED, mediaEncrypted = false)
        runCurrent()

        // Dropped, not carried on in the clear (§7, DoD 13).
        assertTrue(engine.activeCalls.value.isEmpty())
        assertTrue(gateway.terminatedCalls.contains(callId.value))
    }

    @Test
    fun `the dropped call is recorded as a media failure, not as a hangup`() = runTest {
        val engine = registered(SrtpPolicy.MANDATORY)
        val callId = requireNotNull(engine.placeCall(account.id, TARGET, MediaProfile.AUDIO).getOrNull())
        runCurrent()

        val endings = mutableListOf<CallSnapshot>()
        backgroundScope.launch { engine.endedCalls.collect { endings += it } }
        runCurrent()

        gateway.emitCall(callId.value, StackCallState.CONNECTED, mediaEncrypted = false)
        runCurrent()

        // The call log has to say why. "Remote hangup" for a call this app refused would
        // hide a security event behind an ordinary ending (Task 44, Task 47).
        val terminated = assertIs<CallState.Terminated>(endings.single().state)
        assertEquals(HangupReason.MEDIA_FAILURE, terminated.reason)
    }

    @Test
    fun `a mandatory account keeps a call whose media is encrypted`() = runTest {
        val engine = registered(SrtpPolicy.MANDATORY)
        val callId = requireNotNull(engine.placeCall(account.id, TARGET, MediaProfile.AUDIO).getOrNull())
        runCurrent()

        gateway.emitCall(callId.value, StackCallState.CONNECTED, mediaEncrypted = true)
        runCurrent()

        assertIs<CallState.Connected>(engine.activeCalls.value.single().state)
    }

    @Test
    fun `an optional account keeps a cleartext call, because that is what optional means`() = runTest {
        val engine = registered(SrtpPolicy.OPTIONAL)
        val callId = requireNotNull(engine.placeCall(account.id, TARGET, MediaProfile.AUDIO).getOrNull())
        runCurrent()

        gateway.emitCall(callId.value, StackCallState.CONNECTED, mediaEncrypted = false)
        runCurrent()

        // A user who chose "offer it, accept a peer that cannot" has chosen this.
        assertIs<CallState.Connected>(engine.activeCalls.value.single().state)
    }

    @Test
    fun `an account the engine never registered is not judged by somebody else's policy`() = runTest {
        // No policy on file means no enforcement, which is the only safe default: refusing
        // a call because an account's policy is unknown would break calling on any account
        // added while the stack was restarting.
        val engine = registered(SrtpPolicy.MANDATORY)
        engine.unregister(account.id)
        runCurrent()

        assertTrue(engine.activeCalls.value.isEmpty())
    }
}
