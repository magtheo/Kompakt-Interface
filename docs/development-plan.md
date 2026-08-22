# Development Plan

## Purpose

This document turns the product, UX, architecture, protocol, and security specifications into an ordered implementation plan.

It is intended to be usable by both a human developer and autonomous coding agents.

The plan prioritizes:

1. validating the thin-client architecture early,
2. avoiding unnecessary device-specific work before the Mudita Kompakt arrives,
3. keeping the server authoritative,
4. implementing security boundaries before privileged features,
5. delivering coherent end-to-end vertical slices before broadening scope.

---

# 1. Development Principles

## 1.1 Build vertical slices, not isolated subsystems

Prefer:

```text
server endpoint
    ↓
client repository
    ↓
view model
    ↓
screen
    ↓
real user interaction
```

over building large backend or UI layers with no end-to-end path.

## 1.2 Do not redesign landed decisions during implementation

Source-of-truth documents:

- `vision.md`
- `user-experience.md`
- `technical-architecture.md`
- `protocol-and-sync.md`
- `security.md`
- `decisions.md`

Implementation agents should follow accepted decisions, flag contradictions, and record new decisions rather than silently replacing architecture.

## 1.3 The server remains authoritative

The Android app must not introduce:

- a second project hierarchy,
- a second task taxonomy,
- a local canonical note store,
- a parallel agent model.

Local data is projection/cache only.

## 1.4 Build for Kompakt constraints from day one

Even before the real device arrives:

- no animated navigation,
- monochrome UI,
- minimal redraw,
- static status indicators,
- large touch targets,
- small-screen layouts,
- jump/discrete scrolling where useful.

---

# 2. Phase 0 — Repository and Tooling Foundation

## Goal

Create a clean Android project and development environment with no product logic yet.

## Tasks

- Create the application repository.
- Choose package/application ID.
- Add Kotlin.
- Add Jetpack Compose.
- Add Mudita MMD.
- Add Compose Navigation.
- Add Coroutines / StateFlow.
- Add DataStore.
- Add serialization library.
- Add HTTP client.
- Add test dependencies.
- Add lint/static analysis.
- Add `.gitignore`.
- Add `LICENSE`.
- Add `README.md`.
- Add `SECURITY.md`.
- Add `/docs` and copy the source-of-truth specifications.
- Add CI for build, unit tests, and lint.

## Recommended licensing direction

If no substantial GPL code is copied from KompaktCalendar, prefer Apache-2.0.

KompaktCalendar remains reference material unless licensing is intentionally changed.

## Acceptance criteria

- debug APK builds successfully,
- APK installs on a normal Android phone/emulator,
- MMD renders correctly,
- CI passes from a clean checkout,
- no release secrets exist in CI.

---

# 3. Phase 1 — App Shell and E-Ink Navigation

## Goal

Prove the complete navigation structure before connecting real data.

## Implement

Top-level navigation:

```text
Today
Chat
Agents
More
```

`More`:

```text
Organize
Inbox
Settings
```

`Organize`:

```text
Projects
Areas
Tasks
Notes
```

## Screens

Create placeholder versions of:

- Today
- Chat list
- Chat thread
- Agents list
- Agent detail
- Agent run detail
- Organize
- Projects
- Areas
- Tasks
- Notes
- Inbox
- Generic item detail
- Capture
- Settings
- Diagnostics

## E-Ink rules

- disable navigation transitions,
- no ripple effects,
- no animated loaders,
- no decorative motion,
- use MMD where appropriate.

## Acceptance criteria

A user can navigate the entire information architecture using mocked data.

---

# 4. Phase 2 — Domain Model and Client Data Layer

## Goal

Implement client-side models and repository boundaries before feature-specific networking.

## Domain objects

Implement:

- `ChatThread`
- `Message`
- `Agent`
- `AgentRun`
- `Task`
- `Note`
- `Project`
- `Area`
- `InboxItem`
- `Action`
- `TodayProjection`
- `CapabilitySet`

All synchronizable entities should support:

```text
id
revision
updated_at
```

where applicable.

## Repository interfaces

Examples:

- `TodayRepository`
- `ChatRepository`
- `AgentRepository`
- `TaskRepository`
- `NoteRepository`
- `OrganizationRepository`
- `InboxRepository`
- `CaptureRepository`
- `SyncRepository`

Use mock/fake repositories first.

## Acceptance criteria

