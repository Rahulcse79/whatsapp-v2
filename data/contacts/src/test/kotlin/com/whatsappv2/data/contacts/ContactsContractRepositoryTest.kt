package com.whatsappv2.data.contacts

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import com.whatsappv2.core.common.dispatcher.DispatcherProvider
import com.whatsappv2.core.common.logging.NoOpLogger
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.model.SipUri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Reading the address book, and not reading it (Task 49).
 *
 * Robolectric's contacts provider rather than a fake `ContentResolver`: the queries are
 * the thing under test, and a fake resolver would only prove that the strings this class
 * builds match the strings the test expects.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [CONTACTS_ROBOLECTRIC_SDK])
class ContactsContractRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val dispatchers = TestDispatchers(StandardTestDispatcher())

    private fun repository() = ContactsContractRepository(context, dispatchers, NoOpLogger)

    @Before
    fun grantPermission() {
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS)
    }

    @Test
    fun `a SIP address the address book knows resolves to that person`() = runTest {
        givenSipContact(name = "Bob Smith", sipAddress = "bob@sip.example.com")

        val contact = repository().resolve(uri("sip:bob@sip.example.com"))

        assertEquals("Bob Smith", contact?.displayName)
    }

    @Test
    fun `an address nobody has resolves to nothing, which is an ordinary answer`() = runTest {
        val contact = repository().resolve(uri("sip:nobody@sip.example.com"))

        assertNull(contact)
    }

    @Test
    fun `without permission it answers nothing rather than throwing`() = runTest {
        // Task 49's second done-when: declining READ_CONTACTS leaves the app working, and
        // every caller treats "not known" and "not allowed to look" the same way.
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.READ_CONTACTS)
        givenSipContact(name = "Bob Smith", sipAddress = "bob@sip.example.com")

        assertNull(repository().resolve(uri("sip:bob@sip.example.com")))
    }

    @Test
    fun `a match with no name is not a match`() = runTest {
        // An empty line where the caller's name goes is worse than showing the address.
        givenSipContact(name = "", sipAddress = "blank@sip.example.com")

        assertNull(repository().resolve(uri("sip:blank@sip.example.com")))
    }

    @Test
    fun `the same caller is looked up once while a call is ringing`() = runTest {
        givenSipContact(name = "Bob Smith", sipAddress = "bob@sip.example.com")
        val repository = repository()
        val address = uri("sip:bob@sip.example.com")

        assertEquals("Bob Smith", repository.resolve(address)?.displayName)
        // Removed behind the cache: a second provider read would find nothing, so a
        // second answer of "Bob Smith" is the cache answering.
        context.contentResolver.delete(ContactsContract.Data.CONTENT_URI, null, null)

        assertEquals("Bob Smith", repository.resolve(address)?.displayName)
    }

    // ---------------------------------------------------------------- fixture

    private fun givenSipContact(name: String, sipAddress: String) {
        val values = ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.SipAddress.CONTENT_ITEM_TYPE)
            put(ContactsContract.CommonDataKinds.SipAddress.SIP_ADDRESS, sipAddress)
            put(ContactsContract.Contacts.DISPLAY_NAME, name)
        }
        context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, values)
    }

    private fun uri(value: String): SipUri = SipUri.parse(value).getOrNull()!!
}

/**
 * Every dispatcher is the test's, so a provider read is on the test scheduler rather than
 * on a real IO thread the test cannot wait for.
 */
private class TestDispatchers(private val dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val main: CoroutineDispatcher get() = dispatcher
    override val io: CoroutineDispatcher get() = dispatcher
    override val default: CoroutineDispatcher get() = dispatcher
    override val unconfined: CoroutineDispatcher get() = dispatcher
}
