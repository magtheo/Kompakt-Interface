package dev.magnor.kompakt.data.remote

import dev.magnor.kompakt.domain.ProtocolNegotiation
import dev.magnor.kompakt.domain.ProtocolVerdict
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live end-to-end smoke against the real coordinator. Skipped unless
 * KOMPACT_LIVE_URL + KOMPACT_LIVE_TOKEN are set (secrets never live in
 * the repo), e.g.:
 *
 *   KOMPACT_LIVE_URL=http://dev-server.example.ts.net:8650 \
 *   KOMPACT_LIVE_TOKEN=… ./scripts/verify.sh
 *
 * This is the T-004 acceptance check: client connects, negotiation
 * passes, real data decodes.
 */
class LiveServerSmokeTest {

    private val url: String? = System.getenv("KOMPACT_LIVE_URL")
    private val token: String? = System.getenv("KOMPACT_LIVE_TOKEN")

    private fun api(): HttpApi = HttpApi(
        baseUrl = requireNotNull(url),
        token = requireNotNull(token),
    )

    @Test
    fun `negotiates and reads real data`() = runTest {
        assumeTrue("live env not set — skipping", url != null && token != null)
        val sync = RemoteSyncRepository(api())

        val caps = sync.capabilities()
        assertEquals(
            "live server must negotiate cleanly with client protocol 1",
            ProtocolVerdict.Ok,
            ProtocolNegotiation.evaluate(
                clientProtocol = 1, minimumServerProtocol = 1, caps = caps,
            ),
        )
        assertTrue("server reports unhealthy", sync.status().healthy)

        val tasks = RemoteTaskRepository(api()).observeTasks().first()
        assertTrue("expected real tasks from coordinator cache", tasks.isNotEmpty())

        val projects = RemoteOrganizationRepository(api()).observeProjects().first()
        assertTrue("expected vault PARA projects", projects.isNotEmpty())

        // repo-task joins must resolve; vikunja project links may dangle
        // until the coordinator caches vikunja projects (later phase).
        val projectIds = projects.map { it.id }.toSet()
        tasks.filter { it.projectId?.startsWith("machine:project:") == true }.forEach {
            assertTrue(
                "repo task ${it.id} references unknown project ${it.projectId}",
                it.projectId in projectIds,
            )
        }
    }

    /**
     * T-006 acceptance: the Today + Organize vertical slice reads decode
     * against the live coordinator — Today projection (events/tasks/
     * attention/aggregates) and the Areas axis of the PARA projection.
     */
    @Test
    fun `reads today projection and areas`() = runTest {
        assumeTrue("live env not set — skipping", url != null && token != null)

        val today = RemoteTodayRepository(api()).today()
        assertTrue(
            "today projection must decode with a real date",
            today.date.toEpochDays() > 0,
        )

        val areas = RemoteOrganizationRepository(api()).observeAreas().first()
        assertTrue("expected vault PARA areas", areas.isNotEmpty())

        // Surface gating stays consistent with what the server actually serves:
        // when capabilities say notes=false, a notes read must fail closed
        // (the UI relies on the flag, not on the request, per protocol §9).
        val caps = RemoteSyncRepository(api()).capabilities()
        val notesServed = caps.supports("notes")
        if (!notesServed) {
            var threw = false
            try {
                RemoteNoteRepository(api()).observeNotes().first()
            } catch (e: Exception) {
                threw = true
            }
            assertTrue(
                "notes flagged off but /v1/notes served data — flag and wire disagree",
                threw,
            )
        }
    }
}