- UI screens depend on repository interfaces rather than hard-coded data,
- mock repositories populate every primary screen,
- UI state uses StateFlow or equivalent immutable state.

---

# 5. Phase 3 — Server Contract Skeleton

## Goal

Create the minimum real server API needed for the app to connect safely.

## Implement first

### Capabilities

Canonicalize:

```text
GET /v1/capabilities
```

Suggested response:

```json
{
  "server_protocol": 1,
  "minimum_client_protocol": 1,
  "features": {
    "today": true,
    "chat": true,
    "agents": true,
    "projects": true,
    "areas": true,
    "tasks": true,
    "notes": true,
    "inbox": true,
    "offline_capture": true
  }
}
```

### Health/status

```text
GET /v1/status
```

### Initial read endpoints

```text
GET /v1/today
GET /v1/inbox
GET /v1/projects
GET /v1/areas
GET /v1/tasks
GET /v1/notes
GET /v1/agents
GET /v1/agent-runs
GET /v1/chats
```

## Acceptance criteria

- Android client connects to development server,
- compatibility negotiation works,
- unsupported protocol gives a clear error,
- unknown optional fields do not crash the client,
- app renders live Today and Organize data.

---

# 6. Phase 4 — Device Enrollment and Security Boundary

## Goal

Implement security before meaningful writes.

## Implement

### Device identity

- generate asymmetric key pair,
- store private key via Android Keystore,
- send public key through enrollment flow.

### Server device record

At minimum:

```text
device_id
public_key
trust_class
status
capabilities
created_at
last_seen
```

### Device states

```text
pending
active
revoked
```

### Enrollment UX

```text
Install app
    ↓
Enter/scan server address
    ↓
Generate device identity
    ↓
Request enrollment
    ↓
Approve from trusted/admin device
    ↓
Active
```

### Revocation

Implement server-side revocation before v0.1 writes.

## Acceptance criteria

- fresh install has no authority,
- enrolled device gains only configured capabilities,
- revoked device loses access,
- modified requests cannot bypass server authorization,
- Kompakt-class device cannot invoke forbidden admin endpoints.

---

# 7. Phase 5 — Today + Organize Vertical Slice

## Goal

Deliver the first genuinely useful daily-life version.

## Today

Live data:

- next event if available,
- today's tasks,
- attention items,
- active/recent agent status,
- quick capture entry.

## Organize

### Projects

Render coordinator/vault projects.

### Areas

Render vault areas.

### Tasks

Support:

- list,
- filter by project,
- filter by area,
- complete,
- postpone where supported,
- open detail.

### Notes

Support:

- browse recent notes,
- filter by project/area,
- open read view.

Do not implement unrestricted vault editing.

## Acceptance criteria

The user can answer:

- what matters today?
- what tasks are due?
- which project/area does this belong to?
- what recent notes exist?

---

# 8. Phase 6 — Capture Pipeline

## Goal

Make the phone useful for rapid personal capture.

## Initial input

Support typed input first. Voice can follow immediately afterward.

## Flow

```text
Capture
    ↓
text/audio
    ↓
server interpretation
    ↓
proposal
    ↓
user confirm/change
    ↓
commit
```

Supported proposed types:

- Task
- Note
- Agent request
- Chat

## API concept

```text
POST /v1/capture/interpret
POST /v1/capture/commit
```

## Requirements

- server proposes structure,
- user confirms before structured creation,
- writes route through coordinator/vault rules,
- no local parallel taxonomy.

## Offline

Task/note capture should support queued offline operation.

## Acceptance criteria

User can enter:

> Call dentist tomorrow.

and receive:

```text
TASK
Call dentist
Tomorrow
```

then confirm creation.

---

# 9. Phase 7 — Chat

## Goal

Add a conventional general-purpose conversational interface without mixing it with task/note semantics.

## Implement

### Chat list

- create chat,
- open chat,
- title,
- recent activity.

### Chat thread

- send message,
- receive assistant response,
- display concise structured text.

### Cross-domain actions

Explicit actions only:

- Save as note,
- Create task,
- Send to agent,
- Attach to project.

## Constraints

- no arbitrary HTML,
- no WebView-driven responses,
- no assumption that chat is an agent,
- no silent task/note creation.

## v0.1 connectivity

Chat requires connectivity.

## Acceptance criteria

Multiple persistent chat threads work and remain clearly separate from Agents and Organize.

---

# 10. Phase 8 — Agents and Agent Runs

