package com.whatsappv2.domain.testing

import com.whatsappv2.domain.contacts.Contact
import com.whatsappv2.domain.contacts.ContactRepository
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
}
