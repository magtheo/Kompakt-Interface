package dev.magnor.kompakt.data.remote

import dev.magnor.kompakt.domain.ForbiddenException
import dev.magnor.kompakt.domain.OfflineException
import dev.magnor.kompakt.domain.ProtocolNegotiation
import dev.magnor.kompakt.domain.ProtocolVerdict
import dev.magnor.kompakt.domain.RepositoryException
import dev.magnor.kompakt.domain.RevisionConflictException
import dev.magnor.kompakt.domain.ServerUnavailableException
import dev.magnor.kompakt.domain.TaskStatus
import dev.magnor.kompakt.domain.UnauthorizedException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Wire-contract tests against a MockWebServer speaking the coordinator's
 * /v1/ shapes (verified live against :8650 in T-004). These pin the
 * client↔server agreement: snake_case fields, envelope keys, error
 * mapping, forward-compat tolerance (protocol §9).
 */
class RemoteContractTest {

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
    fun `capabilities decode and negotiation ok`() = runTest {
        enqueueJson(
            """
            {"server_protocol":1,"minimum_client_protocol":1,
             "features":{"today":true,"chat":false,"agents":false,
                         "projects":true,"areas":true,"tasks":true,
                         "notes":false,"inbox":true,"offline_capture":false}}
            """.trimIndent(),
        )
        val sync = RemoteSyncRepository(api)
        val caps = sync.capabilities()
        assertEquals(1, caps.serverProtocol)
        assertTrue(caps.supports("today"))
        assertTrue(!caps.supports("chat"))
        assertEquals(
            ProtocolVerdict.Ok,
            ProtocolNegotiation.evaluate(
                clientProtocol = 1, minimumServerProtocol = 1, caps = caps,
            ),
        )
        val req = server.takeRequest()
        assertEquals("/v1/capabilities", req.path)
        assertEquals("Bearer test-token", req.getHeader("Authorization"))
    }

    @Test
    fun `capabilities decode the V-069 device grant block`() = runTest {
        // What /v1/capabilities now serves a device principal: the global
        // contract plus the caller's own granted set. Absent granted
        // (admin / pre-V-069 server) decodes to null — pinned by the
        // negotiation test above relying on the default.
        enqueueJson(
            """
            {"server_protocol":1,"minimum_client_protocol":1,
             "features":{"today":true,"chat":true,"agents":true,"projects":true,
                         "areas":true,"tasks":true,"notes":true,"inbox":true,
                         "offline_capture":true},
             "granted":{"device_id":"pixel-4a-5g",
                        "capabilities":["project.read","task.read","note.read","inbox.read"]}}
            """.trimIndent(),
        )
        val caps = RemoteSyncRepository(api).capabilities()
        assertEquals("pixel-4a-5g", caps.granted?.deviceId)
        assertTrue("granted cap passes", caps.grants("note.read"))
        assertTrue("granted cap passes", caps.grants("project.read"))
        assertFalse("missing cap fails", caps.grants("chat.read"))
    }

    @Test
    fun `negotiation hard-stops when client too old`() = runTest {
        enqueueJson(
            """{"server_protocol":3,"minimum_client_protocol":2,"features":{}}""",
        )
        val caps = RemoteSyncRepository(api).capabilities()
        val verdict = ProtocolNegotiation.evaluate(
            clientProtocol = 1, minimumServerProtocol = 1, caps = caps,
        )
        assertTrue(verdict is ProtocolVerdict.ClientTooOld)
        assertEquals(2, (verdict as ProtocolVerdict.ClientTooOld).minimumClientProtocol)
    }

    @Test
    fun `task decode honors snake_case wire and unknown fields`() = runTest {
        // Exactly the shape /v1/tasks serves (incl. nulls + a future field).
        enqueueJson(
            """
            {"tasks":[{
              "id":"repo:dev-server:task:T-014",
              "title":"Add business logic checks",
              "status":"open",
              "due_at":null,
              "project_id":"machine:project:dev-server",
              "area_id":null,
              "notes":"some file refs",
              "source_type":null,"source_id":null,
              "revision":1,
              "updated_at":"2026-08-15T19:39:25.979330Z",
              "future_field":{"nested":true}
            }],"meta":{"page":1}}
            """.trimIndent(),
        )
        val tasks = RemoteTaskRepository(api)
        val list = tasks.observeTasks().first()
        assertEquals(1, list.size)
        val t = list[0]
        assertEquals("repo:dev-server:task:T-014", t.id)
        assertEquals(TaskStatus.OPEN, t.status)
        assertEquals("machine:project:dev-server", t.projectId)
        assertNull(t.dueAt)
        assertEquals(1L, t.revision)
    }

    @Test
    fun `unknown enum value decodes to UNKNOWN not crash`() = runTest {
        enqueueJson(
            """
            {"tasks":[{"id":"t1","title":"x","status":"weird_future_status",
             "revision":1,"updated_at":"2026-08-15T19:39:25Z"}]}
            """.trimIndent(),
        )
        val list = RemoteTaskRepository(api).observeTasks().first()
        assertEquals(TaskStatus.UNKNOWN, list[0].status)
    }

