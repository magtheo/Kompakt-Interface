package dev.magnor.kompakt.data.remote

import dev.magnor.kompakt.domain.CaptureType
import dev.magnor.kompakt.domain.RepositoryException
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Capture pipeline wire contract (Phase 6): interpret request/response and
 * commit request/response shapes, pinned against the coordinator's live
 * capture endpoints (verified against :8650). Includes the §9
 * fail-closed rules: an unknown result kind or a confirmed-but-missing
 * payload must throw, not silently succeed.
 */
class CaptureContractTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: RemoteCaptureRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repo = RemoteCaptureRepository(HttpApi(baseUrl = server.url("/").toString(), token = "test-token"))
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
    fun `interpret posts text and decodes proposal`() = runTest {
        enqueueJson(
            """
            {"proposed_type":"task","title":"Buy butter",
             "text":null,"due_at":"2026-08-25T10:00:00Z",
             "project_id":null,"area_id":null}
            """.trimIndent(),
        )
        val proposal = repo.interpret("buy butter tomorrow")
        assertEquals(CaptureType.TASK, proposal.proposedType)
        assertEquals("Buy butter", proposal.title)
        assertEquals(Instant.parse("2026-08-25T10:00:00Z"), proposal.dueAt)

        val sent = server.takeRequest()
        assertEquals("/v1/capture/interpret", sent.path)
        assertTrue(sent.body.readUtf8().contains("\"buy butter tomorrow\""))
    }

    @Test
    fun `commit task decodes task_created envelope`() = runTest {
        enqueueJson(
            """
            {"replayed":false,"kind":"task_created","task":{
              "id":"repo:dev-server:task:T-101",
              "title":"Buy butter",
              "status":"open",
              "due_at":"2026-08-25T10:00:00Z",
              "project_id":null,"area_id":null,
              "notes":null,"source_type":null,"source_id":null,
              "revision":1,
              "updated_at":"2026-08-23T00:00:00Z"}}
            """.trimIndent(),
        )
        val proposal = dev.magnor.kompakt.domain.CaptureProposal(
            proposedType = CaptureType.TASK,
            title = "Buy butter",
        )
        val result = repo.commit(proposal, requestId = "req-1")
        assertTrue(result is dev.magnor.kompakt.domain.CaptureResult.TaskCreated)
        assertEquals("Buy butter", (result as dev.magnor.kompakt.domain.CaptureResult.TaskCreated).task.title)

        val sent = server.takeRequest()
        assertEquals("/v1/capture/commit", sent.path)
        assertEquals("req-1", sent.getHeader("X-Request-Id"))
        val body = sent.body.readUtf8()
        assertTrue(body.contains("\"request_id\":\"req-1\""))
        assertTrue(body.contains("\"proposed_type\":\"task\""))
    }

    @Test
    fun `commit note decodes note_created envelope`() = runTest {
        enqueueJson(
            """
            {"replayed":true,"kind":"note_created","note":{
              "id":"note:scratch:abc123",
              "title":"Idea about agent UI",
              "revision":1,
              "updated_at":"2026-08-23T00:00:00Z"}}
            """.trimIndent(),
        )
        val proposal = dev.magnor.kompakt.domain.CaptureProposal(
            proposedType = CaptureType.NOTE,
            title = "Idea about agent UI",
        )
        val result = repo.commit(proposal, requestId = "req-2")
        assertTrue(result is dev.magnor.kompakt.domain.CaptureResult.NoteCreated)
        assertEquals("Idea about agent UI", (result as dev.magnor.kompakt.domain.CaptureResult.NoteCreated).note.preview)
    }

    @Test
    fun `unknown result kind fails closed`() = runTest {
        enqueueJson("""{"kind":"hologram_created","holo":{}}""")
        val proposal = dev.magnor.kompakt.domain.CaptureProposal(
            proposedType = CaptureType.NOTE,
            title = "x",
        )
        var thrown = false
        try {
            repo.commit(proposal, requestId = "req-3")
        } catch (e: RepositoryException) {
            thrown = true
            assertTrue(e.message!!.contains("hologram_created"))
        }
        assertTrue(thrown)
    }

    @Test
    fun `confirmed kind without payload fails closed`() = runTest {
        enqueueJson("""{"kind":"task_created"}""")
        val proposal = dev.magnor.kompakt.domain.CaptureProposal(
            proposedType = CaptureType.TASK,
            title = "x",
        )
        var thrown = false
        try {
            repo.commit(proposal, requestId = "req-4")
        } catch (e: RepositoryException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}
