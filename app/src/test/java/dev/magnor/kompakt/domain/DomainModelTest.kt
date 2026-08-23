package dev.magnor.kompakt.domain

import dev.magnor.kompakt.data.AppContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 2 (T-003) contract tests: the fake repositories must honor the
 * sync semantics the real server will enforce (revisions, idempotency,
 * change stream) so Phase 3 only swaps plumbing.
 */
class DomainModelTest {

    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        container = AppContainer()
    }

    // ---- protocol negotiation (§9) ----

    @Test
    fun `protocol ok when versions overlap`() {
        val verdict = ProtocolNegotiation.evaluate(
            clientProtocol = 1,
            minimumServerProtocol = 1,
            caps = CapabilitySet(serverProtocol = 1, minimumClientProtocol = 1),
        )
        assertTrue(verdict is ProtocolVerdict.Ok)
    }

    @Test
    fun `client too old is a hard stop`() {
        val verdict = ProtocolNegotiation.evaluate(
            clientProtocol = 1,
            minimumServerProtocol = 1,
            caps = CapabilitySet(serverProtocol = 5, minimumClientProtocol = 2),
        )
        assertTrue(verdict is ProtocolVerdict.ClientTooOld)
    }

    @Test
    fun `server too old is rejected`() {
        val verdict = ProtocolNegotiation.evaluate(
            clientProtocol = 3,
            minimumServerProtocol = 2,
            caps = CapabilitySet(serverProtocol = 1, minimumClientProtocol = 1),
        )
        assertTrue(verdict is ProtocolVerdict.ServerTooOld)
    }

    // ---- unknown enum values never throw (§9) ----

    @Test
    fun `unknown entity kind decodes as UNKNOWN`() {
        val json = """{"entity_type":"hologram","entity_id":"x_1","type":"updated","revision":1,"cursor":"chg_1"}"""
        val envelope = KompaktJson.decodeFromString(ChangeEnvelope.serializer(), json)
        assertEquals(EntityKind.UNKNOWN, envelope.entityKind)
    }

    @Test
    fun `unknown action type is filtered before rendering`() {
        val action = KompaktJson.decodeFromString(
            Action.serializer(), """{"id":"vibes","label":"Vibes"}""",
        )
        assertEquals(ActionType.UNKNOWN, action.type)
        assertTrue(!action.isKnown)
    }

    // ---- idempotency (§15) ----

    @Test
    fun `replaying a requestId creates exactly one task`() = runTest {
        val requestId = "req-fixed-1"
        val first = container.taskRepository.createTask(
            TaskDraft(title = "Call dentist"), requestId,
        )
        val replay = container.taskRepository.createTask(
            TaskDraft(title = "Call dentist"), requestId,
        )
        assertEquals(first.id, replay.id)
        val all = container.taskRepository.observeTasks().first()
        assertEquals(1, all.count { it.title == "Call dentist" && it.id == first.id })
    }

    // ---- optimistic concurrency (§13/§14) ----

    @Test
    fun `stale revision is rejected`() = runTest {
        val task = container.taskRepository.createTask(TaskDraft(title = "A"), "req-a")
        val stale = task.copy() // revision 1
        val fresh = container.taskRepository.completeTask(task.id, task.revision, "req-b")
        assertEquals(TaskStatus.COMPLETED, fresh.status)
        try {
            // Second mutation with the stale revision must conflict.
            container.taskRepository.updateTask(
                task.id, stale.revision, TaskPatch(title = "B"), "req-c",
            )
            throw AssertionError("expected RevisionConflictException")
        } catch (e: RevisionConflictException) {
            // expected — server 409 analog
        }
    }

    // ---- capture: propose, user confirms (never guessed) ----

    @Test
    fun `capture proposes task for action phrasing`() = runTest {
        val proposal = container.captureRepository.interpret("Call dentist tomorrow")
        assertEquals(CaptureType.TASK, proposal.proposedType)
        assertNotNull(proposal.dueAt)
    }

    @Test
    fun `capture proposes note for reflective phrasing`() = runTest {
        val proposal = container.captureRepository.interpret("Idea about agent UI patterns")
        assertEquals(CaptureType.NOTE, proposal.proposedType)
    }

    @Test
    fun `capture commit creates the confirmed object`() = runTest {
        val proposal = container.captureRepository.interpret("Buy groceries")
        val result = container.captureRepository.commit(proposal, "req-cap-1")
        assertTrue(result is CaptureResult.TaskCreated)
        val task = (result as CaptureResult.TaskCreated).task
        assertEquals("Buy groceries", task.title)
        assertNotNull(container.taskRepository.getTask(task.id))
    }

    // ---- change stream (§3/§11) ----

    @Test
    fun `changes replay mutations then catch up`() = runTest {
        // Baseline seed produces no change entries — the initial fetch
        // carries the baseline; the stream carries post-sync mutations.
        assertTrue(container.syncRepository.changesSince(null).changes.isEmpty())

        val task = container.taskRepository.createTask(TaskDraft(title = "Tracked"), "req-chg-1")
        val page1 = container.syncRepository.changesSince(null)
        assertEquals(1, page1.changes.size)
        assertEquals(task.id, page1.changes.single().entityId)

        // Following the returned cursor yields an empty page — caught up.
        val page2 = container.syncRepository.changesSince(page1.nextCursor)
        assertTrue(page2.changes.isEmpty())
    }

    // ---- Today is a view, not a source of truth ----

    @Test
    fun `today projection aggregates without owning`() = runTest {
        val today = container.todayRepository.today()
        assertNotNull(today.date)
        assertTrue(today.events.isNotEmpty())
        assertTrue(today.tasks.isNotEmpty())
        // Completing a task flows through the projection via the repository.
        val first = today.tasks.first()
        container.taskRepository.completeTask(first.id, first.revision, "req-today-1")
        val after = container.todayRepository.today()
        assertTrue(first.id !in after.tasks.map { it.id })
    }

    // ---- Phase 8: agents surface is capability-driven (V-052) ----

    @Test
    fun `agent dispatch honors backend capabilities and replays idempotently`() = runTest {
        val surface = container.agentRepository.observeSurface().first()
        val role = surface.agents.first { it.backend == "warren" }

        // Warren registers projects → a run without a project ref is rejected.
        val rejected = runCatching {
            container.agentRepository.dispatch(
                AgentDispatchDraft(prompt = "Audit docs", backend = role.backend, agent = role.name),
                "req-run-1",
            )
        }
        assertTrue(rejected.isFailure)

        val run = container.agentRepository.dispatch(
            AgentDispatchDraft(
                prompt = "Audit docs", backend = role.backend,
                agent = role.name, projectRef = "kompakt",
            ),
            "req-run-2",
        )
        assertEquals(AgentRunKind.RUN, run.kind) // warren is atomic, not resumable
        assertEquals(AgentRunState.QUEUED, run.state)

        // Replay with the same request id creates exactly one run.
        val replay = container.agentRepository.dispatch(
            AgentDispatchDraft(
                prompt = "Audit docs", backend = role.backend,
                agent = role.name, projectRef = "kompakt",
            ),
            "req-run-2",
        )
        assertEquals(run.id, replay.id)

        // Steering is honest: neither fake backend advertises live steering.
        assertEquals(
            SteerOutcome.UNSUPPORTED,
            container.agentRepository.steer(run.id, "faster", "req-steer-1"),
        )

        // Cancel is terminal-safe and repeatable.
        container.agentRepository.cancel(run.id, "req-cancel-1")
        assertEquals(AgentRunState.CANCELLED, container.agentRepository.observeRun(run.id).first()?.state)
        container.agentRepository.cancel(run.id, "req-cancel-2")
        assertEquals(AgentRunState.CANCELLED, container.agentRepository.observeRun(run.id).first()?.state)
    }
}
