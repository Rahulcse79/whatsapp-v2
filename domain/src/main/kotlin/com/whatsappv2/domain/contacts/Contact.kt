package com.whatsappv2.domain.contacts

/**
 * A person the address book knows, as much of them as this app needs (Task 49, §5.2).
 *
 * ## Deliberately small
 *
 * A name and a picture. Not an email, not an address, not every number they have — §7 and
 * §11 forbid bulk collection of contact data, and the way to keep that promise is for the
 * type itself to have nowhere to put it. Anything beyond this is read at the moment it is
 * shown and not carried around.
 *
 * [photoUri] is a reference into the provider rather than an image: copying the bytes out
 * would be making a second copy of someone's photograph, which is the thing not to do.
 */
data class Contact(
    /** What the user calls this person. Never blank — a nameless match is not a match. */
    val displayName: String,

    /** A `content://` reference to their photo, or null if they have none. */
    val photoUri: String?,
)
