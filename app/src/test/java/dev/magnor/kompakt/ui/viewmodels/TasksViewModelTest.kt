package dev.magnor.kompakt.ui.viewmodels

import dev.magnor.kompakt.data.fake.FakeChangeLog
import dev.magnor.kompakt.data.fake.FakeOrganizationRepository
import dev.magnor.kompakt.data.fake.FakeStore
import dev.magnor.kompakt.data.fake.FakeTaskRepository
import dev.magnor.kompakt.domain.EntityKind
import dev.magnor.kompakt.domain.Project
import dev.magnor.kompakt.domain.ProjectStatus
import dev.magnor.kompakt.domain.Task
import dev.magnor.kompakt.domain.TaskFilter
import dev.magnor.kompakt.domain.TaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-006: task grouping (Today/Upcoming/Completed), project/area filter
 * lists, and per-project open counts — the dev plan §7 behaviors.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModelTest {

    private val now = Instant.parse("2026-08-22T15:00:00Z")
    private lateinit var taskRepo: FakeTaskRepository
    private lateinit var orgRepo: FakeOrganizationRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun build(tasks: List<Task>, projects: List<Project> = emptyList()) {
        val clock = { now }
        val taskStore = FakeStore<Task>(EntityKind.TASK, FakeChangeLog(clock), clock)
        taskStore.seed(tasks)
        val projectStore = FakeStore<Project>(EntityKind.PROJECT, FakeChangeLog(clock), clock)
        projectStore.seed(projects)
        val areaStore = FakeStore<dev.magnor.kompakt.domain.Area>(EntityKind.AREA, FakeChangeLog(clock), clock)
        taskRepo = FakeTaskRepository(taskStore, dev.magnor.kompakt.data.fake.IdempotencyRegistry(), { "t" }, clock)
        orgRepo = FakeOrganizationRepository(projectStore, areaStore)
    }

    @Test
    fun `unfiltered list groups open tasks into today and upcoming`() = runTest(UnconfinedTestDispatcher()) {
        build(
            listOf(
                Task(
                    id = "t_overdue", title = "Overdue", status = TaskStatus.OPEN,
                    dueAt = Instant.parse("2026-08-21T20:00:00Z"),
                    updatedAt = now,
                ),
                Task(
                    id = "t_later_today", title = "Later today", status = TaskStatus.OPEN,
                    dueAt = Instant.parse("2026-08-22T17:00:00Z"),
                    updatedAt = now,
                ),
                Task(
                    id = "t_tomorrow", title = "Tomorrow", status = TaskStatus.OPEN,
                    dueAt = Instant.parse("2026-08-23T09:00:00Z"),
                    updatedAt = now,
                ),
                Task(
                    id = "t_nodue", title = "Someday", status = TaskStatus.OPEN,
                    dueAt = null, updatedAt = now,
                ),
                Task(
                    id = "t_done", title = "Done", status = TaskStatus.COMPLETED,
                    dueAt = Instant.parse("2026-08-21T20:00:00Z"),
                    updatedAt = now,
                ),
            ),
        )
        val vm = TasksViewModel(taskRepo, orgRepo, now, TaskFilter.All)
        val job = launch { vm.state.collect {} } // subscriber for WhileSubscribed
        try {
            val state = vm.state.value
            assertTrue(state.loaded)
            assertEquals(null, state.filterTitle)
            // Due-before-end-of-today bucket — includes overdue, sorted by dueAt.
            assertEquals(listOf("t_overdue", "t_later_today"), state.today.map { it.id })
            // Future or undated open tasks, undated last.
            assertEquals(listOf("t_tomorrow", "t_nodue"), state.upcoming.map { it.id })
            assertEquals(listOf("t_done"), state.completed.map { it.id })
            assertEquals(0, state.open.size) // flat lists only for filtered views
        } finally {
            coroutineContext.cancelChildren()
            job.cancel()
        }
    }

    @Test
    fun `project filter shows flat open and completed lists titled by project`() = runTest(UnconfinedTestDispatcher()) {
        build(
            listOf(
                Task(
                    id = "t_kv_open", title = "KodeVerket open", status = TaskStatus.OPEN,
                    dueAt = Instant.parse("2026-08-23T09:00:00Z"),
                    projectId = "project_kv", updatedAt = now,
                ),
                Task(
                    id = "t_kv_done", title = "KodeVerket done", status = TaskStatus.COMPLETED,
                    projectId = "project_kv", updatedAt = now,
                ),
                Task(
                    id = "t_other", title = "Elsewhere", status = TaskStatus.OPEN,
                    projectId = "project_other", updatedAt = now,
                ),
            ),
            listOf(
                Project(
                    id = "project_kv", name = "KodeVerket", status = ProjectStatus.ACTIVE,
                    updatedAt = now,
                ),
            ),
        )
        val vm = TasksViewModel(taskRepo, orgRepo, now, TaskFilter.ByProject("project_kv"))
        val job = launch { vm.state.collect {} }
        try {
            val state = vm.state.value
            assertTrue(state.filtered)
            assertEquals("KodeVerket", state.filterTitle)
            assertEquals(listOf("t_kv_open"), state.open.map { it.id })
            assertEquals(listOf("t_kv_done"), state.completed.map { it.id })
        } finally {
            coroutineContext.cancelChildren()
            job.cancel()
        }
    }

    @Test
    fun `projects list counts open tasks per project`() = runTest(UnconfinedTestDispatcher()) {
        build(
            listOf(
                Task(
                    id = "t1", title = "a", status = TaskStatus.OPEN,
                    projectId = "project_kv", updatedAt = now,
                ),
                Task(
                    id = "t2", title = "b", status = TaskStatus.OPEN,
                    projectId = "project_kv", updatedAt = now,
                ),
                Task(
                    id = "t3", title = "c", status = TaskStatus.COMPLETED,
                    projectId = "project_kv", updatedAt = now,
                ),
            ),
            listOf(
                Project(
                    id = "project_kv", name = "KodeVerket", status = ProjectStatus.ACTIVE,
                    updatedAt = now,
                ),
                Project(
                    id = "project_empty", name = "Empty", status = ProjectStatus.PAUSED,
                    updatedAt = now,
                ),
            ),
        )
        val vm = ProjectsViewModel(orgRepo, taskRepo)
        val job = launch { vm.state.collect {} }
        try {
            val state = vm.state.value
            assertTrue(state.loaded)
            val byId = state.rows.associateBy { it.project.id }
            assertEquals(2, byId["project_kv"]?.openTasks) // completed t3 not counted
            assertEquals(0, byId["project_empty"]?.openTasks)
        } finally {
            coroutineContext.cancelChildren()
            job.cancel()
        }
    }
}
