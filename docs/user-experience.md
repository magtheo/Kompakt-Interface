# User Experience

## Purpose

This document defines the intended user experience for the Mudita Kompakt personal interface.

The UX should be designed for:

- a small E-Ink display,
- short interactions,
- voice-first input,
- low cognitive load,
- low distraction,
- minimal visual motion,
- and a server that performs most of the complex work.

The user should rarely need to browse, type long text, or navigate deep hierarchies.

---

## UX Goal

The app should feel like:

> A calm personal control panel that shows what matters, lets the user capture intent, and delegates complexity to the server.

It should not feel like:

- a desktop shrunk onto a phone,
- a conventional Android productivity suite,
- a chat application pretending to be everything,
- or an animated dashboard.

---

## Core Interaction Modes

The interface has several distinct modes.

### Today

Purpose:

> Orient me.

Shows what matters now.

---

### Chat

Purpose:

> Let me think, ask, explore, or discuss.

A normal conversational interface with multiple chat threads.

---

### Agents

Purpose:

> Show me what my autonomous workers are doing.

Focused on status, runs, results, and intervention.

---

### Tasks

Purpose:

> Show me what I need to do.

Structured commitments.

---

### Notes

Purpose:

> Let me capture and retrieve information.

Persistent thoughts and information.

---

### Inbox

Purpose:

> Show me what needs my attention.

Aggregated from multiple domains.

---

## Navigation

Preferred top-level navigation:

```text
Today | Chat | Agents | More
```

`More` opens:

```text
Tasks
Notes
Projects
Inbox
Settings
```

This keeps the highest-frequency modes directly accessible while limiting persistent navigation clutter.

An alternative five-item navigation may be considered later if real-device testing shows it works better.

---

## Today Screen

Today should be the default opening screen.

It is an overview, not a management surface — one exception granted by
D031: quick-complete on the TASKS tab.

T-026 redesigned it as **three swipe/tap tabs** (E-Ink aware: touch is
fast, refresh rate is the constraint — one refresh per tab switch):

```text
┌────────────────────────────┐
│ Today · Fri 28 Aug    📅 🔔 │
├────────────────────────────┤
│ NOW   TASKS 3   ATTENTION 1│
├────────────────────────────┤
│ ┌────────────────────────┐ │
│ │ NEXT UP                │ │
│ │ Dentist                │ │
│ │ 15:00 · in 1h          │ │
│ └────────────────────────┘ │
│ LATER                      │
│ 17:00 Team sync            │
│ EARLIER                    │
│ 09:00 Standup        (dim) │
│ · now ·                    │
│ ANYTIME                    │
│ ○ Buy groceries            │
└────────────────────────────┘
```

- **NOW** — hero NEXT UP card (falls through to tomorrow's first event
  when today is empty), relative times, now-marker timeline, untimed
  tasks in an Anytime band. A view: no mutations.
- **TASKS** — due-today list with whole-row quick-complete (D031),
  Done-today section below, static notice line for conflicts/errors.
- **ATTENTION** — one bordered card per item, deep-links preserved (T-018).

Agents and recent note were cut from Today (Aug 2026); agent results
surface as ATTENTION items. Agent placement in the UI is explicitly
reopened for a later decision.

### Rules

- empty sections render nothing — no wall of placeholder rows,
- show only a small number of high-value items,
- avoid long lists,
- use "See all" when needed,
- prioritize the next event and current obligations,
- avoid decorative widgets,
- do not make Today itself editable beyond simple quick actions.

---

## Chat UX

Chat should feel familiar and simple.

### Chat list

```text
CHATS

General
Kodeverket
Linux
Travel research
Temporary chat

[New chat]
```

Each row may show:

- title,
- last activity,
- optional project label.

Avoid dense previews.

---

### Chat thread

```text
GENERAL

                    ┌─────────────────────────┐
                    │ How does X compare to Y?│
                    └─────────────────────────┘
                    You · 09:12
Markdown answer renders here: **bold** as
weight, `code` as monospace, bullets,
fenced code in a bordered block.

Assistant · 09:13

────────────────────────────
[ Message              (➤) ]
```

