package com.whatsappv2.engine

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.engine.SipEngine
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.testing.FakeSipEngine
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 11 done-when #2: `FakeSipEngine` must be consumable from an Android module.
 *
 * `:domain` is a pure JVM module and the fake ships from its `testFixtures`; this test
 * exists in `:app` specifically to prove that dependency actually resolves across the
 * JVM/Android boundary. Without it the fake could compile fine in `:domain` and be
 * unusable exactly where DoD 4 needs it.
 */
class FakeEngineConsumableTest {

    @Test
    fun `the fake satisfies SipEngine and drives a call from an Android module`() = runTest {
        // Typed as the interface deliberately: :app must depend on the contract, never
        // on the fake's own API surface.
        val engine: SipEngine = FakeSipEngine()
        val accountId = AccountId("acct-1")
        val bob = requireNotNull(SipUri.parse("sip:bob@example.com").getOrNull())

        val fake = engine as FakeSipEngine
        fake.simulateIncomingCall(accountId, bob)
        val callId = engine.activeCalls.value.single().callId

        engine.answer(callId, MediaProfile.AUDIO)
        assertEquals(1, engine.activeCalls.value.size)

        fake.simulateRemoteHangup(callId)
        assertTrue(engine.activeCalls.value.isEmpty())
    }
}
