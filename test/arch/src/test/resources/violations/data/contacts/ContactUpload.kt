package fixtures

// VIOLATION FIXTURE - never compiled. Rule 9 must reject this.
import com.whatsappv2.domain.contacts.Contact
import java.net.HttpURLConnection
import okhttp3.OkHttpClient

class ContactUpload(private val client: OkHttpClient) {
    fun upload(contact: Contact, connection: HttpURLConnection) = Unit
}
