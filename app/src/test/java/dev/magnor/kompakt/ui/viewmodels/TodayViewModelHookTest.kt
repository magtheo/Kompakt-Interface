package dev.magnor.kompakt.ui.viewmodels

import dev.magnor.kompakt.data.repository.TodayRepository
import dev.magnor.kompakt.domain.TodayProjection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * T-020 — the Today projection load fires the onLoaded hook exactly
 * once per emission (drives the local-reminder replan).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelHookTest {

    private lateinit var repo: RecordingRepo

    private class RecordingRepo(initial: TodayProjection) : TodayRepository {
        val flow = MutableStateFlow(initial)
        override fun observeToday(): Flow<TodayProjection> = flow
        override suspend fun today(): TodayProjection = flow.value
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val t0 = Instant.parse("2026-08-24T09:00:00Z")
    private val today = kotlinx.datetime.LocalDate(2026, 8, 24)

    @Test
    fun `projection load invokes hook with the projection`() = runTest {
        val projection = TodayProjection(date = today)
        repo = RecordingRepo(projection)
        val received = mutableListOf<TodayProjection>()
        val vm = TodayViewModel(repo, t0, onLoaded = { received.add(it) })

        vm.state.first { it.loaded } // collect until the emission lands

        assertEquals(listOf(projection), received)
    }

    @Test
    fun `hook is optional`() = runTest {
        val projection = TodayProjection(date = today)
        repo = RecordingRepo(projection)
        val vm = TodayViewModel(repo, t0) // no hook — must not throw
        vm.state.first { it.loaded }
        assertEquals(projection, vm.state.value.projection)
    }
}
