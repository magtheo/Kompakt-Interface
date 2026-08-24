package dev.magnor.kompakt.ui.viewmodels

import dev.magnor.kompakt.data.fake.FakeChangeLog
import dev.magnor.kompakt.data.fake.FakeInboxRepository
import dev.magnor.kompakt.data.fake.FakeStore
import dev.magnor.kompakt.data.fake.IdempotencyRegistry
import dev.magnor.kompakt.domain.EntityKind
import dev.magnor.kompakt.domain.InboxItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * T-018: opening an agent-run alert marks it read (dismiss via the
 * repository); opening other items is a no-op server-side.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModelTest {

    private val now = Instant.parse("2026-08-24T09:00:00Z")
    private lateinit var store: FakeStore<InboxItem>

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        store = FakeStore(EntityKind.INBOX_ITEM, FakeChangeLog { now }) { now }
        runBlocking { seed() }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun item(
        id: String,
        sourceType: EntityKind?,
        sourceId: String?,
    ) = InboxItem(
        id = id,
        sourceType = sourceType,
        sourceId = sourceId,
        title = "Agent replied",
        summary = "replied",
        timestamp = now,
    )

    private suspend fun seed() {
        store.create(item("alert:ses_1:1", EntityKind.AGENT_RUN, "ses_1"))
        store.create(item("task_alert", EntityKind.TASK, "task_9"))
    }

    private fun viewModel() = InboxViewModel(
        FakeInboxRepository(store, IdempotencyRegistry()),
        now,
    )

    @Test
    fun openingAgentAlertDismissesIt() = runTest {
        val vm = viewModel()
        vm.markOpened(item("alert:ses_1:1", EntityKind.AGENT_RUN, "ses_1"))
        assertNull(store.snapshot["alert:ses_1:1"])
    }

    @Test
    fun openingNonAgentItemDoesNotDismiss() = runTest {
        val vm = viewModel()
        vm.markOpened(item("task_alert", EntityKind.TASK, "task_9"))
        assertEquals("task_alert", store.snapshot["task_alert"]?.id)
    }

    @Test
    fun derivedAlertIsLeftAlone() = runTest {
        val vm = viewModel()
        store.create(item("alert:host_down:1", null, null))
        vm.markOpened(item("alert:host_down:1", null, null))
        assertEquals("alert:host_down:1", store.snapshot["alert:host_down:1"]?.id)
    }
}