    @Test
    fun `today projection decodes with view fields`() = runTest {
        enqueueJson(
            """
            {"date":"2026-08-22","events":[
               {"id":"evt_1","title":"Philosophy","start_at":"2026-08-22T09:00:00Z",
                "end_at":"2026-08-22T10:00:00Z","location":null}],
             "tasks":[],"attention":[
               {"id":"alert:host_down:laptop","source_type":null,"source_id":null,
                "title":"laptop unreachable","summary":"host_down",
                "timestamp":"2026-08-22T18:08:46Z","priority":"high","actions":[],
                "revision":1,"updated_at":"2026-08-22T18:08:46Z"}],
             "agent_activity":[],"recent_note":null}
            """.trimIndent(),
        )
        val today = RemoteTodayRepository(api).today()
        assertEquals(1, today.events.size)
        assertEquals("Philosophy", today.events[0].title)
        assertEquals(1, today.attention.size)
        assertEquals("high", today.attention[0].priority.wire)
        assertNull(today.recentNote)
    }

    @Test
    fun `empty list envelopes decode to empty lists`() = runTest {
        enqueueJson("""{"chats":[]}""")
        val chats = RemoteChatRepository(api).observeThreads().first()
        assertTrue(chats.isEmpty())
    }

    @Test
    fun `changes endpoint decodes caught-up page`() = runTest {
        enqueueJson("""{"next_cursor":null,"changes":[]}""")
        val page = RemoteSyncRepository(api).changesSince("chg_0")
        assertNull(page.nextCursor)
        assertTrue(page.changes.isEmpty())
        assertEquals("/v1/changes?since=chg_0", server.takeRequest().path)
    }

    @Test
    fun `401 maps to UnauthorizedException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"unauthorized"}"""))
        var thrown: Exception? = null
        try {
            RemoteSyncRepository(api).capabilities()
        } catch (e: Exception) {
            thrown = e
        }
        assertTrue(thrown is UnauthorizedException)
    }

    @Test
    fun `403 with capability detail maps to ForbiddenException naming the grant`() = runTest {
        // require_capability shape (src/auth.py): the exact Aug-27 body.
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"detail":"capability 'project.read' required"}"""),
        )
        var thrown: Exception? = null
        try {
            RemoteTaskRepository(api).getTask("t1")
        } catch (e: Exception) {
            thrown = e
        }
        assertTrue(thrown is ForbiddenException)
        assertEquals("project.read", (thrown as ForbiddenException).capability)
    }

    @Test
    fun `403 with other detail keeps capability null`() = runTest {
        // Not a require_capability 403 — must not guess a grant name.
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"nope"}"""))
        var thrown: Exception? = null
        try {
            RemoteTaskRepository(api).getTask("t1")
        } catch (e: Exception) {
            thrown = e
        }
        assertTrue(thrown is ForbiddenException)
        assertNull((thrown as ForbiddenException).capability)
    }

    @Test
    fun `409 maps to RevisionConflictException with current revision`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"detail":"stale revision","current_revision":8}"""),
        )
        var thrown: Exception? = null
        try {
            RemoteTaskRepository(api).getTask("t1")
        } catch (e: Exception) {
            thrown = e
        }
        assertTrue(thrown is RevisionConflictException)
        assertEquals(8L, (thrown as RevisionConflictException).currentRevision)
    }

    @Test
    fun `409 without a revision surfaces the server detail instead`() = runTest {
        // Agents busy-409 (SessionBusyError while a turn runs) carries a
        // plain detail — must NOT masquerade as a sync conflict.
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"detail":"session ses_1 busy: a turn is running"}"""),
        )
        var thrown: Exception? = null
        try {
            RemoteAgentRepository(api).send("ses_1", "hi", requestId = "r1")
        } catch (e: Exception) {
            thrown = e
        }
        assertTrue("expected RepositoryException, got $thrown", thrown is RepositoryException)
        assertTrue(
            "detail must surface the busy reason",
            thrown?.message?.contains("busy") == true,
        )
    }

    @Test
    fun `agent event decode carries sender role from payload`() = runTest {
        // Wire truth: kind="message", sender in payload.role.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"events":[
                    {"seq":1,"kind":"message","payload":{"role":"assistant","text":"OK"}},
                    {"seq":2,"kind":"message","payload":{"role":"user","text":"again"}},
                    {"seq":3,"kind":"state_change","payload":{"text":"idle"}}
                ]}""",
            ),
        )
        val events = RemoteAgentRepository(api).events("ses_1", since = 0)
        assertEquals(3, events.size)
        assertEquals("assistant", events[0].role)
        assertEquals("user", events[1].role)
        assertEquals(null, events[2].role) // non-message rows carry no role
        assertEquals("OK", events[0].text)
    }

    @Test
    fun `503 maps to ServerUnavailableException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("busy"))
        var thrown: Exception? = null
        try {
            RemoteSyncRepository(api).status()
        } catch (e: Exception) {
            thrown = e
        }
        assertTrue(thrown is ServerUnavailableException)
    }

    @Test
    fun `connection refused maps to OfflineException`() = runTest {
        val deadServer = MockWebServer()
        deadServer.start()
        val url = deadServer.url("/").toString()
        deadServer.shutdown()
        val deadApi = HttpApi(baseUrl = url, token = "t")
        var thrown: Exception? = null
        try {
            deadApi.get("/v1/status")
        } catch (e: Exception) {
            thrown = e
        }
        assertTrue(thrown is OfflineException)
    }
}
