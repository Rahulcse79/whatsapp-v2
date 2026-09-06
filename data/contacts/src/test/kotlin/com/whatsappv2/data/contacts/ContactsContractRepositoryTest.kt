package com.whatsappv2.data.contacts

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import com.whatsappv2.core.common.dispatcher.DispatcherProvider
import com.whatsappv2.core.common.logging.NoOpLogger
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.model.SipUri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading the address book, and not reading it (Task 49).
 *
 * ## Why a stub provider
 *
 * Robolectric ships no contacts provider, so inserting into `ContactsContract` and reading
 * it back tests nothing — the rows go nowhere. A stub registered on the real authority
 * does better than a fake `ContentResolver` would: the repository's own URIs, projection
 * and selection all have to reach it, so a query built wrongly fails here rather than on a
 * handset.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [CONTACTS_ROBOLECTRIC_SDK])
class ContactsContractRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val dispatchers = TestDispatchers()
    private lateinit var provider: StubContactsProvider

    private fun repository() = ContactsContractRepository(context, dispatchers, NoOpLogger)

    @Before
    fun setUp() {
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS)
        provider = Robolectric.buildContentProvider(StubContactsProvider::class.java)
            .create(ProviderInfo().apply { authority = ContactsContract.AUTHORITY })
            .get()
    }

    @Test
    fun `a SIP address the address book knows resolves to that person`() = runTest {
        provider.given(name = "Bob Smith", photo = "content://photo/1")

        val contact = repository().resolve(uri("sip:bob@sip.example.com"))

        assertEquals("Bob Smith", contact?.displayName)
        assertEquals("content://photo/1", contact?.photoUri)
    }

    @Test
    fun `the address is asked for as the provider files it, without the scheme`() = runTest {
        provider.given(name = "Bob Smith")

        repository().resolve(uri("sip:bob@sip.example.com"))

        // `sip:` is ours; the provider stores `user@host`. Asking with the scheme on would
        // match nobody, and would do it silently.
        assertTrue(provider.lastArgs.orEmpty().contains("bob@sip.example.com"))
    }

    @Test
    fun `an address nobody has resolves to nothing, which is an ordinary answer`() = runTest {
        assertNull(repository().resolve(uri("sip:nobody@sip.example.com")))
    }

    @Test
    fun `without permission it answers nothing, and asks nobody`() = runTest {
        // Task 49's second done-when: declining READ_CONTACTS leaves the app working, and
        // every caller treats "not known" and "not allowed to look" the same way.
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.READ_CONTACTS)
        provider.given(name = "Bob Smith")

        assertNull(repository().resolve(uri("sip:bob@sip.example.com")))
        assertNull(provider.lastArgs, "the provider must not be queried at all")
    }

    @Test
    fun `a match with no name is not a match`() = runTest {
        // An empty line where the caller's name goes is worse than showing the address.
        provider.given(name = "")

        assertNull(repository().resolve(uri("sip:blank@sip.example.com")))
    }

    @Test
    fun `the same caller is looked up once while a call is ringing`() = runTest {
        provider.given(name = "Bob Smith")
        val repository = repository()
        val address = uri("sip:bob@sip.example.com")
        assertEquals("Bob Smith", repository.resolve(address)?.displayName)

        // Emptied behind the cache: a second provider read would find nobody, so a second
        // answer of "Bob Smith" can only be the cache answering.
        provider.clear()

        assertEquals("Bob Smith", repository.resolve(address)?.displayName)
    }

    @Test
    fun `a caller who is not a contact is looked up once too`() = runTest {
        val repository = repository()
        val address = uri("sip:nobody@sip.example.com")
        assertNull(repository.resolve(address))

        provider.given(name = "Appeared Later")

        // Absence is remembered as well: without that, a ringing screen re-reads the
        // address book for every frame a stranger is calling.
        assertNull(repository.resolve(address))
    }

    private fun uri(value: String): SipUri = SipUri.parse(value).getOrNull()!!
}

/**
 * A contacts provider that returns exactly what a test told it to.
 *
 * Registered on the real authority, so the repository reaches it through the same URIs it
 * would use on a device.
 */
class StubContactsProvider : ContentProvider() {

    private var rows: List<Array<Any?>> = emptyList()

    /** The selection arguments of the last query, or null if there has not been one. */
    var lastArgs: List<String>? = null
        private set

    fun given(name: String, photo: String? = null) {
        rows = listOf(arrayOf(name, photo))
    }

    fun clear() {
        rows = emptyList()
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        lastArgs = selectionArgs?.toList() ?: uri.pathSegments
        return MatrixCursor(projection ?: emptyArray()).apply {
            rows.forEach(::addRow)
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}

/**
 * Unconfined, not a `TestDispatcher`.
 *
 * A `TestDispatcher` built here carries its own scheduler and `runTest` builds another —
 * mixing the two is the "different schedulers" failure. The repository only steps off the
 * caller's thread to read a provider, with no delays to fast-forward, so running that
 * inline is both simpler and closer to what it does.
 */
private class TestDispatchers : DispatcherProvider {
    override val main: CoroutineDispatcher get() = Dispatchers.Unconfined
    override val io: CoroutineDispatcher get() = Dispatchers.Unconfined
    override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
    override val unconfined: CoroutineDispatcher get() = Dispatchers.Unconfined
}