## Goal

Expose server agents as workers/processes rather than chat contacts.

## Implement

### Agents

Show:

- name,
- status,
- description,
- recent activity.

### Agent runs

Show:

- title/objective,
- state,
- latest activity,
- result summary,
- needs-input state.

### Actions

Examples:

- start low-risk run,
- stop if authorized,
- open result,
- message agent,
- discuss result in chat,
- create task,
- save result as note,
- archive.

## Security

Only low-risk/reversible actions are available to Kompakt.

Admin/security/destructive operations remain unavailable.

## Acceptance criteria

The user can:

- inspect agents,
- see what is running,
- inspect a finished run,
- respond to needs-input,
- turn results into deliberate follow-up actions.

---

# 11. Phase 9 — Incremental Sync and Offline Queue

## Goal

Move from request/response behavior to resilient multi-device synchronization.

## Implement

### Object revisions

Every synchronizable object:

```text
id
revision
updated_at
```

### Incremental changes

```text
GET /v1/changes?since=<cursor>
```

### Tombstones

Deleted objects propagate through the change feed.

### Expected revision

Updates include:

```text
expected_revision
```

Stale write:

```text
409 Conflict
```

### Offline queue

Support:

- task capture,
- note capture,
- safe capture requests.

Each queued write contains:

```text
request_id
payload
state
created_at
```

States:

```text
pending
sending
confirmed
failed
```

## Acceptance criteria

- app reconnects after offline period,
- queued capture replays once,
- duplicate replay does not duplicate server data,
- remote edits appear incrementally,
- deletions propagate,
- stale writes do not overwrite newer state silently.

---

# 12. Phase 10 — Foreground and Background Delivery

## Goal

Make Today/Inbox/agent events arrive without requiring manual refresh.

## Foreground

Implement SSE or WebSocket and feed events into a shared `SyncCoordinator`.

## Background

Implement:

- ntfy delivery,
- authenticated device/user topic,
- minimal push payload,
- deep-link/event metadata,
- cursor sync after wake.

## Fallback

Implement WorkManager periodic incremental sync.

Initial target:

```text
15–30 minute class interval
```

subject to Android constraints and real-device testing.

## Local

Use AlarmManager for already-known reminders.

## Acceptance criteria

Test:

- foreground agent completion,
- background needs-input notification,
- missed push recovered by cursor sync,
- WorkManager catches missed events,
- local reminder fires without network.

---

# 13. Phase 11 — Voice Input

## Goal

Make capture/chat/agent requests genuinely voice-first.

## Preferred experiment — Server-side STT

```text
record
  ↓
secure upload
  ↓
server STT
  ↓
transcript
```

Requirements:

- explicit recording,
- no hidden/background recording,
- delete temporary audio after successful processing by default.

## Alternative

FUTO or another local keyboard/STT remains an option.

## UX

```text
tap mic
speak
inspect transcript
correct small error
submit
```

## Acceptance criteria

Voice can fill:

- capture,
- chat,
- agent request.

---

# 14. Phase 12 — Notifications and Inbox Refinement

## Goal

Ensure interruptions are rare and actionable.

## Notification candidates

- task due,
- important reminder,
- agent completed,
- agent needs input,
- project blocked,
- server warning.

## Avoid

- every agent log event,
- routine sync status,
- passive progress noise.

## Acceptance criteria

Every notification answers:

> Why should the user care now?

and opens the relevant source object.

---

# 15. Phase 13 — Diagnostics and Developer Tools

## Goal

Make real-device debugging practical.

## Diagnostics screen

Show:

```text
App version
Protocol version
Device ID
Trust class
Server status
Last successful sync
Current cursor
Pending queue count
Background delivery status
ntfy status
Foreground stream status
Cache status
```

## Developer controls

Optional debug build tools:

- force sync,
- force offline,
- clear cache,
- simulate slow network,
- simulate server error,
- show redraw counters,
- inspect last sync result,
- inspect sanitized event log.

Never expose secrets in diagnostics.

---

# 16. Phase 14 — Kompakt Hardware Validation

## Goal

Tune the working app on the actual device.

## Validate

### UI

- typography,
- row height,
- touch targets,
- ghosting,
- contrast,
- scroll behavior,
- navigation latency.

### Input

- keyboard,
- glide correction,
- microphone,
- voice flow.

### Android behavior

