package fixtures

// VIOLATION FIXTURE - never compiled. Rule 9 must reject this.
//
// The chat SDK is on the egress list too, and this is why: chat is exactly where somebody
// will one day want to match the device address book against chat users. That is a bulk
// upload of other people's personal data wearing a feature's clothes.
import com.chatserver.sdk.ChatSdk
import com.whatsappv2.domain.contacts.Contact
import java.net.HttpURLConnection
import okhttp3.OkHttpClient

class ContactUpload(private val client: OkHttpClient) {
    fun upload(contact: Contact, connection: HttpURLConnection) = Unit

    fun match(contact: Contact) = ChatSdk.get().openDirectConversation(contact.displayName) {}
}
