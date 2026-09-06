package com.whatsappv2.data.contacts

import com.whatsappv2.domain.contacts.Contact

/**
 * The last few lookups, and nothing more (Task 49, §7).
 *
 * Its own class rather than `android.util.LruCache` for two reasons: that one cannot hold
 * a null, and "this address is not in the address book" is exactly the answer worth
 * remembering — without it a caller who is not a contact is looked up on every frame of a
 * ringing screen.
 *
 * Bounded and in memory. It exists to stop repeated provider reads during one call, not
 * to hold an address book: a copy that outlived the process would be a second address
 * book to keep honest, and §11 says not to make one.
 */
internal class LookupCache(private val maxEntries: Int) {

    // accessOrder = true is the third argument: it is what makes this least-recently-used
    // rather than least-recently-inserted, so a caller asked for repeatedly stays.
    private val entries = object : LinkedHashMap<String, Contact?>(
        INITIAL_CAPACITY,
        LOAD_FACTOR,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Contact?>): Boolean =
            size > maxEntries
    }

    fun containsKey(address: String): Boolean = entries.containsKey(address)

    operator fun get(address: String): Contact? = entries[address]

    fun put(address: String, contact: Contact?) {
        entries[address] = contact
    }

    private companion object {
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
    }
}