Implemented layout rules (T-013/T-014/T-015):

- transcript scrolls under a fixed top bar; the composer never
  lives below the fold,
- monochrome sender coding: user = right-shifted bordered card,
  semi-bold; assistant = full-width plain text,
- every message carries a `Sender · HH:mm` meta line; delivery
  state rides on glyphs (pending/failed), never color,
- one compact composer row: field + send icon button beside it
  (bottom-aligned so it tracks the last line as the draft grows),
  placeholder instead of a floating label,
- tapping a message opens its action row:
  - **Edit** — rewrite the conversation from here,
  - **Revert to here** — drop everything after it,
  - **Regenerate** — drop the reply and ask again,
  all destructive, no branches (see D026),
- a Jump-to-message inline index provides fast navigation in long
  threads,
- threads auto-title from the first user message server-side;
  explicit titles are never overwritten.

Scope tiers (T-022d):

- every thread has a scope — **General**, **Topic** (vault-seeded), or
  **Workspace** (repo-bound) — shown as a scope row under the thread
  header and as a label on list rows,
- the new-chat action expands into a picker (same pattern as the agent
  workspace picker): General, one row per topic, one row per known
  workspace,
- sending on a General thread may surface a "Move to \<topic\>?" chip —
  Move applies the scope (explicit tap), Not now dismisses it locally;
  the chip never re-routes a message by itself,
- Workspace threads show a pending-turn indicator while the backend
  runs; the reply settles asynchronously and the repo auto-commits per
  successful turn,
- re-scoping — or clearing back to General — happens from the header
  scope row; always an explicit action, never inferred.

### Chat principles

- multiple persistent threads,
- chat remains separate from tasks and notes,
- no silent conversion into structured objects,
- explicit actions may include:
  - Save as note,
  - Create task,
  - Send to agent,
  - Attach to project.

Long responses render as markdown — the restricted subset defined
in D027 — rather than being summarized or paged.

---

## Agent UX

Agents should not look like chat contacts.

They represent workers and processes.

### Agent list

```text
AGENTS

● Project Coordinator
  Running

○ Job Search
  Idle

! Research Agent
  Needs input

RECENT RUNS

✓ PR #55 audit
● Course research
```

Use clear static status markers.

Avoid animated spinners.

Possible statuses:

- Idle
- Running
- Waiting
- Needs input
- Completed
- Failed

---

### Agent detail

```text
PROJECT COORDINATOR

Status
Running

Current objective
Check cross-spec drift

Latest activity
Comparing semantic model

[Message agent]
[Stop]
[Recent runs]
```

---

### Agent run detail

```text
PR #55 AUDIT

● Summary — Running · started 22:41
────────────────────────────────
user      Fix the flaky login test
agent     Running: npm test …
tool      bash (dim one-liner)
agent     3 findings so far …
────────────────────────────────
[ Message the session      (➤) ]
[ Steer instead (mid-run) ]

Details ▾   ·   Run command ▾
[ Discuss in chat ] [ Task ] [ Note ]
```

Implemented as the same transcript-first layout as chat threads
(T-013/T-015): dialogue events render in full (markdown-capable),
process events collapse to dim one-liners; status collapses into
one summary card with expandable Details; commands live behind an
inline expandable section (no overlay menus — e-ink ghosting);
transitions compact to a Discuss/Task/Note row. The composer
appears only for resumable, non-terminal runs; Steer shows only
when the backend advertises live steering.

"Discuss" may open a chat seeded with relevant context.

---

## Task UX

Task lists should remain compact.

### Task list

```text
TASKS

TODAY

○ Review PR
○ Call dentist
○ Buy food

UPCOMING

○ Submit application
○ Read chapter
```

### Task detail

```text
REVIEW PR

Project
Kodeverket

Due
Today

Notes
Agent review available.

[Complete]
[Postpone]
[Ask agent]
[More]
```

### Task principles

