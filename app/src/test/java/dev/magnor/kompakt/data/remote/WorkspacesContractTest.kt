package dev.magnor.kompakt.data.remote

import dev.magnor.kompakt.data.repository.WorkspaceRepository
import dev.magnor.kompakt.domain.Workspace
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-022c wire contract for /v1/workspaces against a MockWebServer speaking
 * the coordinator's shape (verified live against the V-061 temp instance):
 * `{default: null, workspaces: [{ref, label}]}` — refs are opaque, paths
 * never cross the wire (D023), and unknown sibling keys (like `default`)
 * must not break decoding.
 */
class WorkspacesContractTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: WorkspaceRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repo = RemoteWorkspaceRepository(HttpApi(baseUrl = server.url("/").toString(), token = "test-token"))
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
    fun `decodes ref-label rows and tolerates the default sibling key`() = runTest {
        enqueueJson(
            """
            {"default": null, "workspaces": [
              {"ref": "evershift", "label": "Evershift"},
              {"ref": "kompakt-interface", "label": "Kompakt-Interface"}
            ]}
            """.trimIndent(),
        )
        val workspaces = repo.observeWorkspaces().first()

        assertEquals(2, workspaces.size)
        assertEquals(Workspace(ref = "evershift", label = "Evershift"), workspaces[0])
        assertEquals("kompakt-interface", workspaces[1].ref)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/workspaces", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
    }

    @Test
    fun `empty registry decodes to an empty picker`() = runTest {
        enqueueJson("""{"default": null, "workspaces": []}""")
        assertTrue(repo.observeWorkspaces().first().isEmpty())
    }
}
