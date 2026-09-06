package com.whatsappv2.domain.testing

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.contacts.Contact
import com.whatsappv2.domain.contacts.ContactRepository
import com.whatsappv2.domain.contacts.SipContact
import com.whatsappv2.domain.model.SipUri

/**
 * An address book that knows only what a test told it.
 *
 * Knows nobody by default, which is also what a device with `READ_CONTACTS` denied looks
 * like — so the ordinary case in a test is the case the app has to keep working for.
 */
class FakeContactRepository : ContactRepository {

    private val known = mutableMapOf<String, Contact>()

    /** Every address that was looked up, in order. */
    val lookups: MutableList<SipUri> = mutableListOf()

    /** Teaches it one person. */
    fun given(uri: SipUri, name: String, photoUri: String? = null): FakeContactRepository = apply {
        known[uri.render()] = Contact(displayName = name, photoUri = photoUri)
    }

    override suspend fun resolve(uri: SipUri): Contact? {
        lookups += uri
        return known[uri.render()]
    }

    /**
     * Matches on the name or the address, like the provider's `LIKE` does, and stops at
     * [limit] — a fake that returned everything would let a test pass against a picker
     * that never bounded its query.
     */
    override suspend fun search(query: String, limit: Int): List<SipContact> =
        known.entries
            .filter { (address, contact) ->
                query.isBlank() ||
                    contact.displayName.contains(query, ignoreCase = true) ||
                    address.contains(query, ignoreCase = true)
            }
            .sortedBy { it.value.displayName }
            .mapNotNull { (address, contact) ->
                SipUri.parse(address).getOrNull()?.let { SipContact(contact, it) }
            }
            .take(limit)
}
