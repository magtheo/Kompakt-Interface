# T-051 Honest Load States — Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Every list screen tells the truth about what it is doing: Loading / Offline / genuinely empty — never a false "No X yet" while a fetch is in flight or the tunnel is down.

**Architecture:** Follows the repo's established T-024 UiState convention (`loaded` + `error` + data, `stateIn(WhileSubscribed(5s))`, initial `loaded=false`). Adds one app-scoped `TransportStatus` fed from `HttpApi.execute` so screens can distinguish offline from empty without changing any repository signature. `degradeTransport` is left untouched — the Sept-2 crash invariant (transport failures must never kill `stateIn`) is preserved exactly.

**Tech Stack:** Kotlin, Compose, StateFlow, JUnit (existing `verify.sh` gate: assemble + unit tests + lint).

---

## Audit findings (2026-09-26, evidence in repo)

- 🔴 **C1 — Chats lie during load.** `ChatScreens.kt:108` renders "No chats yet — Tap New chat above" while `threads` is still the `stateIn` initial `emptyList()`; `ChatScreens.kt:272` renders "No messages — Write below" for an old chat until the messages GET lands (1–2 s tunnel-up, 5–10 s cold dial).
- 🔴 **C2 — Offline renders as false-empty app-wide.** `RemoteRepositories.kt:108` `degradeTransport` swallows `OfflineException`/`ServerUnavailableException` into `emptyList()` at the repository boundary, so the VM-level `.catch { … error = e.userMessage() }` of the T-024 pattern (Organize/Today) can never see transport failures. Offline inbox shows "Inbox empty — all clear". Chats (C1) are the worst case of this systemic root.
- ⚠️ **W1 — Calendar and Agents screens lack the `loaded` flag entirely** (`CalendarScreens.kt:106`, `AgentScreens.kt:89,101,128,150,286`): same false-empty class as Chats.
- ⚠️ **W2 — No phase signal.** The 1–10 s wait is tunnel dial (cold) vs fetch (warm); no UI distinguishes them.
- ℹ️ **I1 — Reusable copy exists:** `Format.kt:84` maps `OfflineException` → "Server unreachable — check connection and reopen".
- ℹ️ **I2 — In-repo precedent to copy verbatim:** `OrganizeViewModels.kt:61-100` (ProjectsUiState/AreasUiState) + `OrganizeScreens.kt:84-87` screen rows.

**Root cause:** the failure taxonomy is fully modeled in the data layer but collapsed to "empty" before the UI layer can see it, and the chat/calendar/agent VMs predate the T-024 UiState convention.

**Locked decision (D037, user go Sep 28):** keep `degradeTransport`'s swallow (Sept-2 invariant), surface transport health via an app-scoped status fed at the single choke point `HttpApi.execute`. Alternative rejected: changing repository signatures to `Flow<FetchResult<T>>` — ~10 repos + fakes + every VM rewritten, churn without user-visible gain.

---

## Task 1: TransportStatus in HttpApi

**Objective:** Single source of truth for "the server is currently unreachable", fed from every request.

**Files:**
- Modify: `app/src/main/java/dev/magnor/kompakt/data/remote/HttpApi.kt`
- Test: `app/src/test/java/dev/magnor/kompakt/data/remote/HttpApiTest.kt` (extend existing)

**Design:**

```kotlin
sealed interface TransportStatus {
    data object Idle : TransportStatus            // no request completed yet
    data object Ok : TransportStatus              // last request succeeded
    data class Degraded(val at: Instant, val cause: String) : TransportStatus
}
```

In `HttpApi`: `private val _transport = MutableStateFlow<TransportStatus>(TransportStatus.Idle)` +
`val transport: StateFlow<TransportStatus> = _transport.asStateFlow()`.
In `execute()`'s existing catch-taxonomy site: on `OfflineException`/`ServerUnavailableException` → `_transport.value = Degraded(Instant.now-ish clock, e.userMessage-ish cause)`; on successful return → `_transport.value = Ok`. Never throws from these assignments (wrap in runCatching to honor the Sept-2 spirit).

