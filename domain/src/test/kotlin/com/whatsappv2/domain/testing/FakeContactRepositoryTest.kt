package com.whatsappv2.domain.testing

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.contacts.SipContact
import com.whatsappv2.domain.model.SipUri
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The address-book fake, tested like the others.
 *
 * A fake that answered differently from the real store would let every test built on it
 * pass against code that is wrong — so its matching, its bound and its "knows nobody"
 * default are asserted here rather than assumed.
 */
class FakeContactRepositoryTest {

    private val contacts = FakeContactRepository()

    @Test
    fun `it knows nobody until told, which is also a denied permission`() = runTest {
        assertNull(contacts.resolve(uri("sip:bob@sip.example.com")))
        assertTrue(contacts.search(query = "", limit = LIMIT).isEmpty())
    }

    @Test
    fun `a taught contact resolves, and the lookup is recorded`() = runTest {
        val bob = uri("sip:bob@sip.example.com")
        contacts.given(bob, name = "Bob Smith", photoUri = "content://photo/1")

        val found = contacts.resolve(bob)

        assertEquals("Bob Smith", found?.displayName)
        assertEquals("content://photo/1", found?.photoUri)
        assertEquals(listOf(bob), contacts.lookups)
    }

    @Test
    fun `search matches the name or the address, as the provider's LIKE does`() = runTest {
        contacts.given(uri("sip:bob@sip.example.com"), name = "Bob Smith")
        contacts.given(uri("sip:carol@other.example.com"), name = "Carol Jones")

        assertEquals(listOf("Carol Jones"), contacts.search("carol", LIMIT).names())
        assertEquals(listOf("Carol Jones"), contacts.search("other.example", LIMIT).names())
        assertEquals(listOf("Bob Smith"), contacts.search("SMITH", LIMIT).names())
    }

    @Test
    fun `a blank query is everyone, in name order`() = runTest {
        contacts.given(uri("sip:carol@sip.example.com"), name = "Carol Jones")
        contacts.given(uri("sip:bob@sip.example.com"), name = "Bob Smith")

        assertEquals(listOf("Bob Smith", "Carol Jones"), contacts.search("", LIMIT).names())
    }

    @Test
    fun `the limit is honoured, so a picker that never bounds its query cannot pass`() = runTest {
        repeat(LIMIT + 2) { index ->
            contacts.given(uri("sip:user$index@sip.example.com"), name = "User $index")
        }

        assertEquals(LIMIT, contacts.search("", LIMIT).size)
    }

    private fun List<SipContact>.names() = map { it.contact.displayName }

    private fun uri(value: String): SipUri = SipUri.parse(value).getOrNull()!!

    private companion object {
        const val LIMIT = 5
    }
}
