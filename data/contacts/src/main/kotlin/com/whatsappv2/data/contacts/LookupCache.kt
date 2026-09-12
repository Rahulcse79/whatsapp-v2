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
 *
 * Synchronised, because it is read from whichever coroutine asked and [clear] is called
 * from a `ContentObserver` on a binder thread. Contention is not a consideration at
 * thirty-two entries; a `LinkedHashMap` resizing under two threads is.
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

    @Synchronized
    fun containsKey(address: String): Boolean = entries.containsKey(address)

    @Synchronized
    operator fun get(address: String): Contact? = entries[address]

    @Synchronized
    fun put(address: String, contact: Contact?) {
        entries[address] = contact
    }

    /**
     * Forgets everything, because the address book changed underneath it.
     *
     * All of it, not the rows that changed: the provider's change notification says
     * *something* changed and not what, and a cache of thirty-two entries is cheaper to
     * refill than a diff is to work out. The entries worth keeping are re-read on the next
     * lookup that wants them.
     */
    @Synchronized
    fun clear() {
        entries.clear()
    }

    private companion object {
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
    }
}
