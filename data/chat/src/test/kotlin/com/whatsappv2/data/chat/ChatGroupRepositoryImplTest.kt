package com.whatsappv2.data.chat

import com.google.gson.Gson
import com.whatsappv2.core.common.dispatcher.DispatcherProvider
import com.whatsappv2.core.common.logging.NoOpLogger
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.errorOrNull
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.data.chat.net.ChatIdentitySource
import com.whatsappv2.data.chat.net.ChatNodeClientFactory
import com.whatsappv2.domain.chat.ChatConversation
import com.whatsappv2.domain.chat.ChatFailure
import com.whatsappv2.domain.chat.ChatGroup
import com.whatsappv2.domain.chat.ChatSession
import com.whatsappv2.domain.chat.ConversationId
import com.whatsappv2.domain.chat.CoralServerUrl
import com.whatsappv2.domain.chat.GroupRole
import com.whatsappv2.domain.repository.ChatGroupError
import com.whatsappv2.domain.testing.FakeChatRepository
import com.whatsappv2.domain.testing.FakeChatSessionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import javax.inject.Provider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Groups against a real HTTP server.
 *
 * `MockWebServer` speaks plain HTTP and `CoralServerUrl` accepts an `http://` origin, so the
 * whole path is driven for real: the client factory, the identity headers, Retrofit's
 * relative paths and the Gson shapes. A stubbed `ChatNodeApi` would prove none of those — and
 * every one of them has a way of being quietly wrong (a leading slash drops `/chat/`, a
 * missing header makes chat-node provision a second identity).
 *
 * What is NOT faked is the thing the production KDoc promises: creating a group is several
 * requests and is **not atomic**. The tests below pin that promise, because it is the part a
 * future change is most likely to "tidy up" into a lie.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatGroupRepositoryImplTest {

    private val server = MockWebServer()
    private val dispatcher = UnconfinedTestDispatcher()

    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher get() = dispatcher
        override val io: CoroutineDispatcher get() = dispatcher
        override val default: CoroutineDispatcher get() = dispatcher
        override val unconfined: CoroutineDispatcher get() = Dispatchers.Unconfined
    }

    /** The origin MockWebServer is listening on, parsed the way a stored URL is. */
    private val url: CoralServerUrl
        get() = CoralServerUrl.parse(server.url("/").toString().removeSuffix("/")).getOrNull()!!

    private val identity = object : ChatIdentitySource {
        override fun currentDeviceKey(): String? = "mcx8101"
        override fun currentDeviceId(): String? = DEVICE_ID
    }

    private val chat = FakeChatRepository()

    private fun repository(signedIn: Boolean = true) = ChatGroupRepositoryImpl(
        // The URL is read off MockWebServer, so the port is whatever the OS handed it.
        sessions = FakeChatSessionRepository(initialUrl = url)
            .also { if (signedIn) it.givenSignedIn(session()) },
        chat = chat,
        clients = ChatNodeClientFactory(Gson(), Provider { identity }),
        dispatchers = dispatchers,
        logger = NoOpLogger,
    )

    private fun session() = ChatSession(
        userId = "mcx8101",
        displayName = "8101",
        token = Secret("a-token"),
        expiresAtMs = null,
        deviceId = DEVICE_ID,
    )

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    /** Every request the server received, in order. */
    private fun requests(count: Int): List<RecordedRequest> = List(count) { server.takeRequest() }

    @Test
    fun `the group endpoints sit under the chat context path, not at the root`() = runTest(dispatcher) {
        server.enqueue(json(200, """[{"conversationId":"g1","name":"Ops","createdBy":"owner-ulid"}]"""))
        server.enqueue(json(200, """[{"userId":"owner-ulid","role":"OWNER"}]"""))

        repository().refresh()

        // A LEADING SLASH on the Retrofit path would make this `/api/groups` and drop the
        // base URL's `/chat/` — the same trap the SDK's own ApiService documents.
        assertEquals("/chat/api/groups", requests(1).single().path)
    }

    @Test
    fun `every request carries the same guest identity the SDK sends`() = runTest(dispatcher) {
        server.enqueue(json(200, """[]"""))

        repository().refresh()

        val request = requests(1).single()
        // A different identity here would make chat-node provision a SECOND chat user for one
        // signed-in person, which is the phantom-identity bug this codebase has had once.
        assertEquals("mcx8101", request.getHeader("X-Device-Key"))
        assertEquals(DEVICE_ID, request.getHeader("X-Device-Id"))
    }

    @Test
    fun `a refresh joins each group to its own roster, because there is no endpoint that does`() =
        runTest(dispatcher) {
            server.enqueue(
                json(
                    200,
                    """[{"conversationId":"g1","name":"Ops","createdBy":"owner-ulid"},
                        {"conversationId":"g2","name":"Release","createdBy":"owner-ulid"}]""",
                ),
            )
            server.enqueue(json(200, """[{"userId":"owner-ulid","role":"OWNER"},{"userId":"u2","role":"MEMBER"}]"""))
            server.enqueue(json(200, """[{"userId":"owner-ulid","role":"OWNER"}]"""))

            val repository = repository()
            assertTrue(repository.refresh() is Outcome.Success)

            val groups = repository.observeGroups().first()
            assertEquals(setOf(ConversationId("g1"), ConversationId("g2")), groups.keys)
            assertEquals("Ops", groups[ConversationId("g1")]?.name)
            assertEquals(2, groups[ConversationId("g1")]?.size)
            assertEquals(GroupRole.OWNER, groups[ConversationId("g1")]?.members?.first()?.role)
            // Two people is callable; one is not, because there is nobody to call.
            assertTrue(groups[ConversationId("g1")]!!.isCallable)
            assertEquals(false, groups[ConversationId("g2")]!!.isCallable)
        }

    @Test
    fun `a group with no id is dropped rather than keyed on an empty string`() = runTest(dispatcher) {
        server.enqueue(json(200, """[{"conversationId":"","name":"Nameless","createdBy":null}]"""))

        val repository = repository()
        repository.refresh()

        // Keying on "" would collide every malformed row onto one entry and show it as a chat.
        assertTrue(repository.observeGroups().first().isEmpty())
    }

    @Test
    fun `creating adds each member by the ULID a direct conversation reveals`() = runTest(dispatcher) {
        // Pre-seeded so the designation and the ULID are DIFFERENT values. The fake would
        // otherwise echo the designation back and a repository that sent the handle — which
        // chat-node 500s on — would pass.
        chat.givenConversation(directConversation(id = "conv-mcx8102", ulid = "01ULIDFOR8102"))

        server.enqueue(json(201, """{"conversationId":"g9","name":"Ops","createdBy":"owner-ulid"}"""))
        server.enqueue(json(201, """{"userId":"01ULIDFOR8102","role":"MEMBER"}"""))
        server.enqueue(json(200, ROSTER_OF_TWO))

        val created = repository().create(name = "Ops", memberUsernames = listOf("mcx8102"))

        val group = created.getOrNull()
        assertNotNull(group)
        assertEquals(ConversationId("g9"), group.id)
        assertEquals(2, group.size)

        val sent = requests(3)
        assertEquals("""{"name":"Ops"}""", sent[0].body.readUtf8())
        // The ULID, not `mcx8102`.
        assertEquals("""{"userId":"01ULIDFOR8102"}""", sent[1].body.readUtf8())
        assertEquals("/chat/api/groups/g9/members", sent[2].path)
    }

    @Test
    fun `a member who cannot be resolved leaves a smaller group, not a failure`() = runTest(dispatcher) {
        chat.givenConversation(directConversation(id = "conv-mcx8102", ulid = "01ULIDFOR8102"))

        server.enqueue(json(201, """{"conversationId":"g9","name":"Ops","createdBy":"owner-ulid"}"""))
        server.enqueue(json(201, """{"userId":"01ULIDFOR8102","role":"MEMBER"}"""))
        // The second member's POST is refused by the server.
        server.enqueue(json(500, """{"error":"internal"}"""))
        server.enqueue(json(200, ROSTER_OF_TWO))

        val created = repository().create(name = "Ops", memberUsernames = listOf("mcx8102", "mcx8103"))

        // The promise in the KDoc: the group EXISTS, so it is returned with however many
        // people are actually in it. Reporting a failure would leave the user unsure whether
        // anything was created, and deleting it would throw away a conversation the other
        // member may already have been notified of.
        val group = created.getOrNull()
        assertNotNull(group, "a partial create was reported as a failure")
        assertEquals(2, group.size, "the group should carry the members that did land")
    }

    @Test
    fun `a create the server answers without an id is a failure, because there is nothing to open`() =
        runTest(dispatcher) {
            server.enqueue(json(201, """{"conversationId":null,"name":"Ops","createdBy":null}"""))

            val created = repository().create(name = "Ops", memberUsernames = listOf("mcx8102"))

            val error = created.errorOrNull()
            assertTrue(error is ChatGroupError.Failed, "expected a Failed, got $error")
            assertTrue(error.cause is ChatFailure.Unknown)
        }

    @Test
    fun `the cap is refused before a single request leaves, so no group is half-made`() = runTest(dispatcher) {
        val created = repository().create(
            name = "Too big",
            memberUsernames = List(ChatGroup.MAX_MEMBERS) { "mcx810$it" },
        )

        val error = created.errorOrNull()
        assertEquals(ChatGroupError.TooManyMembers(ChatGroup.MAX_MEMBERS + 1, ChatGroup.MAX_MEMBERS), error)
        // Nothing reached the wire: a group refused after POST /groups would already exist.
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `duplicates and blanks are dropped before they count towards the cap`() = runTest(dispatcher) {
        server.enqueue(json(201, """{"conversationId":"g9","name":"Ops","createdBy":"owner-ulid"}"""))
        server.enqueue(json(201, """{"userId":"mcx8102","role":"MEMBER"}"""))
        server.enqueue(json(200, """[{"userId":"owner-ulid","role":"OWNER"}]"""))

        val created = repository().create(
            name = "Ops",
            // Four entries, one real person: trimmed, de-duplicated, blank-stripped.
            memberUsernames = listOf("mcx8102", " mcx8102 ", "", "   "),
        )

        assertTrue(created is Outcome.Success, "one person written four ways was read as four people")
        assertEquals("""{"name":"Ops"}""", requests(1).single().body.readUtf8())
    }

    @Test
    fun `a blank name and an empty roster are refused by name, so the screen can say which`() =
        runTest(dispatcher) {
            assertEquals(ChatGroupError.NoName, repository().create("   ", listOf("mcx8102")).errorOrNull())
            assertEquals(ChatGroupError.NoMembers, repository().create("Ops", emptyList()).errorOrNull())
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `signed out, a group request is NotConfigured rather than an unauthenticated call`() =
        runTest(dispatcher) {
            val repository = repository(signedIn = false)

            assertEquals(ChatFailure.NotConfigured, repository.refresh().errorOrNull())
            assertEquals(0, server.requestCount, "a signed-out request reached the server")
        }

    @Test
    fun `deleting drops the group from the list it published`() = runTest(dispatcher) {
        server.enqueue(json(200, """[{"conversationId":"g1","name":"Ops","createdBy":"owner-ulid"}]"""))
        server.enqueue(json(200, """[{"userId":"owner-ulid","role":"OWNER"}]"""))
        server.enqueue(MockResponse().setResponseCode(204))

        val repository = repository()
        repository.refresh()
        assertTrue(repository.delete(ConversationId("g1")) is Outcome.Success)

        assertTrue(repository.observeGroups().first().isEmpty())
        val sent = requests(3)
        assertEquals("DELETE", sent[2].method)
        assertEquals("/chat/api/groups/g1", sent[2].path)
    }

    @Test
    fun `a failed delete leaves the group where it was`() = runTest(dispatcher) {
        server.enqueue(json(200, """[{"conversationId":"g1","name":"Ops","createdBy":"owner-ulid"}]"""))
        server.enqueue(json(200, """[{"userId":"owner-ulid","role":"OWNER"}]"""))
        server.enqueue(json(500, """{"error":"internal"}"""))

        val repository = repository()
        repository.refresh()
        val outcome = repository.delete(ConversationId("g1"))

        assertNull(outcome.getOrNull(), "a 500 was reported as a successful delete")
        // Removing it locally on a failed DELETE would hide a group that is still there.
        assertEquals(1, repository.observeGroups().first().size)
    }

    private fun directConversation(id: String, ulid: String) = ChatConversation(
        id = ConversationId(id),
        type = "DIRECT",
        createdAtMs = 0,
        muted = false,
        archived = false,
        pinned = false,
        // chat-node's internal id, which is what `POST /members` wants.
        otherUserId = ulid,
        otherUserContactIdentifier = "mcx8102",
        lastMessageBody = null,
        lastMessageType = null,
        lastMessageSenderId = null,
        lastMessageAtMs = 0,
        unreadCount = 0,
    )

    private companion object {
        /** 32 upper hex, the shape the Coral platform's sample uses. */
        const val DEVICE_ID = "0123456789ABCDEF0123456789ABCDEF"

        /** The owner plus one member — a group that is callable, and the shape `GET /members` returns. */
        val ROSTER_OF_TWO = """
            [{"userId":"owner-ulid","role":"OWNER"},{"userId":"01ULIDFOR8102","role":"MEMBER"}]
        """.trimIndent()
    }

    @Test
    fun `a roster member is given the designation the conversation list already knows`() = runTest(dispatcher) {
        // The reverse lookup: a direct summary carries BOTH the ULID and the designation, so
        // the list this device holds resolves a roster that otherwise reads as bare ULIDs.
        chat.givenConversation(directConversation(id = "conv-mcx8102", ulid = "01ULIDFOR8102"))

        server.enqueue(json(200, """[{"conversationId":"g1","name":"Ops","createdBy":"owner-ulid"}]"""))
        server.enqueue(json(200, ROSTER_OF_TWO))

        val repository = repository()
        repository.refresh()

        val members = repository.observeGroups().first().getValue(ConversationId("g1")).members
        assertEquals("mcx8102", members.first { it.userId == "01ULIDFOR8102" }.username)
        // The owner is not in this device's conversation list — you have no direct chat with
        // yourself — so they stay null rather than borrowing somebody else's name.
        assertNull(members.first { it.userId == "owner-ulid" }.username)
    }
}
