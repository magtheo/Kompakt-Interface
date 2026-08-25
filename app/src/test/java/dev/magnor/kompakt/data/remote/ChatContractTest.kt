package dev.magnor.kompakt.data.remote

import dev.magnor.kompakt.domain.MessageRole
import dev.magnor.kompakt.domain.MessageStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-009 wire contract for /v1/chats — pins the shapes implemented by
 * vault-coordinator V-050 (feat/V-050-chat): thread + message envelopes,
 * snake_case fields, send response carrying BOTH the acknowledged user
 * message and the server-generated assistant_message (D024: the phone
 * never talks to the LLM; it renders what the coordinator returns).
 */
class ChatContractTest {

    private lateinit var server: MockWebServer
    private lateinit var api: HttpApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = HttpApi(baseUrl = server.url("/").toString(), token = "test-token")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueJson(body: String) {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(body),
        )
    }

    @Test
    fun `threads list decodes chat envelope`() = runTest {
        enqueueJson(
            """
            {"chats":[{"id":"chat_1","title":"General","created_at":"2026-08-23T10:00:00Z",
             "updated_at":"2026-08-23T11:30:00Z","revision":3,"project_id":null,
             "is_temporary":false,"last_message_preview":"hello"}]}
            """.trimIndent(),
        )
        val repo = RemoteChatRepository(api)
        val threads = repo.observeThreads().first()
        assertEquals(1, threads.size)
        assertEquals("General", threads[0].title)
        assertEquals("hello", threads[0].lastMessagePreview)
        assertEquals(3, threads[0].revision)
        assertEquals("/v1/chats", server.takeRequest().path)
    }

    @Test
    fun `messages observe decodes messages envelope once per collection`() = runTest {
        enqueueJson(
            """
            {"messages":[{"id":"chatmsg:1","chat_id":"chat_1","role":"user","content":"hi",
             "created_at":"2026-08-23T10:00:00Z","updated_at":"2026-08-23T10:00:00Z",
             "revision":1,"status":"sent"},
            {"id":"chatmsg:2","chat_id":"chat_1","role":"assistant","content":"hello!",
             "created_at":"2026-08-23T10:00:05Z","updated_at":"2026-08-23T10:00:05Z",
             "revision":1,"status":"sent"}]}
            """.trimIndent(),
        )
        val repo = RemoteChatRepository(api)
        val messages = repo.observeMessages("chat_1").first()
        assertEquals(2, messages.size)
        assertEquals(MessageRole.USER, messages[0].role)
        assertEquals(MessageRole.ASSISTANT, messages[1].role)
        assertEquals(MessageStatus.SENT, messages[0].status)
        assertEquals("/v1/chats/chat_1/messages", server.takeRequest().path)
    }

    @Test
    fun `create thread posts request_id and title, decodes chat`() = runTest {
        enqueueJson(
            """
            {"chat":{"id":"chat_2","title":"New chat","created_at":"2026-08-23T12:00:00Z",
             "updated_at":"2026-08-23T12:00:00Z","revision":1,"project_id":null,
             "is_temporary":false,"last_message_preview":null}}
            """.trimIndent(),
        )
        val repo = RemoteChatRepository(api)
        val thread = repo.createThread(
            dev.magnor.kompakt.domain.ChatThreadDraft(title = "New chat"),
            requestId = "req-create-1",
        )
        assertEquals("chat_2", thread.id)
        assertNull(thread.lastMessagePreview)
        val req = server.takeRequest()
        assertEquals("/v1/chats", req.path)
        assertEquals("req-create-1", req.getHeader("X-Request-Id"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"request_id\":\"req-create-1\""))
        assertTrue(body.contains("\"title\":\"New chat\""))
    }

    @Test
    fun `send decodes user message and assistant_message pair`() = runTest {
        enqueueJson(
            """
            {"message":{"id":"chatmsg:u1","chat_id":"chat_1","role":"user","content":"ping",
             "created_at":"2026-08-23T12:00:00Z","updated_at":"2026-08-23T12:00:00Z",
             "revision":1,"status":"sent"},
             "assistant_message":{"id":"chatmsg:a1","chat_id":"chat_1","role":"assistant","content":"pong",
             "created_at":"2026-08-23T12:00:06Z","updated_at":"2026-08-23T12:00:06Z",
             "revision":1,"status":"sent"}}
            """.trimIndent(),
        )
        val repo = RemoteChatRepository(api)
        val exchange = repo.sendMessage("chat_1", "ping", "req-send-1")
        assertEquals("chatmsg:u1", exchange.user.id)
        assertEquals("pong", exchange.assistant?.content)
        val req = server.takeRequest()
        assertEquals("/v1/chats/chat_1/messages", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"request_id\":\"req-send-1\""))
        assertTrue(body.contains("\"text\":\"ping\""))
    }

    @Test
    fun `send tolerates missing assistant_message`() = runTest {
        enqueueJson(
            """
            {"message":{"id":"chatmsg:u2","chat_id":"chat_1","role":"user","content":"ping",
             "created_at":"2026-08-23T12:00:00Z","updated_at":"2026-08-23T12:00:00Z",
             "revision":1,"status":"sent"}}
            """.trimIndent(),
        )
        val repo = RemoteChatRepository(api)
        val exchange = repo.sendMessage("chat_1", "ping", "req-send-2")
        assertEquals("chatmsg:u2", exchange.user.id)
        assertNull(exchange.assistant)
    }

    // ── T-022d chat scopes (V-062/V-063) ────────────────────────────────

    @Test
    fun `topic registry decodes topics envelope`() = runTest {
        enqueueJson(
            """
            {"topics":[{"id":"evershift","label":"Evershift"},
                       {"id":"kodeverket","label":"KodeVerket"}]}
            """.trimIndent(),
        )
        val repo = RemoteTopicRepository(api)
        val topics = repo.observeTopics().first()
        assertEquals(2, topics.size)
        assertEquals("evershift", topics[0].id)
        assertEquals("KodeVerket", topics[1].label)
        assertEquals("/v1/chat/topics", server.takeRequest().path)
    }

    @Test
    fun `create with scope posts scope fields, general create omits them`() = runTest {
        enqueueJson(
            """
            {"chat":{"id":"chat_3","title":"New chat","created_at":"2026-08-23T12:00:00Z",
             "updated_at":"2026-08-23T12:00:00Z","revision":1,"project_id":null,
             "is_temporary":false,"last_message_preview":null,
             "scope_type":"topic","scope_ref":"evershift","scope_label":"Evershift"}}
            """.trimIndent(),
        )
        val repo = RemoteChatRepository(api)
        val thread = repo.createThread(
            dev.magnor.kompakt.domain.ChatThreadDraft(
                title = "New chat",
                scopeType = "topic",
                scopeRef = "evershift",
            ),
            requestId = "req-create-2",
        )
        assertEquals("topic", thread.scopeType)
        assertEquals("evershift", thread.scopeRef)
        assertEquals("Evershift", thread.scopeLabel)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"scope_type\":\"topic\""))
        assertTrue(body.contains("\"scope_ref\":\"evershift\""))

        // General chat: scope fields must be ABSENT (explicitNulls=false).
        enqueueJson(
            """
            {"chat":{"id":"chat_4","title":"New chat","created_at":"2026-08-23T12:00:00Z",
             "updated_at":"2026-08-23T12:00:00Z","revision":1,"project_id":null,
             "is_temporary":false,"last_message_preview":null}}
            """.trimIndent(),
        )
        repo.createThread(dev.magnor.kompakt.domain.ChatThreadDraft(title = "New chat"), "req-create-3")
        val generalBody = server.takeRequest().body.readUtf8()
        assertTrue(!generalBody.contains("scope_type"))
        assertTrue(!generalBody.contains("scope_ref"))
    }

    @Test
    fun `setScope posts scope pair and decodes re-scoped thread`() = runTest {
        enqueueJson(
            """
            {"chat":{"id":"chat_1","title":"New chat","created_at":"2026-08-23T12:00:00Z",
             "updated_at":"2026-08-23T12:01:00Z","revision":2,"project_id":null,
             "is_temporary":false,"last_message_preview":null,
             "scope_type":"topic","scope_ref":"evershift","scope_label":"Evershift"}}
            """.trimIndent(),
        )
        val repo = RemoteChatRepository(api)
        val thread = repo.setScope("chat_1", "topic", "evershift", "req-scope-1")
        assertEquals("evershift", thread.scopeRef)
        assertEquals(2, thread.revision)
        val req = server.takeRequest()
        assertEquals("/v1/chats/chat_1/scope", req.path)
        assertEquals("req-scope-1", req.getHeader("X-Request-Id"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"scope_type\":\"topic\""))
        assertTrue(body.contains("\"scope_ref\":\"evershift\""))
    }

    @Test
    fun `setScope with nulls clears the scope and omits the fields`() = runTest {
        enqueueJson(
            """
            {"chat":{"id":"chat_1","title":"New chat","created_at":"2026-08-23T12:00:00Z",
             "updated_at":"2026-08-23T12:02:00Z","revision":3,"project_id":null,
             "is_temporary":false,"last_message_preview":null}}
            """.trimIndent(),
        )
        val repo = RemoteChatRepository(api)
        val thread = repo.setScope("chat_1", null, null, "req-scope-2")
        assertNull(thread.scopeType)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(!body.contains("scope_type"))
    }

    @Test
    fun `send decodes proposed_topic on an unscoped thread`() = runTest {
        enqueueJson(
            """
            {"message":{"id":"chatmsg:u3","chat_id":"chat_1","role":"user","content":"greedy meshing?",
             "created_at":"2026-08-23T12:00:00Z","updated_at":"2026-08-23T12:00:00Z",
             "revision":1,"status":"sent"},
             "assistant_message":{"id":"chatmsg:a3","chat_id":"chat_1","role":"assistant","content":"…",
             "created_at":"2026-08-23T12:00:06Z","updated_at":"2026-08-23T12:00:06Z",
             "revision":1,"status":"sent"},
             "proposed_topic":{"id":"evershift","label":"Evershift"}}
            """.trimIndent(),
        )
        val repo = RemoteChatRepository(api)
        val exchange = repo.sendMessage("chat_1", "greedy meshing?", "req-send-3")
        assertEquals("evershift", exchange.proposedTopic?.id)
        assertEquals("Evershift", exchange.proposedTopic?.label)
    }

    @Test
    fun `send decodes workspace outcome state`() = runTest {
        enqueueJson(
            """
            {"message":{"id":"chatmsg:u4","chat_id":"chat_1","role":"user","content":"add a readme",
             "created_at":"2026-08-23T12:00:00Z","updated_at":"2026-08-23T12:00:00Z",
             "revision":1,"status":"sent"},
             "assistant_message":{"id":"chatmsg:a4","chat_id":"chat_1","role":"assistant","content":"done",
             "created_at":"2026-08-23T12:00:11Z","updated_at":"2026-08-23T12:00:11Z",
             "revision":1,"status":"sent"},
             "workspace":{"state":"settled","execution_id":"exec_7","committed":true}}
            """.trimIndent(),
        )
        val repo = RemoteChatRepository(api)
        val exchange = repo.sendMessage("chat_1", "add a readme", "req-send-4")
        assertEquals("settled", exchange.workspaceState)
    }

    @Test
    fun `thread wire decodes scope fields and pending_reply`() = runTest {
        enqueueJson(
            """
            {"chats":[{"id":"chat_5","title":"New chat","created_at":"2026-08-23T12:00:00Z",
             "updated_at":"2026-08-23T12:05:00Z","revision":4,"project_id":null,
             "is_temporary":false,"last_message_preview":"add a readme",
             "scope_type":"workspace","scope_ref":"roblox-toolkit","scope_label":"Roblox Toolkit",
             "pending_reply":true}]}
            """.trimIndent(),
        )
        val repo = RemoteChatRepository(api)
        val threads = repo.observeThreads().first()
        assertEquals("workspace", threads[0].scopeType)
        assertEquals("roblox-toolkit", threads[0].scopeRef)
        assertEquals("Roblox Toolkit", threads[0].scopeLabel)
        assertTrue(threads[0].pendingReply)
    }
}
