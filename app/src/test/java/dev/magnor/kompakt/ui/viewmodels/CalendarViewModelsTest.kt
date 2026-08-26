package dev.magnor.kompakt.ui.viewmodels

import dev.magnor.kompakt.data.fake.FakeCalendarRepository
import dev.magnor.kompakt.data.repository.CaptureRepository
import dev.magnor.kompakt.domain.CaptureProposal
import dev.magnor.kompakt.domain.CaptureResult
import dev.magnor.kompakt.domain.CaptureType
import dev.magnor.kompakt.domain.EventDraft
import dev.magnor.kompakt.domain.RequestId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.yearMonth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-023: CalendarViewModel month navigation + windowed fetch, EventViewModel
 * form semantics (naive Europe/Oslo input → UTC wire, all-day, writable
 * gating), and the capture→event commit path (EVENT proposals bypass
 * /v1/capture/commit and POST /v1/events against the shared calendar repo).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelsTest {

    private val now = Instant.parse("2026-08-22T15:00:00Z") // Sat, 17:00 Oslo
    private val zone = TimeZone.of("Europe/Oslo")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- CalendarViewModel ----

    @Test
    fun `month navigation moves and clamps the selected day`() = runTest {
        val repo = FakeCalendarRepository()
        val vm = CalendarViewModel(repo, now, zone) { "r" }

        assertEquals(LocalDate.parse("2026-08-22").yearMonth, vm.state.value.month)
        assertEquals(42, vm.state.value.grid.size)

        vm.selectDay(LocalDate.parse("2026-08-22"))
        vm.nextMonth()
        // Aug selection does not carry into September.
        assertNull(vm.state.value.selectedDay)
        assertEquals(YearMonth.parse("2026-09"), vm.state.value.month)

        vm.previousMonth()
        assertEquals(YearMonth.parse("2026-08"), vm.state.value.month)
    }

    @Test
    fun `today is selected initially and goToToday restores it`() = runTest {
        val repo = FakeCalendarRepository()
        val vm = CalendarViewModel(repo, now, zone) { "r" }

        assertEquals(LocalDate.parse("2026-08-22"), vm.state.value.selectedDay)

        vm.nextMonth()
        vm.selectDay(LocalDate.parse("2026-09-15"))
        vm.goToToday()
        assertEquals(YearMonth.parse("2026-08"), vm.state.value.month)
        assertEquals(LocalDate.parse("2026-08-22"), vm.state.value.selectedDay)
    }

    @Test
    fun `window fetch lands seeded events on their Oslo day`() = runTest {
        val repo = FakeCalendarRepository()
        val vm = CalendarViewModel(repo, now, zone) { "r" }

        val aug22 = vm.state.value.eventsOn(LocalDate.parse("2026-08-22"))
        // personal:event_001 (Philosophy) seeds at 09:00Z = 11:00 Oslo same day.
        assertTrue(aug22.any { it.title == "Philosophy" })
    }

    @Test
    fun `create appends to the window and reports the outcome`() = runTest {
        val repo = FakeCalendarRepository()
        val vm = CalendarViewModel(repo, now, zone) { "req-1" }
        var created: dev.magnor.kompakt.domain.EventCreateResult? = null

        vm.createEvent(
            EventDraft("personal", "Tannlege visit", "2026-08-25T07:00:00Z"),
        ) { created = it }

        assertNotNull(created)
        assertTrue(created!!.id.startsWith("personal:"))
        assertTrue(vm.state.value.eventsOn(LocalDate.parse("2026-08-25")).any { it.title == "Tannlege visit" })
        assertEquals("Created \"Tannlege visit\"", vm.state.value.writeOutcome)

        // Delete removes it again.
        var deleted = false
        vm.deleteEvent(created!!.id) { deleted = it }
        assertTrue(deleted)
        assertTrue(vm.state.value.eventsOn(LocalDate.parse("2026-08-25")).none { it.title == "Tannlege visit" })
        assertEquals("Deleted", vm.state.value.writeOutcome)
    }

    // ---- EventViewModel ----

    @Test
    fun `new event defaults to today with plus one hour`() = runTest {
        val repo = FakeCalendarRepository()
        val vm = EventViewModel(repo, now, zone) { "r" }

        vm.load(null)

        val s = vm.state.value
        assertTrue(s.isNew)
        assertEquals("2026-08-22", s.date)
        assertEquals("17:00", s.start)
        assertEquals("18:00", s.end)
    }

    @Test
    fun `load prefills the form from the fetched event`() = runTest {
        val repo = FakeCalendarRepository()
        val vm = EventViewModel(repo, now, zone) { "r" }

        vm.load("personal:event_001") // Philosophy, 09:00Z = 11:00 Oslo

        val s = vm.state.value
        assertEquals("Philosophy", s.title)
        assertEquals("2026-08-22", s.date)
        assertEquals("11:00", s.start)
    }

    private fun TestScope.editorForCreate(repo: FakeCalendarRepository): EventViewModel {
        val vm = EventViewModel(repo, now, zone) { "req-9" }
        // save() gates on calendars.value — stateIn(WhileSubscribed) stays
        // empty until someone subscribes; keep it warm for the whole test.
        backgroundScope.launch { vm.calendars.collect {} }
        runCurrent() // let the collector start and stateIn collect the fakes
        vm.load(null)
        vm.setTitle("Tannlege visit")
        vm.setCalendarId("personal") // first writable
        vm.setDate("2026-08-25")
        vm.setStart("09:00")
        vm.setEnd("10:30")
        return vm
    }

    @Test
    fun `save converts naive oslo input to utc wire`() = runTest {
        val repo = FakeCalendarRepository()
        val vm = editorForCreate(repo)

        vm.save()

        assertTrue(vm.state.value.saved)
        val created = repo.events.value.first { it.title == "Tannlege visit" }
        // 09:00 CEST (Aug, UTC+2) → 07:00Z; 10:30 → 08:30Z.
        assertEquals("2026-08-25T07:00:00Z", created.startAt.toString())
        assertEquals("2026-08-25T08:30:00Z", created.endAt?.toString())
        assertEquals("personal", created.calendarId)
    }

    @Test
    fun `all day create sends no end`() = runTest {
        val repo = FakeCalendarRepository()
        val vm = editorForCreate(repo)
        vm.setAllDay(true)

        vm.save()

        val created = repo.events.value.first { it.title == "Tannlege visit" }
        assertTrue(created.allDay)
        assertNull(created.endAt)
    }

    @Test
    fun `end before start is rejected locally`() = runTest {
        val repo = FakeCalendarRepository()
        val vm = editorForCreate(repo)
        vm.setEnd("08:00") // before 09:00 start

        vm.save()

        assertEquals("End must be after start", vm.state.value.error)
        assertTrue(repo.events.value.none { it.title == "Tannlege visit" })
    }

    @Test
    fun `read only calendar is rejected`() = runTest {
        val repo = FakeCalendarRepository()
        val vm = editorForCreate(repo)
        vm.setCalendarId("sa") // read-only registry

        vm.save()

        assertEquals("Calendar is read-only", vm.state.value.error)
        assertTrue(repo.events.value.none { it.title == "Tannlege visit" })
    }

    @Test
    fun `edit patches an existing event`() = runTest {
        val repo = FakeCalendarRepository()
        val vm = EventViewModel(repo, now, zone) { "r" }
        vm.load("personal:event_001")

        vm.setTitle("Philosophy (moved)")
        vm.setDate("2026-08-23")
        vm.setStart("12:00")
        vm.save()

        assertTrue(vm.state.value.saved)
        val updated = repo.fetchEvent("personal:event_001")
        assertEquals("Philosophy (moved)", updated?.title)
        assertEquals("2026-08-23T10:00:00Z", updated?.startAt?.toString()) // 12:00 CEST
    }

    // ---- CaptureViewModel → event ----

    /** Minimal stub: only interpret matters — EVENT commits bypass commit(). */
    private class StubCaptureRepository(private val proposal: CaptureProposal) : CaptureRepository {
        override suspend fun interpret(input: String): CaptureProposal = proposal
        override suspend fun commit(proposal: CaptureProposal, requestId: RequestId): CaptureResult =
            CaptureResult.QueuedOffline
    }

    @Test
    fun `event capture commits via calendar repository`() = runTest {
        val calendars = FakeCalendarRepository()
        val proposal = CaptureProposal(
            proposedType = CaptureType.EVENT,
            title = "Møte med lege",
            text = "møte med lege i morgen",
            startAt = Instant.parse("2026-08-27T07:00:00Z"),
        )
        val vm = CaptureViewModel(StubCaptureRepository(proposal), calendars) { "req-c1" }

        vm.onTextChange("møte med lege i morgen")
        vm.interpret()
        vm.commit()

        // Result surfaced to the user and the event landed in the shared repo.
        assertEquals("Event created: Møte med lege", vm.state.value.result)
        val created = calendars.events.value.first { it.title == "Møte med lege" }
        assertEquals("personal", created.calendarId) // first writable registry
        assertEquals("2026-08-27T07:00:00Z", created.startAt.toString())

        // Idempotent replay: the same request id must not duplicate the event.
        assertEquals(1, calendars.events.value.count { it.title == "Møte med lege" })
    }

    @Test
    fun `event capture without start is rejected`() = runTest {
        val calendars = FakeCalendarRepository()
        val proposal = CaptureProposal(
            proposedType = CaptureType.EVENT,
            title = "Møte uten tid",
        )
        val vm = CaptureViewModel(StubCaptureRepository(proposal), calendars) { "req-c2" }

        vm.onTextChange("møte uten tid")
        vm.interpret()
        vm.commit()

        assertEquals("Event needs a start time", vm.state.value.error)
        assertTrue(calendars.events.value.none { it.title == "Møte uten tid" })
    }
}
