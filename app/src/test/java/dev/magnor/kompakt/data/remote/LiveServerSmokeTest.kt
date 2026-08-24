package dev.magnor.kompakt.data.remote

import dev.magnor.kompakt.domain.AgentDispatchDraft
import dev.magnor.kompakt.domain.AgentRun
import dev.magnor.kompakt.domain.AgentRunState
import dev.magnor.kompakt.domain.ProtocolNegotiation
import dev.magnor.kompakt.domain.ProtocolVerdict
import dev.magnor.kompakt.domain.SteerOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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

    /**
     * T-011 acceptance: the agents vertical (Phase 8, V-052 contract) works
     * against the live coordinator — surface decode (both backends, honest
     * capabilities), merged run list with readable titles, events + result
     * on a terminal Warren run, honest `unsupported` steer for pi, and a
     * real OpenCode dispatch round-trip through the app's own DTO stack.
     */
    @Test
    fun `reads agents surface and dispatches a live run`() = runTest {
        assumeTrue("live env not set — skipping", url != null && token != null)
        val agents = RemoteAgentRepository(api())

        // The flag and the wire must agree: agents is served.
        val caps = RemoteSyncRepository(api()).capabilities()
        assertTrue("capabilities must advertise agents", caps.supports("agents"))

        val surface = agents.observeSurface().first()
        assertTrue(
            "expected both live backends, got ${surface.backends.keys}",
            "warren" in surface.backends && "opencode" in surface.backends,
        )
        assertTrue(
            "pi must advertise honest spawn-only steering",
            surface.agents.any { it.name == "pi" && it.steering == "spawn_only" },
        )

        val runs = agents.observeRuns().first()
        assertTrue("expected runs from both backends", runs.isNotEmpty())
        assertTrue(
            "expected a warren run with a readable title (prompt-derived)",
            runs.any { it.backend == "warren" && !it.displayTitle.isNullOrBlank() },
        )

        // Terminal warren run: events decode + result interpretation (w-1
        // lesson 3: finalize_failed + salvage evidence ⇒ SUCCEEDED).
        val terminal = runs.first {
            it.backend == "warren" &&
                (it.state == AgentRunState.SUCCEEDED || it.state == AgentRunState.FAILED)
        }
        val events = agents.events(terminal.id, since = 0)
        assertTrue("expected a non-empty durable event stream", events.isNotEmpty())
        val result = agents.result(terminal.id)
        assertTrue("terminal run must yield an interpreted result", result != null)
        assertTrue("result must carry a summary", !result?.summary.isNullOrBlank())

        // Warren steering is honestly unsupported — never silently pretend.
        val steer = agents.steer(terminal.id, "focus on the readme", requestId = "live-smoke-agents")
        assertEquals(
            "pi is spawn-only; steer must report unsupported",
            SteerOutcome.UNSUPPORTED,
            steer,
        )

        // Real dispatch round-trip on the default (opencode) lane.
        val dispatched = agents.dispatch(
            AgentDispatchDraft(
                prompt = "Reply with the single word OK. Do not use any tools.",
                backend = "opencode",
            ),
            requestId = "live-smoke-agents",
        )
        assertEquals("dispatch backend", "opencode", dispatched.backend)
        assertTrue(
            "opencode executions are ses_… sessions, got ${dispatched.id}",
            dispatched.id.startsWith("ses_"),
        )
        assertTrue(
            "dispatch without a project must yield a null ref (V-056 wire)",
            dispatched.projectRef == null,
        )

        // T-017 resume leg: send a follow-up into the dispatched session —
        // the coordinator's sync driver resumes the v1 turn; once settled,
        // the durable evidence shows BOTH turns and the result carries the
        // latest assistant message. Real-clock polling (IO delay bypasses
        // runTest's virtual time), capped so a wedged backend fails fast.
        //
        // A busy turn answers send with 409 (SessionBusyError → honest
        // conflict detail); the product gates the composer on canSend, so
        // the test waits for turn one to settle first — same flow.
        val settleDeadline = System.currentTimeMillis() + 45_000
        var settled: AgentRun? = dispatched
        while (System.currentTimeMillis() < settleDeadline) {
            settled = agents.observeRun(dispatched.id).first()
            val state = settled?.state
            if (state != null && state != AgentRunState.QUEUED && state != AgentRunState.RUNNING) break
            withContext(Dispatchers.IO) { delay(2_000) }
        }
        assertTrue(
            "turn one must settle before send, state=${settled?.state}",
            settled?.state != AgentRunState.QUEUED && settled?.state != AgentRunState.RUNNING,
        )
        val sent = agents.send(
            dispatched.id,
            "Reply with the single word DONE. Do not use any tools.",
            requestId = "live-smoke-agents",
        )
        assertEquals("send keeps the session lane", "opencode", sent.backend)

        var run: AgentRun? = dispatched
        val deadline = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < deadline) {
            run = agents.observeRun(dispatched.id).first()
            val state = run?.state
            if (state != null && state != AgentRunState.QUEUED && state != AgentRunState.RUNNING) break
            withContext(Dispatchers.IO) { delay(2_000) }
        }
        assertTrue(
            "resumed turn must settle, state=${run?.state}",
            run?.state == AgentRunState.IDLE || run?.state?.isTerminal == true,
        )

        // Wire truth: events carry kind="message" with sender in payload.role;
        // OpenCode's session view does NOT project the initial dispatch
        // prompt — durable evidence after resume = turn-1 assistant + the
        // resumed user message + its assistant reply.
        val roles = agents.events(dispatched.id, since = 0).map { it.role ?: it.kind }
        assertTrue(
            "durable evidence must show the resumed turn (user→assistant), got $roles",
            roles.count { it == "user" } >= 1 && roles.count { it == "assistant" } >= 2,
        )
        val resumedResult = agents.result(dispatched.id)
        assertTrue(
            "result must reflect the resumed turn",
            resumedResult?.summary?.contains("DONE", ignoreCase = true) == true,
        )
    }
}
