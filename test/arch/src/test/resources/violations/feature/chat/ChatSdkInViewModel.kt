package fixtures

// VIOLATION FIXTURE - never compiled. Rule 13 must reject this.
//
// This is the exact leak the rule exists to stop, written the way it would really arrive:
// ChatSdk is a process-lifetime singleton reachable by a static get(), so registering a
// listener from a ViewModel WORKS. It also leaks that ViewModel for the life of the app
// (finding 1.3-7), and it puts a vendored SDK type in the signature of a screen's state,
// which is what turns the next SDK swap from a one-module rewrite into an app rewrite.
import com.chatserver.sdk.ChatListener
import com.chatserver.sdk.ChatSdk
import com.chatserver.sdk.model.Message

class ChatSdkInViewModel : ChatListener {
    fun start() = ChatSdk.get().addListener(this)

    override fun onMessage(message: Message) = Unit
}
