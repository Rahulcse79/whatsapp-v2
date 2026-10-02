package com.whatsappv2.data.chat

import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.repository.ChatContactRepository
import com.whatsappv2.domain.repository.ChatSessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the two caches a signed-in user needs warm: the conversation list and the directory.
 *
 * ## The bug this closes
 *
 * `ChatRepositoryImpl.observeMessages` has three reasons to re-read — a local change, a
 * server event, and the first subscription — and its KDoc says why the server's is the one
 * that is easy to forget. `observeConversations` had **none**: it is `ChatMemoryCache`'s
 * StateFlow, and the only thing that ever wrote it was an explicit `refreshConversations()`,
 * which `ChatsViewModel` calls on a manual retry and when the socket comes up.
 *
 * So nothing refreshed the list while the app was running. Send a message to somebody,
 * press Back, and the Chats list did not show it — the row's preview was stale, and a
 * conversation opened for the first time was not there at all. Reported from a device on
 * 2 Oct 2026. The thread was always right, which is what made it look like a list that
 * "needed opening to work": opening the thread is what re-read the messages.
 *
 * ## The directory had no loader at all
 *
 * `ChatContactRepository` is observed by three screens and was refreshed by exactly one —
 * the "New conversation" picker. Everywhere else read a map that stayed **empty until that
 * screen had been opened**, which is subtle because it works perfectly as soon as you go
 * looking for it.
 *
 * Two things depend on it, and both were quietly broken on a fresh start: a Chats row read
 * `mcx8101` instead of `8101 (mcx8101)`, and — the one that matters — the thread's call
 * button fell back to the conversation handle, so it dialled `mcx8101`, which no PBX can
 * route. The directory is the only thing that knows `mcx8101` is on `8101`.
 *
 * ## Why it is a separate object started with the application's scope
 *
 * Because one loop for the whole process is the point. Driving this from inside
 * `observeConversations` would start a refresh loop **per collector**, and there are already
 * two — the Chats list and the thread, which reads the same flow for its title and the
 * extension to dial — so every event would fetch the list twice.
 *
 * It cannot live in [ChatEngineLifecycle], which has the scope and the same event stream but
 * would need the repository to do the fetching, and the repository already depends on the
 * engine. That is a Dagger cycle. This is a leaf instead: it depends on both and nothing
 * depends on it.
 *
 * `start(scope)` rather than an injected scope, because `@ApplicationScope` is declared in
 * `:app` and this module cannot see it — the same reason [ChatEngineLifecycle.start] takes
 * one.
 *
 * ## [conflate] rather than a debounce
 *
 * A fetch is in flight for as long as the server takes, and events can land during it.
 * Conflating collapses everything that arrives in that window into exactly one more fetch,
 * which is the right answer with no interval to tune and nothing to make a test wait for.
 * A burst of twenty incoming messages costs two requests, not twenty.
 */
@Singleton
class ChatDataSync @Inject internal constructor(
    private val repository: ChatRepositoryImpl,
    private val contacts: ChatContactRepository,
    private val sessions: ChatSessionRepository,
    private val bus: ChatEventBus,
    private val logger: Logger,
) {

    private var started = false

    /**
     * Begins following the event stream. Called once, from `SipApplication.onCreate`.
     *
     * Idempotent, like the engine's: a second call would start a second loop and double
     * every fetch.
     */
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true

        scope.launch {
            bus.events()
                .filter(ChatEvent::changesTheConversationList)
                .conflate()
                .collect { refresh() }
        }

        // The directory, once per signed-in identity. Keyed on the user rather than on the
        // session object so a token refresh does not re-fetch, and `null` on sign-out so the
        // next person's directory is fetched rather than the previous one's being kept.
        scope.launch {
            sessions.observeSession()
                .map { it?.userId }
                .distinctUntilChanged()
                .collect { user -> if (user != null) loadDirectory() }
        }
    }

    private suspend fun loadDirectory() {
        // Unfiltered: this is the whole directory for the joins, not a screen's search.
        when (val outcome = contacts.refresh(query = null)) {
            is Outcome.Success -> Unit
            is Outcome.Failure -> logger.debug(TAG, "A background directory refresh failed: ${outcome.error}")
        }
    }

    private suspend fun refresh() {
        // Logged and dropped. A failed top-up is not something to report anywhere: the list
        // on screen is the last good one, the user did not ask for this, and the next event
        // or the next manual refresh tries again. Reporting it would put an error banner
        // over a working list because a background fetch lost a race with the network.
        when (val outcome = repository.refreshConversations()) {
            is Outcome.Success -> Unit
            is Outcome.Failure -> logger.debug(TAG, "A background conversation refresh failed: ${outcome.error}")
        }
    }

    private companion object {
        const val TAG = "ChatDataSync"
    }
}

/**
 * Whether an event changes what the conversation **list** should show.
 *
 * [ChatEvent.Received] can add a whole row — somebody messaging for the first time — as well
 * as change a preview and the ordering. [ChatEvent.Acknowledged] is this device's own
 * message coming back with its id and sequence number, which is what makes a chat you just
 * started appear in your own list.
 *
 * [ChatEvent.Connected] is deliberately **not** here. `ChatsViewModel` already refreshes on
 * it, and with its loading state attached; adding it would mean two fetches per connection
 * and a race between them over which answer lands in the cache last.
 */
private fun ChatEvent.changesTheConversationList(): Boolean = when (this) {
    is ChatEvent.Received -> true
    is ChatEvent.Acknowledged -> true
    ChatEvent.Connected -> false
    is ChatEvent.Disconnected -> false
}
