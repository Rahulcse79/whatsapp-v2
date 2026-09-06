package com.whatsappv2.data.contacts

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.whatsappv2.core.common.dispatcher.DispatcherProvider
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.domain.contacts.Contact
import com.whatsappv2.domain.contacts.ContactRepository
import com.whatsappv2.domain.model.SipUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device address book, read one caller at a time (Task 49, §5.2, §7).
 *
 * ## What it reads, and what it never does
 *
 * One lookup per address, returning a name and a photo reference. It does not enumerate
 * the address book, does not cache it to disk, and does not send any of it anywhere —
 * §7 and §11 forbid bulk collection, and `ContactsGuardTest` asserts this module has no
 * way to make a network call at all.
 *
 * ## Permission is not an error
 *
 * A denied `READ_CONTACTS` returns null, exactly as an address with no match does. Both
 * mean "show the address as it is", which is what keeps the app fully working for a user
 * who declined — Task 49's second done-when. It is checked rather than caught: querying
 * without permission throws a `SecurityException` on some OEM builds and returns an empty
 * cursor on others, and a check behaves the same on both.
 *
 * ## The cache
 *
 * In memory, bounded, and thrown away with the process. A call screen asks for the same
 * caller repeatedly while a call is ringing, and a provider query per recomposition is
 * both slow and a lot of reads of somebody's address book. It is deliberately not
 * persisted: a contact renamed or deleted must stop being shown, and a copy on disk would
 * be a second address book we then had to keep honest.
 */
@Singleton
class ContactsContractRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) : ContactRepository {

    private val cache = LookupCache(CACHE_ENTRIES)

    override suspend fun resolve(uri: SipUri): Contact? {
        if (!canReadContacts()) return null

        val address = uri.lookupKey()
        cache[address]?.let { return it }
        // A miss and a known-absent are different: the second is worth remembering, so a
        // caller who is not in the address book is looked up once rather than every frame.
        if (cache.containsKey(address)) return null

        val found = withContext(dispatchers.io) { query(address) }
        cache.put(address, found)
        return found
    }

    private fun canReadContacts(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Looks [address] up as a SIP address, then as a phone number.
     *
     * Both, because a SIP URI's user part is very often the person's extension or number
     * and that is how they are filed. SIP first: an exact match on the address the call
     * actually came from beats a number that merely looks like it.
     */
    private fun query(address: String): Contact? =
        bySipAddress(address) ?: byPhoneNumber(address)

    /**
     * Android deprecated the SIP address table without removing it, and without moving the
     * rows already in it: a contact filed with a SIP address years ago is still filed that
     * way on the device in the user's hand. Reading it is the only way to find them, so the
     * deprecation is carried here, at the call site, rather than in a baseline — and
     * [byPhoneNumber] is what finds contacts filed the way the platform now prefers.
     */
    @Suppress("DEPRECATION")
    private fun bySipAddress(address: String): Contact? = read(
        uri = ContactsContract.Data.CONTENT_URI,
        selection = "${ContactsContract.Data.MIMETYPE} = ? AND " +
            "${ContactsContract.CommonDataKinds.SipAddress.SIP_ADDRESS} = ?",
        args = arrayOf(ContactsContract.CommonDataKinds.SipAddress.CONTENT_ITEM_TYPE, address),
    )

    /**
     * Matches the user part as a phone number.
     *
     * `PhoneLookup` rather than a `LIKE` on the number column: it is the provider's own
     * normalisation, so `+44 20 7946 0000` and `02079460000` find the same person, which
     * a string comparison never would.
     */
    private fun byPhoneNumber(address: String): Contact? {
        val user = address.substringBefore('@').takeIf { it.isNotBlank() } ?: return null
        return read(
            uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(user),
            ),
            selection = null,
            args = null,
        )
    }

    private fun read(uri: Uri, selection: String?, args: Array<String>?): Contact? {
        val projection = arrayOf(NAME_COLUMN, PHOTO_COLUMN)

        return runCatching {
            resolver().query(uri, projection, selection, args, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val name = cursor.getString(cursor.getColumnIndexOrThrow(NAME_COLUMN))
                // A nameless match is not a match: showing an empty line where the caller
                // should be is worse than showing the address.
                if (name.isNullOrBlank()) return@use null
                val photo = cursor.getString(cursor.getColumnIndexOrThrow(PHOTO_COLUMN))
                Contact(displayName = name, photoUri = photo?.takeIf { it.isNotBlank() })
            }
        }.onFailure {
            // Never the address or the name: a log line is not the place for either (§7).
            logger.warn(TAG, "Contact lookup failed: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    private fun resolver(): ContentResolver = context.contentResolver

    /** The address as the provider files it: `user@host`, without the scheme. */
    private fun SipUri.lookupKey(): String {
        val user = user
        return if (user == null) host.rendered else "$user@${host.rendered}"
    }

    private companion object {
        const val TAG = "Contacts"
        const val NAME_COLUMN = ContactsContract.Contacts.DISPLAY_NAME
        const val PHOTO_COLUMN = ContactsContract.Contacts.PHOTO_URI

        /**
         * How many lookups to remember.
         *
         * Small on purpose. It exists to stop a ringing call screen re-querying the
         * provider every frame, not to hold an address book in memory.
         */
        const val CACHE_ENTRIES = 32
    }
}