- one-tap completion,
- fast postpone,
- clear due state,
- avoid excessive metadata,
- project context visible but secondary,
- agent-related actions explicit.

---

## Notes UX

Notes should optimize for capture.

### Notes list

```text
NOTES

Idea about agent UI
Philosophy thought
Kodeverket pricing
Shopping idea
```

### Note detail/editor

```text
NOTE

I think the agent interface should...

[Save]
```

### Notes principles

- very low capture friction,
- organization optional,
- full-screen text entry is acceptable,
- project association can happen later,
- server may suggest classification but not force it.

Implemented model (D028):

- the list is a walk-index of vault files: scratchpad pinned first,
  then notes newest-first, capped at 200 rows,
- the editor edits the whole file text; Save sends the text with the
  checksum it loaded — if the file changed underneath, the server
  answers 409 with the fresh copy, the editor reloads, nothing is lost,
- deliberate note saves create individual files under `00 - Inbox/`;
  quick captures keep using the scratchpad commit path,
- a sectioned scratchpad opens read-view as per-section triage cards
  (T-022b): New note in… / Append to… / Create task / Keep / Discard.
  Consuming actions perform the external effect first and remove the
  source section second — a crash in between duplicates the section,
  never loses it. Create task derives without consuming; Discard's
  undo is git history.

---

## Universal Capture

Universal capture should be available from the main surfaces.

Possible first screen:

```text
CAPTURE

🎙 Speak

or

[Type]

Save as:

[Note] [Task] [Chat] [Agent]
```

A more advanced flow may allow the server to infer intent:

```text
"I need to remember to call the dentist tomorrow."

Suggested:

TASK
Call dentist
Tomorrow

[Confirm]
[Change]
```

The system must not silently create structured objects without confirmation.

---

## Inbox UX

Inbox aggregates attention.

Example:

```text
INBOX

! Agent needs approval
  Kodeverket audit

✓ Agent finished
  Summer course research

! Task overdue
  Call dentist

! Server warning
  Backup failed
```

Selecting an item opens the real source object.

Inbox should support:

- open,
- archive/dismiss where appropriate,
- simple approval,
- simple rejection,
- postpone when relevant.

Inbox should not become a second task system.

---

## Project UX

Projects should be compact projections rather than desktop dashboards.

Example:

```text
KODEVERKET

Current goal
Prepare next release

Next action
Review PR #55

Agents
● Audit running

Needs attention
1 item

Recent
PR created
Tests fixed
```

Projects mainly provide context and navigation.

---

## Cross-Domain Actions

The domains remain separate but connected.

Supported explicit transitions may include:

```text
Chat → Save as note
Chat → Create task
Chat → Send to agent

Agent result → Discuss in chat
Agent result → Create task
Agent result → Save as note

Task → Ask agent
Task → Open project

Note → Discuss in chat
Note → Create task
```

Implemented so far: Chat → Save as note (T-022b — verbatim reply
snapshot, chat provenance in frontmatter, assistant messages only) and
Note → Create task (T-022b — derive-only, the section is not consumed).
The rest remain deliberate future transitions, never automatic.

These transitions should always be visible.

---

## E-Ink UX Rules

### No animation by default

Avoid:

- slide transitions,
- fades,
- animated loaders,
- ripples,
- pulsing indicators,
- animated progress bars.

Prefer:

- instant page replacement,
- static status text,
- static symbols,
- manual refresh when appropriate.

---

### Limit redraws

Do not constantly update:

- timers,
- live clocks,
- token streams,
- progress percentages.

For running agents, prefer:

```text
Running
Last update: 2 min ago
[Refresh]
```

rather than a continuously changing UI.

If streaming chat is implemented, consider batching updates rather than redrawing for every token.

---

### High contrast

Prefer:

- black,
- white,
- clear borders,
- typography hierarchy,
- spacing,
- icon shape.

Do not depend on color to communicate status.

---

### Large touch targets

The display is small, but interactions should remain forgiving.

Prioritize:

