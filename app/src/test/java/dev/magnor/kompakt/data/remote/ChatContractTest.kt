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
}
