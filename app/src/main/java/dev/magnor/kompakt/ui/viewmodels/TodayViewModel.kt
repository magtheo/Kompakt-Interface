package dev.magnor.kompakt.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.magnor.kompakt.data.repository.CalendarRepository
import dev.magnor.kompakt.data.repository.TaskRepository
import dev.magnor.kompakt.data.repository.TodayRepository
import dev.magnor.kompakt.domain.CalendarEvent
import dev.magnor.kompakt.domain.RequestId
import dev.magnor.kompakt.domain.RevisionConflictException
import dev.magnor.kompakt.domain.Task
import dev.magnor.kompakt.domain.TaskStatus
import dev.magnor.kompakt.domain.TodayProjection
import dev.magnor.kompakt.ui.userMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * Today — operational overview (T-026 tabbed redesign). A projection
 * combining entities owned by the other repositories; this ViewModel
 * renders, it owns nothing — except the ONE scope-limited mutation D031
 * grants: quick-complete on the TASKS tab (revision-guarded).
 *
 * Degradation rules (T-024): the beyond-today hero join fails silently
 * to null; the projection itself keeps an honest static error line
 * (E-Ink rule: no spinners, reopening refetches).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    todayRepository: TodayRepository,
    val now: Instant,
    private val onLoaded: ((TodayProjection) -> Unit)? = null,
    private val calendarRepository: CalendarRepository? = null,
    private val taskRepository: TaskRepository? = null,
    private val newRequestId: () -> RequestId = { "req-today" },
) : ViewModel() {

    data class TodayUiState(
        val loaded: Boolean = false,
        val projection: TodayProjection? = null,
        val error: String? = null,
        /** One static line above the TASKS list (conflict / failure notice). */
        val notice: String? = null,
        /** Hero anchor: first remaining-today event, else first beyond-today. */
        val nextUp: CalendarEvent? = null,
        val pastEvents: List<CalendarEvent> = emptyList(),
        val remainingEvents: List<CalendarEvent> = emptyList(),
        val openTasks: List<Task> = emptyList(),
        /** Completed with updatedAt inside today (Oslo) — projection is due-scoped. */
        val doneToday: List<Task> = emptyList(),
    ) {
        /** Open items behind the ATTENTION tab — static count for the rail. */
        val attentionCount: Int get() = projection?.attention?.size ?: 0
    }

    private data class Load(val projection: TodayProjection? = null, val error: String? = null)

    private val zone = TimeZone.of("Europe/Oslo") // T-023 tz convention
    private val notice = MutableStateFlow<String?>(null)
    private val nextBeyondToday = MutableStateFlow<CalendarEvent?>(null)
    private val refreshTick = MutableStateFlow(0)

    /** Cold remote flow — the tick re-collects (T-012 refresh pattern). */
    private val loads = refreshTick.flatMapLatest {
        todayRepository.observeToday()
            .map { projection ->
                onLoaded?.invoke(projection) // T-020: local-reminder replan hook
                Load(projection)
            }
            .catch { e -> emit(Load(error = e.userMessage())) }
    }

    val state: StateFlow<TodayUiState> =
        combine(loads, nextBeyondToday, notice) { load, beyond, noticeText ->
            val projection = load.projection
                ?: return@combine TodayUiState(loaded = true, error = load.error)
            val events = projection.events.sortedBy { it.startAt }
            val (past, remaining) = events.partition { it.startAt <= now }
            if (remaining.isEmpty()) maybeFetchBeyond()
            val todayDate = now.toLocalDateTime(zone).date
            TodayUiState(
                loaded = true,
                projection = projection,
                notice = noticeText,
                nextUp = remaining.firstOrNull() ?: beyond,
                pastEvents = past,
                remainingEvents = remaining,
                openTasks = projection.tasks.filter { it.status == TaskStatus.OPEN },
                doneToday = projection.tasks.filter {
                    it.status == TaskStatus.COMPLETED &&
                        it.updatedAt.toLocalDateTime(zone).date == todayDate
                },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    /**
     * Hero fall-through: when nothing remains today, fetch the next 7 days
     * and keep the first future event. Display-only join — any failure
     * degrades to null (T-024); guarded so a stable value never re-fetches.
     */
    private fun maybeFetchBeyond() {
        val repository = calendarRepository ?: return
        if (nextBeyondToday.value != null) return
        viewModelScope.launch {
            val from = now.toLocalDateTime(zone).date
            val to = from.plus(7, DateTimeUnit.DAY)
            val beyond = runCatching {
                repository.fetchWindow(from.toString(), to.toString())
            }.getOrNull()
                ?.filter { it.startAt > now }
                ?.minByOrNull { it.startAt }
            if (beyond != null) nextBeyondToday.value = beyond
        }
    }

    /**
     * D031 quick-complete (TASKS tab): revision-guarded single mutation.
     * Conflict → notice + refresh; failure → honest userMessage; success →
     * refresh (tick re-collects the projection).
     */
    fun completeTask(task: Task) {
        val repository = taskRepository ?: return
        viewModelScope.launch {
            try {
                repository.completeTask(task.id, task.revision, newRequestId())
                notice.value = null
            } catch (e: RevisionConflictException) {
                notice.value = "Changed on server — showing latest"
            } catch (e: Exception) {
                notice.value = e.userMessage()
            } finally {
                refreshTick.value += 1
            }
        }
    }
}
