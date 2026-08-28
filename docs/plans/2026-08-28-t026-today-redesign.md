# T-026 — Today Redesign: Tabs + Cards Implementation Plan

> **For Hermes:** Implement task-by-task; every task ends green before the next.
> Ledger: verbose done entry in TASKS.md; branch `feat/T-026-today-redesign`.

**Goal:** Replace Today's five stacked sections with a three-tab surface
(NOW / TASKS / ATTENTION) — one dominant anchor per tab, no empty-section walls.

**Architecture:** Pure client change (server `/v1/today` projection unchanged).
`HorizontalPager` (androidx.compose.foundation, already in BOM 2024.10.01 — no new
dependency) provides swipe; a custom monochrome tab rail provides taps; both stay
in sync. `TodayViewModel` gains two collaborators (calendar fall-through join,
task completion) following the T-024 degrade rules: joins fail to null/empty,
primary errors surface as static lines.

**Locked decisions (user, Aug 28 2026 — recorded as D031):**
1. Tab set `NOW / TASKS / ATTENTION`.
2. Tab switching: swipe AND tap (user: "touch is fast, just the refresh rate that
   isn't").
3. Quick-complete lives on the TASKS tab only (tap ○ → ✓). NOW stays a pure view.
   Uses the existing `TaskRepository.completeTask` with `expected_revision` —
   this is the deliberate exception to "Today is a view, not a source of truth"
   and is scope-limited to that one action.
4. Agents section and Recent note are CUT from Today. Agents remain reachable via
   More → Agents and via attention deep-links (T-018); their future placement is
   explicitly open (user wants to consider it — do not delete any agent surface).

---

## Design reference (from the Aug 28 discussion)

```
Today · Fri 28 Aug                       [📅] [🔔]
──────────────────────────────────────────────
  NOW           TASKS (2)      ATTENTION ●2
──────────────────────────────────────────────            ← tab rail
[ NOW page ]   hero card "NEXT UP / Dentist / today 17:00 · in 2 h"
               now marker, remaining timeline, "anytime" task band
[ TASKS page ] ○/✓ rows, tap to complete, conflict → notice + refresh
[ ATTENTION ]  one bordered card per item, tap → deep-link
```

- **Empty sections do not render.** No placeholder rows anywhere except one
  honest line per tab when the tab itself is empty ("Nothing due · N done
  today", "All clear").
- **Hero fall-through:** when no events remain today, NEXT UP shows the first
  event after today (fetched via `CalendarRepository.fetchWindow`, degrade →
  null → hero hidden, page shows only the anytime band / honest line).
- **Past/future:** past timeline entries render in the standard secondary
  (subtitle) style — no new "dim" concept, high-contrast rule respected.

---

## Task 1 — ViewModel: next-up fall-through join

**Files:** `ui/viewmodels/TodayViewModel.kt`, test `ui/viewmodels/TodayViewModelTest.kt`

1. Constructor gains `calendarRepository: CalendarRepository?` (null = feature off /
   fake mode without calendar — mirror SurfaceGating semantics).
2. New state field `nextBeyondToday: CalendarEvent?`. When `projection.events`
  (events starting after `now`) is empty, fetch
  `fetchWindow(now, now + 7 days)` once per projection emission; first event
  wins; `.catch { null }` (T-024 rule — display-only join degrades silently).
3. Expose `nextUp: CalendarEvent?` = first remaining-today event, else
  `nextBeyondToday`.
4. **Tests:** (a) remaining-today event → nextUp = it, fetchWindow NOT called;
  (b) empty today → fetchWindow called, nextUp = tomorrow's first;
  (c) fetchWindow throws → nextUp null, no error surfaced;
  (d) null repository → nextUp from today only.

## Task 2 — ViewModel: quick-complete action

**Files:** `TodayViewModel.kt`, test file

1. Constructor gains `taskRepository: TaskRepository?`.
2. `suspend fun completeTask(task: Task)` → `completeTask(task.id, task.revision,
   RequestId.fresh())` (reuse the existing request-id helper used by capture);
   on `RevisionConflictException` → `notice = "Changed on server — refreshed"`
   + re-observe; on other failures → `notice = e.userMessage()`; success →
   notice null (projection re-emit updates the row).
3. State gains `notice: String?` (rendered as one static line at the top of the
   TASKS page; cleared on next successful load).
4. **Tests:** (a) happy path — repository receives id + task's revision;
  (b) conflict → notice set, no throw; (c) generic failure → userMessage notice.

## Task 3 — ViewModel: tab counts + open/done split

**Files:** `TodayViewModel.kt`, test file

1. Derived fields on the UI state: `openTasks` (due today, status OPEN),
   `doneTasks` (status COMPLETED, updated today), `attentionCount`.
2. **Tests:** split correctness incl. tasks due yesterday not counted;
   completed-a-week-ago not in done list.

## Task 4 — Screen: tab rail + pager scaffold

**Files:** `ui/screens/TodayScreen.kt` (+ small additions to `ui/screens/Common.kt`
only if a shared divider is needed)

1. `rememberSaveable` selected tab; `HorizontalPager(pageCount = 3)` bound to it;
   custom `TodayTabRow` — three `Text`-only buttons, selected = bold +
   1.dp bottom border, counts as static text (`TASKS (2)`, `ATTENTION ●2`);
   monochrome, no ripple (MMD semantics — reuse the ListRow/SectionLabel
   pattern family).
2. Title becomes `Today · Fri 28 Aug` (Europe/Oslo, kotlinx-datetime, same tz
   convention as T-023).
3. Existing `onOpenAttention` / `onOpenInbox` / `onOpenCalendar` params unchanged.

## Task 5 — NOW page

**Files:** `TodayScreen.kt`

1. `NowPage(projection, nextUp, now, onOpenEvent, onOpenTask)`:
   - hero `CardMMD` when `nextUp != null`: label NEXT UP, title (19 sp), time
     line `today 17:00 · in 2 h` / `tomorrow 09:00` (reuse `relativeTo`).
     Tap → event detail route.
   - now marker: one `HorizontalDivider`-equivalent row with `now 15:07` label.
   - remaining-today timeline rows: `17:00 — Title`, subtitle = location when
     present; past events in secondary style above the marker (still tappable).
   - anytime band: open tasks from Task 3 as `○ Title` rows (tap → item detail,
     T-025 `Routes.item(id, EntityKind.TASK)`).
   - all-empty page → single line "Nothing more today".

## Task 6 — TASKS page

**Files:** `TodayScreen.kt`

1. `TasksPage(openTasks, doneTasks, notice, onToggle, onOpenTask)`:
   open rows first (`○ Title`, trailing `today`), then done (`✓ Title`,
   secondary style). Tap on open row → `onToggle(task)` (quick-complete).
   Tap on done row → item detail (no un-complete in v1 — YAGNI).
2. Notice line renders above the list when set.

## Task 7 — ATTENTION page + section cuts

**Files:** `TodayScreen.kt`, `ui/KompaktApp.kt`, tests

1. `AttentionPage(attention, onOpenAttention, onOpenInbox)`: one `CardMMD` per
   item (existing T-018 deep-link logic moves here verbatim).
2. DELETE the Agents and Recent-note sections; remove `showAgentsSection` /
   `showRecentNote` params and their KompaktApp/test call sites
   (`showInboxAction` stays — bell remains). Empty page → "All clear".

## Task 8 — Wire-up + gate + ledger

1. `KompaktApp`: new params (`onOpenEvent` → EVENT_DETAIL route; task rows via
   `Routes.item`). AppContainer: inject calendar + task repositories into
   `TodayViewModel` factory (fake mode keeps null-safe fakes).
2. `bash scripts/verify.sh` green (assembleDebug + testDebugUnitTest + lintDebug).
3. UX doc (`docs/user-experience.md`) Today section rewritten to the tabbed
   design; decisions D031 already recorded; verbose TASKS.md done entry.

---

## Test inventory (new)

- TodayViewModelTest: +4 (fall-through), +3 (complete), +2 (split/counts)
- TodayScreenTest (if a Compose-test harness exists — check first; if the repo
  has none, cover rail counts via pure VM state and skip UI tests, matching
  existing convention)
- RoutesTest: unchanged (no new routes)

## Pitfalls (from skill/session history)

- `EventViewModel.calendars`-style `stateIn` traps: TodayViewModel tests must
  backgroundScope-subscribe before reading `.value`.
- Failing-repo wrappers: fail ONLY the method under test (the observeProject
  singular/plural lesson from T-024).
- Patch-tool ledger edits: anchor on a unique single-line prefix.
- `git diff TASKS.md` on every repo status read (stale-writer, 15 strikes).
- MockWebServer body assertions: no whitespace-stripping.
