package com.whatsappv2.domain.contacts

import com.whatsappv2.domain.model.SipUri

/**
 * Who an address belongs to, if the address book knows (Task 49, §5.2).
 *
 * ## Reading is not collecting
 *
 * §7 and §11 forbid *collecting* the address book — copying it, keeping it, sending it
 * anywhere. They do not forbid showing the user their own contacts, which is what a
 * contact picker is (Task 50). So there are two reads here and no third: [resolve] asks
 * about one caller, and [search] asks for the contacts matching what the user typed.
 *
 * Neither hands back the whole book. [search] is bounded and takes a query, so the only
 * way to enumerate the address book through this interface is one deliberate query at a
 * time — and architecture Rule 9 makes sure that whatever is read cannot leave the device.
 *
 * ## Not knowing is an ordinary answer
 *
 * [resolve] returns null for an address with no match **and** for the case where the app
 * has no permission to look. Both mean the same thing to every caller — show the address
 * as it is — and distinguishing them would only tempt a screen into nagging about a
 * permission the user has already declined. Denying READ_CONTACTS must leave the app
 * fully working, which is Task 49's second done-when.
 */
interface ContactRepository {

    /** The contact for [uri], or null if unknown, unmatched, or unreadable. */
    suspend fun resolve(uri: SipUri): Contact?

    /**
     * Contacts with a SIP address matching [query], for the dialler's picker (Task 50).
     *
     * Only contacts that have a SIP address: this app can call those, and a picker full of
     * people it cannot reach is a list of dead ends. A blank [query] is the picker's
     * opening state and returns the first [limit] of them, which is the one case that
     * looks like enumeration and is why [limit] is not optional.
     *
     * Empty when the permission was declined, exactly as [resolve] returns null — the
     * picker shows nothing to pick and the dialler still dials.
     */
    suspend fun search(query: String, limit: Int): List<SipContact>
}

/**
 * A contact together with the SIP address to call them on.
 *
 * Separate from [Contact] because a picker needs the address and a ringing screen does
 * not: the screen already has it, and giving it one it does not use would be one more
 * place the address book is carried around for no reason.
 */
data class SipContact(
    val contact: Contact,
    val address: SipUri,
)