**Steps:** write failing test (fake server refusing connection → transport becomes Degraded; successful call → Ok) → run `./gradlew :app:testDebugUnitTest --tests '*HttpApi*'` expect FAIL → implement → PASS → commit `T-051: transport status in HttpApi`.

## Task 2: Expose TransportStatus on AppContainer

**Files:** Modify `data/AppContainer.kt` — `val transportStatus: StateFlow<TransportStatus> get() = api.transport` (single HttpApi instance already app-scoped; check SwitchChat/Switch* wrappers route through the same api). No test needed beyond compile (covered by Task 1).

## Task 3: ChatListViewModel → ChatListUiState

**Objective:** One combined UiState, T-024 pattern, replacing the three bare StateFlows.

**Files:**
- Modify: `ui/viewmodels/ChatViewModels.kt:41-91`
- Test: `app/src/test/java/dev/magnor/kompakt/ui/viewmodels/ChatListViewModelTest.kt` (new; use existing FakeRepositories)

```kotlin
data class ChatListUiState(
    val loaded: Boolean = false,
    val threads: List<ChatThread> = emptyList(),
    val topics: List<ChatTopic> = emptyList(),
    val workspaces: List<Workspace> = emptyList(),
)
val state: StateFlow<ChatListUiState> = combine(
    chatRepository.observeThreads(),
    topicRepository.observeTopics(),
    workspaceRepository.observeWorkspaces(),
) { t, tp, w -> ChatListUiState(loaded = true, threads = t, topics = tp, workspaces = w) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatListUiState())
```

Keep `created`, `error`, `newChat()` untouched. Note: `combine` waits for all three one-shot fetches → `loaded=true` only when the list is actually renderable (acceptable: server GETs run in parallel, ~1 s warm).

**TDD:** test 1 — initial state `loaded=false`; test 2 — after fakes emit → `loaded=true` + data present; test 3 — topics/workspaces failing (fake throws) degrades via `degradeTransport`… fakes bypass `degradeTransport`, so assert only threads behavior there; the offline path is covered by screen-level status (Task 4) instead. Commit.

## Task 4: ChatListScreen tri-state rows

**Files:** Modify `ui/screens/ChatScreens.kt:59-121`.

Replace `threads/topics/workspaces` collectAsState with single `state`. Rendering:

```kotlin
when {
    !state.loaded && transport is TransportStatus.Degraded ->
        ListRow(title = "Offline — server unreachable", subtitle = "Will load when connection returns")
    !state.loaded -> ListRow(title = "Loading chats…")
    state.threads.isEmpty() -> ListRow(title = "No chats yet", subtitle = "Tap New chat above")
    else -> state.threads.forEach { … existing row … }
}
```

`transport` collected from the container's `transportStatus`. Keep scope-picker logic (topics/workspaces from `state`). Commit.

## Task 5: ChatThreadViewModel loaded flag

**Files:** Modify `ui/viewmodels/ChatViewModels.kt:123-148` (+ test file new `ChatThreadViewModelTest.kt`).

Minimal, no refactor of overlay/send machinery:

```kotlin
val loaded: StateFlow<Boolean> = messages.map { true }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
```

(`messages` re-emits per refreshTick; `map{true}` on a StateFlow stays `true` after first load — deliberate: post-send refreshes must NOT flash "Loading". Cold re-entry shows old data until the refetch swaps in — acceptable, and becomes instant with the future snapshot cache.) Tests: initial false → becomes true once fake messages emit. Commit.

## Task 6: ChatThreadScreen rows

**Files:** Modify `ui/screens/ChatScreens.kt:272-274`.

```kotlin
if (messages.isEmpty()) {
    item(key = "empty") {
        when {
            !loaded && transport is TransportStatus.Degraded ->
                ListRow(title = "Offline — server unreachable", subtitle = "History loads when connection returns")
            !loaded -> ListRow(title = "Loading messages…")
            else -> ListRow(title = "No messages", subtitle = "Write below")
        }
    }
}
```

