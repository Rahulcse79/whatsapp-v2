package com.whatsappv2.push

import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * How a push's `account_id` finds the account it names (Task 38, ADR-004).
 *
 * The gateway saw a REGISTER; what it can put in the push is the SIP identity that
 * REGISTER carried. The internal id is a UUID this device made up, so a resolver that
 * only knew about it would never match anything a real gateway sends.
 */
class PushAccountResolverTest {

    private val local = account(id = "f0b1c2d3-local", username = "1001", domain = "192.168.2.196")
    private val office = account(id = "a9b8c7d6-office", username = "7000", domain = "192.168.80.145")
    private val accounts = listOf(local, office)

    @Test
    fun `the SIP user the gateway saw register resolves to the account`() {
        assertEquals(local.id, PushAccountResolver.resolve("1001", accounts))
        assertEquals(office.id, PushAccountResolver.resolve("7000", accounts))
    }

    @Test
    fun `a user qualified by domain picks the account on that server`() {
        val twin = account(id = "e5f6-twin", username = "1001", domain = "sip.example.com")

        assertEquals(twin.id, PushAccountResolver.resolve("1001@sip.example.com", accounts + twin))
        assertEquals(local.id, PushAccountResolver.resolve("1001@192.168.2.196", accounts + twin))
    }

    @Test
    fun `the domain is compared case-insensitively, as hostnames are`() {
        val named = account(id = "named", username = "alice", domain = "Sip.Example.com")

        assertEquals(named.id, PushAccountResolver.resolve("alice@sip.example.com", listOf(named)))
    }

    @Test
    fun `the internal id is still accepted`() {
        assertEquals(office.id, PushAccountResolver.resolve("a9b8c7d6-office", accounts))
    }

    @Test
    fun `a wrong domain does not fall back to the bare user`() {
        // Naming a server is a statement; ignoring it would re-register the wrong account
        // and leave the right one asleep.
        assertNull(PushAccountResolver.resolve("1001@elsewhere.example", accounts))
    }

    @Test
    fun `an identity this device does not hold resolves to nothing`() {
        assertNull(PushAccountResolver.resolve("1002", accounts))
        assertNull(PushAccountResolver.resolve("1001", emptyList()))
    }

    private fun account(id: String, username: String, domain: String) = SipAccount(
        id = AccountId(id),
        label = username,
        username = username,
        extension = null,
        authUsername = null,
        password = Secret("1234"),
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
        srtpPolicy = SrtpPolicy.DISABLED,
        codecs = CodecPreferences.DEFAULT,
        isDefault = false,
    )
}