- notifications,
- background process survival,
- ntfy/UnifiedPush behavior,
- WorkManager scheduling,
- AlarmManager,
- wake/sleep,
- deep links,
- Android Keystore capabilities.

### Network

- cellular,
- Wi-Fi,
- reconnect behavior,
- live-stream stability.

## Expected outcome

Mostly tuning, not architectural rewrite.

---

# 17. Phase 15 — Security Review

## Goal

Validate implementation against `security.md`.

## Review

- no embedded secrets,
- minimum Android permissions,
- private key storage,
- enrollment,
- revocation,
- capability checks,
- rate limits,
- request validation,
- offline replay safety,
- no arbitrary shell endpoints,
- no arbitrary WebView rendering,
- logs sanitized,
- CI secrets isolated,
- release signing key isolated.

## Negative tests

Attempt:

- unauthenticated requests,
- revoked device requests,
- low-trust → admin action,
- replayed request,
- stale revision mutation,
- oversized input,
- invalid action ID,
- unknown enum,
- manipulated client request.

## Acceptance criteria

The server rejects actions based on policy even if the Android client is deliberately modified.

---

# 18. Phase 16 — Open-Source Release Preparation

## Goal

Make the repository usable and safe for outside users.

## Add / finalize

- README,
- architecture overview,
- build instructions,
- setup/enrollment instructions,
- screenshots,
- license,
- SECURITY.md,
- CONTRIBUTING.md,
- changelog,
- release process,
- signed APK,
- checksums,
- signing certificate fingerprint.

## CI

Public PR CI:

- build,
- lint,
- tests,
- static analysis.

No production or signing credentials.

Trusted release path:

- controlled release signing,
- artifact publication.

---

# 19. v0.1 Definition of Done

## App shell

- [ ] Today
- [ ] Chat
- [ ] Agents
- [ ] Organize
- [ ] Inbox
- [ ] Settings/Diagnostics

## Organize

- [ ] Projects from vault/coordinator
- [ ] Areas from vault/coordinator
- [ ] Tasks
- [ ] Notes
- [ ] filtering by project/area

## Capture

- [ ] task capture
- [ ] note capture
- [ ] server proposal
- [ ] user confirmation
- [ ] offline capture queue

## Chat

- [ ] multiple chats
- [ ] persistent history
- [ ] send/receive messages
- [ ] explicit cross-domain actions

## Agents

- [ ] persistent agent list
- [ ] agent runs
- [ ] status
- [ ] result summary
- [ ] needs-input flow
- [ ] low-risk actions

## Sync

- [ ] protocol negotiation
- [ ] revisions
- [ ] cursor sync
- [ ] tombstones
- [ ] expected-revision conflicts
- [ ] idempotency
- [ ] reconnect

## Delivery

- [ ] foreground update path
- [ ] background update path
- [ ] periodic fallback
- [ ] local scheduled reminder

## Security

- [ ] device enrollment
- [ ] revocation
- [ ] Keystore-backed identity where supported
- [ ] server-side capability authorization
- [ ] low-trust Kompakt boundary
- [ ] no high-value secrets on phone

## Hardware

- [ ] APK tested on actual Kompakt
- [ ] E-Ink UX tuned
- [ ] background behavior validated
- [ ] notification behavior validated

---

# 20. Explicitly Not v0.1

Do not delay v0.1 for:

- multi-user support,
- organizations,
- teams,
- complex RBAC,
- full offline chat,
- CRDTs,
- complex conflict UI,
- arbitrary note-vault editing,
- full desktop project management,
- arbitrary terminal/shell control,
- server administration,
- production deployment approval,
- financial operations,
- broad file browsing,
- arbitrary WebViews,
- rich animation,
- plugin marketplace,
- generalized third-party integrations.

---

# 21. Recommended Implementation Order

```text
0. Repository/tooling
1. App shell/navigation
2. Domain/repository interfaces
3. Server read contract
4. Device enrollment/security
5. Today + Organize
6. Capture
7. Chat
8. Agents
9. Sync/offline
10. Delivery
11. Voice
12. Notification refinement
13. Diagnostics
14. Kompakt validation
15. Security review
16. Public release
```

## First major milestone

> A normal Android phone can securely enroll, open Today, browse vault-derived Projects/Areas/Tasks/Notes, and create a confirmed task/note capture through the real server.

That milestone validates the most important assumptions before Chat, agent control, background delivery, voice, or Kompakt-specific tuning add complexity.