- large rows,
- full-row tap targets,
- few small icons,
- explicit labeled actions.

---

### Short navigation depth

Common actions should be reachable in:

- 0–2 taps from Today,
- or one voice/capture action.

Avoid deep settings-style hierarchies.

---

## Voice UX

Voice is expected to be the primary text-entry method.

Ideal sequence:

1. tap microphone,
2. speak,
3. transcription appears,
4. correct one or two words,
5. submit.

The user should not be forced into a separate "voice assistant" mode.

Voice should simply be a fast way to fill:

- chat,
- note,
- task,
- agent request.

Implemented (T-021 / V-059):

- recording is explicit — tap to start, tap to stop; no background or
  always-on audio. The clip is a request-scoped temp file on the server
  and is deleted after transcription, success or failure,
- the mic appears in all four composers (capture, chat thread, agent
  dispatch objective, run steering) and is hidden when the feature or
  capability is absent,
- button state is glyph-coded — stop square while recording, spinner
  while uploading, red retry after a failure — with a status line
  naming the failure reason,
- the transcript lands as editable text in the draft (empty draft →
  replace, existing text → space-join); a silent clip answers
  "Nothing recognized — try again", which is itself the full-loop proof.

---

## Chat Response Presentation

Long LLM responses should be adapted to E-Ink.

Prefer:

- concise answer first,
- bullets,
- optional "More",
- clear sections,
- small number of actionable buttons.

Example:

```text
RESEARCH COMPLETE

Bottom line
Fedora 44 supports X, but Y remains limited.

Key points
• ...
• ...
• ...

[More]
[Ask follow-up]
[Save note]
```

Do not dump large markdown-heavy documents into a tiny scrolling surface unless the user explicitly opens a detailed view.

---

## Notifications

Notifications should be rare and meaningful.

Good candidates:

- task due,
- important reminder,
- agent needs input,
- agent completed when the result matters,
- server warning,
- important project blocker.

Avoid:

- routine background progress,
- every agent log message,
- passive informational noise.

The notification philosophy should be:

> Interrupt only when there is a reason for the user to act or know now.

Implemented delivery paths (T-019/T-020):

- native in-app SSE while the app is open (`GET /v1/alerts/stream`;
  unread replay on connect, visually idempotent by alert id),
- periodic WorkManager sync as the background fallback,
- local reminder scheduling via AlarmManager for time-critical items,
- ntfy remains a backup channel only.

---

## Offline UX

When offline:

- Today may show cached content,
- tasks can be completed locally,
- notes can be created,
- chat/agent requests can be queued,
- the UI must clearly indicate queued state.

Example:

```text
Queued — will send when online
```

Avoid blocking basic capture because connectivity is unavailable.

---

## Error UX

Errors should be actionable and calm.

Bad:

```text
HTTP 502
```

Better:

```text
Server unavailable

Your note was saved locally.
It will sync automatically.

[Retry]
```

For authentication failure:

```text
This device is no longer authorized.

[Re-enroll]
```

---

## First Version UX Scope

The first coherent version should support:

### Today
- next event,
- today tasks,
- attention items,
- agent status,
- capture.

### Chat
- chat list,
- create chat,
- open thread,
- send message.

### Agents
- list agents,
- list recent runs,
- inspect run,
- simple actions.

### Tasks
- list,
- complete,
- postpone,
- detail.

### Notes
- list,
- create,
- edit.

### Inbox
- list,
- open source item,
- basic decision actions.

### Shared
- capture,
- item detail,
- settings,
- offline/connection state.

---

## UX Questions Still Open

To validate on real hardware:

- exact typography sizes,
- exact bottom-navigation layout,
- whether four top-level destinations fit comfortably,
- best jump-scroll step,
- whether chat needs pagination,
- whether server responses should be aggressively summarized,
- how much Today can show before it becomes crowded,
- whether physical buttons can or should be mapped,
- how notifications behave on MuditaOS K,
- whether the universal capture control should be persistent or screen-specific.

These should be answered through real-device use rather than speculation.

