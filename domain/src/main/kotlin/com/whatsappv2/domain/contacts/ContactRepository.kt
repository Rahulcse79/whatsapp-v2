package com.whatsappv2.domain.contacts

import com.whatsappv2.domain.model.SipUri

/**
 * Who an address belongs to, if the address book knows (Task 49, §5.2).
 *
 * ## One address at a time, on purpose
 *
 * There is no "load all contacts" here, and there is not going to be. §7 and §11 forbid
 * bulk collection, and an interface that cannot express it cannot accidentally grow a
 * caller that does it. Resolution is a question about one caller, asked when that caller
 * is on screen.
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
}
