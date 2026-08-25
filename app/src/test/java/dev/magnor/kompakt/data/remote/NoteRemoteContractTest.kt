package dev.magnor.kompakt.data.remote

import dev.magnor.kompakt.data.repository.NoteRepository
import dev.magnor.kompakt.domain.Note
import dev.magnor.kompakt.domain.NoteConflictException
import dev.magnor.kompakt.domain.NoteDraft
import dev.magnor.kompakt.domain.RepositoryException
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
 * T-022a / D028 v2 wire contract for /v1/notes against a MockWebServer
 * speaking the coordinator's shapes (verified live against :8650 in
 * V-060a): list rows pin the scratchpad and omit text; detail adds text +
 * checksum; PUT carries the optimistic lock; a 409 with
 * `detail.reason = "checksum_mismatch"` and the fresh note attached decodes
 * into NoteConflictException — anything else stays a plain conflict.
 */
class NoteRemoteContractTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: NoteRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repo = RemoteNoteRepository(HttpApi(baseUrl = server.url("/").toString(), token = "test-token"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueJson(body: String, status: Int = 200) {
        server.enqueue(
            MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    @Test
    fun `list decodes rows and tolerates missing text checksum`() = runTest {
        enqueueJson(
            """
            {"notes":[
              {"id":"vault:note:scratch","title":"Scratchpad","role":"scratchpad",
               "category":"Inbox","updated_at":"2026-08-24T09:00:00Z"},
              {"id":"vault:note:abc","title":"Evershift renderer idea","preview":"voxel idea",
               "category":"Projects","role":"note","updated_at":"2026-08-24T08:00:00Z"}
            ]}
            """.trimIndent(),
        )
        val rows = repo.observeNotes().first()

        assertEquals(2, rows.size)
        val scratch = rows.first { it.isScratchpad }
        assertNull(scratch.text) // list wire omits it
        assertNull(scratch.checksum)
        assertEquals("vault:note:abc", rows[1].id)
    }

    @Test
    fun `detail decodes text and checksum`() = runTest {
        enqueueJson(
            """
            {"note":{"id":"vault:note:abc","title":"Idea","role":"note","category":"Inbox",
              "text":"## 2026-08-24 — Idea\nbody","checksum":"sha256abc",
              "updated_at":"2026-08-24T09:00:00Z"}}
            """.trimIndent(),
        )
        val note = repo.getNote("vault:note:abc")

        assertEquals("sha256abc", note?.checksum)
        assertTrue(note?.text.orEmpty().startsWith("## "))
    }

    @Test
    fun `put sends the lock and returns the updated note`() = runTest {
        enqueueJson(
            """
            {"note":{"id":"vault:note:abc","title":"Idea","role":"note","category":"Inbox",
              "text":"new text","checksum":"sha256new",
              "updated_at":"2026-08-24T10:00:00Z"}}
            """.trimIndent(),
        )
        val updated = repo.updateNote("vault:note:abc", "new text", "sha256old")

        assertEquals("sha256new", updated.checksum)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/v1/notes/vault:note:abc", request.path)
        assertTrue(request.body.readUtf8().contains("\"expected_checksum\":\"sha256old\""))
    }

    @Test
    fun `checksum 409 decodes into NoteConflictException with fresh note`() = runTest {
        enqueueJson(
            status = 409,
            body = """
                {"detail":{"reason":"checksum_mismatch",
                  "note":{"id":"vault:note:abc","title":"Idea","role":"note","category":"Inbox",
                    "text":"server text","checksum":"sha256fresh",
                    "updated_at":"2026-08-24T11:00:00Z"}}}
                """.trimIndent(),
        )
        val thrown = runCatching { repo.updateNote("vault:note:abc", "my edit", "sha256stale") }.exceptionOrNull()

        val conflict = thrown as? NoteConflictException
        assertEquals("sha256fresh", conflict?.fresh?.checksum)
        assertEquals("server text", conflict?.fresh?.text)
    }

    @Test
    fun `plain 409 stays a RepositoryException not a note conflict`() = runTest {
        enqueueJson(status = 409, body = """{"detail":"session busy"}""")
        val thrown = runCatching { repo.updateNote("vault:note:abc", "my edit", "sha256old") }.exceptionOrNull()

        assertTrue(thrown is RepositoryException)
        assertNull(thrown as? NoteConflictException)
        assertEquals("session busy", thrown?.message)
    }

    // ---- T-022e / V-064: project-targeted note creation ----

    private val createdEnvelope = """
        {"note":{"id":"vault:note:new","title":"Idea","role":"note","category":"Projects",
          "project_id":"vault:project:kodeverket","updated_at":"2026-08-25T12:00:00Z"}}
    """.trimIndent()

    @Test
    fun `create carries the project id for targeted saves`() = runTest {
        enqueueJson(createdEnvelope)
        repo.createNote(
            NoteDraft(text = "## Idea\nbody", projectId = "vault:project:kodeverket"),
            "req-1",
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/notes", request.path)
        assertEquals("req-1", request.getHeader("X-Request-Id"))
        assertTrue(request.body.readUtf8().contains("\"project_id\":\"vault:project:kodeverket\""))
    }

    @Test
    fun `create omits the project id for inbox saves`() = runTest {
        enqueueJson(createdEnvelope)
        repo.createNote(NoteDraft(text = "## Idea\nbody"), "req-2")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/notes", request.path)
        // explicitNulls=false: an inbox save must not carry a null project_id
        assertFalse(request.body.readUtf8().contains("project_id"))
    }
}