Commit.

## Task 7: Calendar list screen

Same treatment: `CalendarViewModel` gains `EventsUiState(loaded, events)` (or additive `loaded` if the VM shape resists wrapping — match whichever the file favors; check `CalendarViewModel.kt:36` shape first). `CalendarScreens.kt:106` renders Loading / Offline / "No events" tri-state. Test + commit.

## Task 8: Agents list screen

`AgentsListViewModel` (`AgentViewModels.kt:51`) currently exposes raw surface/runs StateFlows — wrap the list screen's data in `AgentsUiState(loaded, surface, runs)`; `AgentScreens.kt:89-150` tri-state ("Loading agents…" / offline / existing empties). `AgentDetailScreen`/`AgentRunDetailScreen` (line 158+, 310+) get the same additive-`loaded` treatment as Task 5. Test + commit.

## Task 9: Tunnel-phase subtitle (W2)

**Files:** Modify `data/AppContainer.kt` (expose `tunnelState: StateFlow<TunnelController.State>`), `ChatScreens.kt` loading rows.

Loading row subtitle becomes phase-aware: tunnel not Up → `"Connecting — secure tunnel"`; tunnel Up → `"Fetching"`. Static text only (e-ink). Commit.

## Task 10: Gate, install, device smoke, ledger

1. `cd ~/Documents/repos/Kompakt-Interface && export JAVA_HOME=/home/theo/tools/jdk-17.0.20.1+1 && ./scripts/verify.sh` — must be green.
2. `adb install -r app/build/outputs/apk/debug/app-debug.apk`; confirm `RecentsKeyService` still bound (never force-stop).
3. Smoke on device: (a) tunnel already up → open Chats: brief "Loading chats…" then list; (b) after lock (tunnel down) → open Chats: "Loading chats… / Connecting — secure tunnel" during dial, then list; (c) kill coordinator briefly → offline row shows, restore → recovers on reopen.
4. TASKS.md: T-051 VERIFIED with evidence only after smoke. Vault journal entry for the install. Commit ledger.

---

## Explicitly out of scope (follow-up plan T-052, needs its own approval)

Snapshot cache (stale-while-revalidate: render last JSON instantly + "Updated HH:MM · refreshing…"), cache warming in idle sync windows (depends on fix A landing).

---

## Execution addendum (2026-09-30)

Executed task-by-task via subagent-driven development (implementer + two-stage
reviewer per task). All tasks APPROVED; suite 356 green; `scripts/verify.sh` OK.

**Amendment to D037 (locked during execution):** on LIST screens, the empty
branch is now transport-aware — `loaded && empty && Degraded` renders the
Offline row ("Can't confirm empty while offline") instead of genuine-empty
copy. Reason: `degradeTransport` swallows offline failures into empty
emissions, so a landed empty list offline may be fake; honest-first. Chat
THREAD deliberately excluded (composer always renders; rare case).

**Commit map:** f36216e T1 · 7c462e6 T2 · 0ade032 T3 · 2ea759f T4 · 68b72cf T5
· 3639c57 T6 · ec73b08 T7 · 11c3464 T8 · b72a2f0 T8b (detail loaded, error
row, amended empty, catch-preserves) · 851606c T9 (tunnel-phase subtitle;
flow injected via ctor — controller lives in KompaktApplication).

**Accepted minors (follow-up tickets, none blocking):** Calendar month-nav
can flash a false "No events" for days outside the loaded window mid-refetch;
AgentDetail collects observeSurface a 3rd time (extra GET per entry); detail
VM one-shot chains lack defensive catch (pre-existing); thread "No messages"
gate ignores Degraded (per amendment scope); detail screens after non-transport
throw retry only on screen re-entry.

**Remaining:** device install + smoke (USB), merge, re-apply stashed
unrelated CalendarScreens fix on main.
