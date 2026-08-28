package dev.magnor.kompakt.ui.viewmodels

import dev.magnor.kompakt.data.repository.CalendarRepository
import dev.magnor.kompakt.data.repository.TaskRepository
import dev.magnor.kompakt.data.repository.TodayRepository
import dev.magnor.kompakt.domain.CalendarEvent
import dev.magnor.kompakt.domain.CalendarInfo
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.EventCreateResult
import dev.magnor.kompakt.domain.EventDraft
import dev.magnor.kompakt.domain.EventUpdate
import dev.magnor.kompakt.domain.RequestId
import dev.magnor.kompakt.domain.RevisionConflictException
import dev.magnor.kompakt.domain.Task
import dev.magnor.kompakt.domain.TaskDraft
import dev.magnor.kompakt.domain.TaskFilter
import dev.magnor.kompakt.domain.TaskStatus
import dev.magnor.kompakt.domain.TodayProjection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-026 — Today tabbed redesign, ViewModel legs: hero fall-through join,
 * quick-complete (D031 scope-limited mutation), event/task splits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {

    private class StubTodayRepo(projection: TodayProjection) : TodayRepository {
        val flow = MutableStateFlow(projection)
        override fun observeToday(): Flow<TodayProjection> = flow
        override suspend fun today(): TodayProjection = flow.value
    }

    /** Fails ONLY fetchWindow — the T-024 failing-repo lesson. */
    private class RecordingCalendar(
        private val fetch: suspend (String, String) -> List<CalendarEvent>,
    ) : CalendarRepository {
        val calls = mutableListOf<Pair<String, String>>()
        override fun observeCalendars(): Flow<List<CalendarInfo>> = MutableStateFlow(emptyList())
        override suspend fun fetchWindow(from: String, to: String): List<CalendarEvent> {
            calls += from to to
            return fetch(from, to)
        }
        override suspend fun fetchEvent(id: String): CalendarEvent? = null
        override suspend fun createEvent(requestId: String, draft: EventDraft): EventCreateResult =
            error("unused")
        override suspend fun updateEvent(id: String, patch: EventUpdate): Boolean = error("unused")
        override suspend fun deleteEvent(id: String): Boolean = error("unused")
    }

    private class RecordingTasks(
        private val complete: suspend (EntityId, Long, RequestId) -> Task,
    ) : TaskRepository {
        val completed = mutableListOf<Triple<EntityId, Long, RequestId>>()
        override fun observeTasks(filter: TaskFilter): Flow<List<Task>> = MutableStateFlow(emptyList())
        override fun observeTask(id: EntityId): Flow<Task?> = MutableStateFlow(null)
        override suspend fun getTask(id: EntityId): Task? = null
        override suspend fun createTask(draft: TaskDraft, requestId: RequestId): Task = error("unused")
        override suspend fun completeTask(id: EntityId, expectedRevision: Long, requestId: RequestId): Task {
            completed += Triple(id, expectedRevision, requestId)
            return complete(id, expectedRevision, requestId)
        }
        override suspend fun postponeTask(
            id: EntityId,
            expectedRevision: Long,
            newDueAt: Instant?,
            requestId: RequestId,
        ): Task = error("unused")
        override suspend fun updateTask(
            id: EntityId,
            expectedRevision: Long,
            patch: dev.magnor.kompakt.domain.TaskPatch,
            requestId: RequestId,
        ): Task = error("unused")
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // 2026-08-28 12:00Z = 14:00 Oslo — mid-afternoon anchor.
    private val now = Instant.parse("2026-08-28T12:00:00Z")
    private val date = LocalDate(2026, 8, 28)
    private val pastEvent = CalendarEvent(id = "e1", title = "Standup", startAt = Instant.parse("2026-08-28T07:00:00Z"))
    private val soonEvent = CalendarEvent(id = "e2", title = "Dentist", startAt = Instant.parse("2026-08-28T15:00:00Z"))
    private val tomorrowEvent = CalendarEvent(id = "e3", title = "Team sync", startAt = Instant.parse("2026-08-29T07:00:00Z"))

    private fun task(
        id: String,
        status: TaskStatus = TaskStatus.OPEN,
        updatedAt: Instant = now,
        revision: Long = 3,
    ) = Task(
        id = id,
        title = "Task $id",
        status = status,
        revision = revision,
        updatedAt = updatedAt,
    )

    @Test
    fun `next-up falls back to beyond-today when nothing remains today`() = runTest {
        val calendar = RecordingCalendar { _, _ -> listOf(tomorrowEvent) }
        val vm = TodayViewModel(
            StubTodayRepo(TodayProjection(date = date, events = listOf(pastEvent))),
            now,
            calendarRepository = calendar,
        )
        val state = vm.state.first { it.loaded && it.nextUp != null }
        assertEquals(tomorrowEvent, state.nextUp)
        // Oslo-date window derived from `now`: 7 days ahead.
        assertEquals(listOf("2026-08-28" to "2026-09-04"), calendar.calls)
    }

    @Test
    fun `calendar fetch failure degrades to no next-up without error`() = runTest {
        val calendar = RecordingCalendar { _, _ -> error("server down") }
        val vm = TodayViewModel(
            StubTodayRepo(TodayProjection(date = date, events = listOf(pastEvent))),
            now,
            calendarRepository = calendar,
        )
        val state = vm.state.first { it.loaded }
        assertNull(state.nextUp)
        assertNull(state.error)
    }

    @Test
    fun `remaining-today event wins and beyond fetch is skipped`() = runTest {
        val calendar = RecordingCalendar { _, _ -> listOf(tomorrowEvent) }
        val vm = TodayViewModel(
            StubTodayRepo(TodayProjection(date = date, events = listOf(pastEvent, soonEvent))),
            now,
            calendarRepository = calendar,
        )
        val state = vm.state.first { it.loaded }
        assertEquals(soonEvent, state.nextUp)
        assertTrue(calendar.calls.isEmpty())
    }

    @Test
    fun `null calendar repository keeps today-only hero`() = runTest {
        val vm = TodayViewModel(
            StubTodayRepo(TodayProjection(date = date, events = listOf(soonEvent))),
            now,
        )
        val state = vm.state.first { it.loaded }
        assertEquals(soonEvent, state.nextUp)
    }

    @Test
    fun `quick complete sends id revision and fresh request id`() = runTest {
        val tasks = RecordingTasks { id, _, _ -> task(id, status = TaskStatus.COMPLETED) }
        val vm = TodayViewModel(
            StubTodayRepo(TodayProjection(date = date)),
            now,
            taskRepository = tasks,
            newRequestId = { "req-1" },
        )
        vm.state.first { it.loaded }
        vm.completeTask(task("t9", revision = 7))
        advanceUntilIdle()
        assertEquals(listOf(Triple<EntityId, Long, RequestId>("t9", 7, "req-1")), tasks.completed)
        assertNull(vm.state.value.notice)
    }

    @Test
    fun `revision conflict sets notice and refreshes`() = runTest {
        val tasks = RecordingTasks { _, rev, _ -> throw RevisionConflictException(rev + 1) }
        val vm = TodayViewModel(
            StubTodayRepo(TodayProjection(date = date)),
            now,
            taskRepository = tasks,
        )
        vm.state.first { it.loaded }
        vm.completeTask(task("t1"))
        val state = vm.state.first { it.loaded && it.notice != null }
        assertEquals("Changed on server — showing latest", state.notice)
    }

    @Test
    fun `generic complete failure surfaces honest user message`() = runTest {
        val tasks = RecordingTasks { _, _, _ -> error("offline") }
        val vm = TodayViewModel(
            StubTodayRepo(TodayProjection(date = date)),
            now,
            taskRepository = tasks,
        )
        vm.state.first { it.loaded }
        vm.completeTask(task("t1"))
        advanceUntilIdle()
        assertNotNull(vm.state.value.notice)
    }

    @Test
    fun `tasks split into open and done-today by Oslo date`() = runTest {
        val open = task("open")
        val doneToday = task("done1", status = TaskStatus.COMPLETED, updatedAt = Instant.parse("2026-08-28T10:00:00Z"))
        val doneOld = task("done2", status = TaskStatus.COMPLETED, updatedAt = Instant.parse("2026-08-20T10:00:00Z"))
        val vm = TodayViewModel(
            StubTodayRepo(TodayProjection(date = date, tasks = listOf(open, doneToday, doneOld))),
            now,
        )
        val state = vm.state.first { it.loaded }
        assertEquals(listOf(open), state.openTasks)
        assertEquals(listOf(doneToday), state.doneToday)
    }

    @Test
    fun `events split at now into past and remaining`() = runTest {
        val vm = TodayViewModel(
            StubTodayRepo(TodayProjection(date = date, events = listOf(soonEvent, pastEvent))),
            now,
        )
        val state = vm.state.first { it.loaded }
        assertEquals(listOf(pastEvent), state.pastEvents)
        assertEquals(listOf(soonEvent), state.remainingEvents)
    }
}
