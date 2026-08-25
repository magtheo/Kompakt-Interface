# Technical Architecture and Stack

## Purpose

This document defines the technical direction for the Mudita Kompakt personal interface.

The APK is intended to be a **thin Android client** backed by a server that owns the authoritative data, AI agents, integrations, and high-value credentials.

The application should be designed for:

- Mudita Kompakt,
- E-Ink constraints,
- intermittent connectivity,
- low local resource use,
- minimal attack surface,
- and long-term server-driven extensibility.

---

## Platform

### Target device

Primary target:

- Mudita Kompakt
- MuditaOS K
- AOSP-based Android environment
- 4.3-inch E-Ink display
- 800 × 480 class display
- modest CPU/RAM compared with current smartphones

Development can begin on:

- a normal Android phone,
- and/or an Android emulator configured around the target resolution.

The actual Kompakt is required later for final validation of:

- ghosting,
- refresh behavior,
- touch feel,
- scrolling,
- background behavior,
- notifications,
- wake/sleep behavior,
- and device-specific API quirks.

---

## Reference Projects

### Mudita MMD

Repository:

`mudita/MMD`

Role:

- primary UI/design-system dependency,
- E-Ink-aware Jetpack Compose components,
- monochrome theme,
- reduced animation,
- ripple-free interaction,
- E-Ink typography and controls.

MMD should be used where it provides useful E-Ink behavior rather than recreating these primitives locally.

---

### KompaktCalendar

Repository:

`davidanderlohr/KompaktCalendar`

Role:

- reference implementation,
- known working Compose/MMD app structure for Kompakt,
- navigation patterns,
- notification patterns,
- DataStore usage,
- E-Ink scrolling patterns,
- Android integration patterns,
- Gradle/dependency reference.

KompaktCalendar should primarily be treated as a reference implementation.

Its data architecture is calendar-specific and should not become the foundation of this app.

It is GPLv3, so substantial direct code reuse should be intentional and license-aware.

---

## Android Stack

Recommended starting stack:

- **Language:** Kotlin
- **UI:** Jetpack Compose
- **Design system:** Mudita MMD
- **Architecture:** MVVM-style presentation architecture
- **State:** StateFlow / immutable UI state
- **Navigation:** Jetpack Compose Navigation
- **Preferences:** Android DataStore
- **Concurrency:** Kotlin Coroutines
- **Networking:** small HTTPS client
- **Serialization:** Kotlin serialization or equivalent
- **Local cache:** intentionally small
- **Notifications:** Android notification APIs
- **Scheduled local reminders:** AlarmManager only when needed

Avoid unnecessary dependencies.

The APK should remain easy to audit.

---

## E-Ink Technical Rules

The app should behave as if every unnecessary redraw has a cost.

### Required principles

- no animated page transitions,
- no ripple effects,
- no continuously animated loading indicators,
- no decorative motion,
- no high-frequency timers unless necessary,
- no auto-updating UI at sub-second cadence,
- no gradients,
- no dependence on color alone,
- minimal partial redraw pressure,
- prefer static high-contrast layouts,
- prefer discrete/jump scrolling.

Compose navigation transitions should default to `None`.

---

## High-Level Architecture

```text
                    SERVER

    ┌──────────────────────────────────┐
    │ Authoritative state              │
    │                                  │
    │ Chats                            │
    │ Agents / agent runs              │
    │ Tasks                            │
    │ Notes                            │
    │ Projects                         │
    │ Inbox                            │
    │ Calendar projection              │
    │ Search/indexes                   │
    │ Integrations                     │
    │ Credentials                      │
    └────────────────┬─────────────────┘
                     │
               Hardened HTTPS API
                     │
                     ▼
    ┌──────────────────────────────────┐
    │          Kompakt APK             │
    │                                  │
    │ UI / Compose / MMD               │
    │ ViewModels                       │
    │ Repository/API layer             │
    │ Local cache                      │
    │ Offline command queue            │
    │ Device identity                  │
    └──────────────────────────────────┘
```

---

## Client Architecture

Suggested package shape:

```text
app/
├── ui/
│   ├── today/
│   ├── chat/
│   ├── agents/
│   ├── tasks/
│   ├── notes/
│   ├── projects/
│   ├── inbox/
│   ├── capture/
│   ├── detail/
│   └── settings/
│
├── domain/
│   ├── ChatThread.kt
│   ├── Message.kt
│   ├── Agent.kt
│   ├── AgentRun.kt
│   ├── Task.kt
│   ├── Note.kt
│   ├── Project.kt
│   ├── InboxItem.kt
│   └── Action.kt
│
├── data/
│   ├── ApiClient.kt
│   ├── SyncRepository.kt
│   ├── CacheRepository.kt
│   └── PreferencesRepository.kt
│
├── auth/
│   └── DeviceIdentity.kt
│
└── notifications/
    ├── NotificationScheduler.kt
    └── NotificationReceiver.kt
```

This is illustrative, not mandatory.

---

## Core Domain Model

### ChatThread

Represents persistent conversational context.

Possible fields:

```text
id
title
createdAt
updatedAt
revision
projectId?
isTemporary?
scopeType?        # null = general | "topic" | "workspace"
scopeRef?
scopeLabel?       # server-resolved display label
pendingReply      # workspace tier: async turn in flight
lastMessagePreview
```

---

### Message

```text
id
chatId
role
content
createdAt
status
```

The client should render safe structured/plain content rather than arbitrary HTML.

---

### Agent

Represents a persistent worker/role.

```text
id
name
status
description
capabilities
lastActivity
```

---

### AgentRun

Represents one concrete execution.

```text
id
agentId
title
objective
status
startedAt
updatedAt
resultSummary?
requiresInput
projectId?
```

---

### Task

```text
id
title
status
dueAt?
projectId?
notes?
sourceType?
sourceId?
actions[]
```

---

### Note

```text
id                # vault-relative file ref
title
preview
category          # PARA-derived
role              # scratchpad | inbox | note
updatedAt
projectId?        # vault:project:<x> when nested under a project
areaId?           # vault:area:<x>
sourceType?       # from frontmatter
sourceId?
text              # detail only — full file text incl. frontmatter
checksum          # detail only — optimistic-lock token for edits
```

---

### Project

```text
id
name
status
currentGoal?
nextAction?
attentionCount
```

---

### InboxItem

Inbox is a projection.

```text
id
sourceType
sourceId
title
summary
timestamp
priority
actions[]
```

Selecting it opens its original source object.

---

## Generic Action Model

The server should be able to tell the phone which actions are available without requiring a bespoke Android implementation for every workflow.

Example:

```json
{
  "id": "approve",
  "label": "Approve",
  "style": "primary"
}
```

Possible actions:

- complete,
- postpone,
- archive,
- retry,
- approve,
- reject,
- open result,
- ask agent,
- send to agent,
- save as note,
- create task,
- discuss in chat.

The client must still validate that actions belong to known allowed action types.

Do not create an arbitrary remote-code UI protocol.

---

## Server API

The API should be deliberately narrow.

Implemented v0.1 catalog (protocol 3; field-level contracts in
protocol-and-sync.md §30):

```text
GET  /v1/capabilities          GET  /v1/status
GET  /v1/today                 GET  /v1/inbox
POST /v1/inbox/{alert_id}/read GET  /v1/alerts/stream   (SSE)
GET  /v1/changes               GET  /v1/projects
GET  /v1/areas                 GET  /v1/tasks
GET  /v1/notes                 GET  /v1/notes/{id}
PUT  /v1/notes/{id}            POST /v1/notes
GET  /v1/chats                 POST /v1/chats
GET  /v1/chats/{id}            GET  /v1/chats/{id}/messages
POST /v1/chats/{id}/messages   POST /v1/chats/{id}/scope
POST /v1/chats/{id}/truncate   GET  /v1/chat/topics
GET  /v1/workspaces            POST /v1/voice/transcribe
POST /v1/capture/interpret     POST /v1/capture/commit
```

Plus the agents surface (`/v1/agents`, `/v1/agents/commands`,
`/v1/agents/dispatch`, `/v1/agent-runs` + `/{id}` `/events` `/result`
`/send` `/steer` `/cancel` `/command`), enabled when the agent backend
is configured.

Exact endpoint design is deliberately frozen — see the catalog above.

The important decision is that the API should expose **capabilities**, not shell access.

---

## Security Model

### Principle

Assume the phone can eventually be lost or compromised.

Design so that compromise of the phone does not imply compromise of the server.

---

### Connection

Preferred direction:

- HTTPS,
- modern TLS,
- one hardened gateway,
- minimal externally exposed API surface.

A direct API gateway may be preferable to placing the phone on a broad private network.

Tailscale remains an option, but if used it should be constrained with ACLs so the Kompakt cannot reach arbitrary internal services.

---

### Device identity

Preferred direction:

- per-device asymmetric key,
- generated during enrollment,
- private key held through Android Keystore where practical,
- device identity is revocable server-side,
- requests may be signed or authenticated using the device key.

Avoid a single high-value reusable bearer token if possible.

---

### Authorization

The phone should have narrow capabilities.

Examples allowed:

- read Today,
- read Inbox,
- submit chat message,
- submit agent request,
- inspect agent status,
- create task,
- create note,
- mark task complete,
- approve low-risk action.

Examples not allowed:

- unrestricted shell,
- root commands,
- raw database access,
- reading server secrets,
- credential rotation,
- arbitrary deployment,
- destructive production operations.

High-risk actions should require a more trusted device or stronger authorization.

---

## Data on Device

Keep local durable data small.

Acceptable cached data:

- today's tasks,
- today's events,
- recent chat snippets,
- recent inbox items,
- recent notes,
- recent agent summaries,
- queued offline commands,
- app preferences.

Avoid storing:

- complete repositories,
- full project archives,
- passwords,
- recovery codes,
- root SSH credentials,
- general-purpose server secrets.

---

## Offline Behavior

The app should not attempt to replicate the full server offline.

Offline support should be narrow.

Allow:

- reading a small cache,
- creating a note,
- creating a task,
- marking a cached task complete,
- writing a chat or agent request into an outgoing queue.

When connectivity returns:

```text
offline action
    ↓
local queue
    ↓
network restored
    ↓
authenticated sync
    ↓
server accepts/rejects
    ↓
UI reconciles
```

Avoid complex conflict resolution in the first version.

---

## Voice

Voice is an important input path.

Two possible architectures:

### Local keyboard/STT

Use an installed keyboard such as FUTO for speech and glide correction.

Advantages:

- integration with standard text fields,
- can work independently of the server.

Disadvantages:

- local CPU limitations,
- another sideloaded app,
- potentially slower speech inference.

### Server-side STT

The app records audio and sends it securely to the server.

Server performs speech recognition.

Advantages:

- better model quality,
- less local CPU burden,
- simpler thin-client experience.

Disadvantages:

- requires connectivity,
- server must safely process/store or immediately discard audio.

Server-side STT is attractive for this project but not yet mandatory.

---

## Notifications

Notifications should be event-driven, not based on constant polling.

Potential notification sources:

- task due,
- reminder,
- agent completed,
- agent needs input,
- project blocked,
- server alert.

KompaktCalendar provides useful reference patterns for:

- AlarmManager,
- BroadcastReceiver,
- reboot rescheduling,
- event alerts.

Only use full-screen alerts for genuinely important cases.

---

## Development Strategy

### Phase 1 — Normal Android phone / emulator

Build and validate:

- domain model,
- navigation,
- API,
- authentication,
- chat,
- agents,
- tasks,
- notes,
- inbox,
- caching,
- offline queue,
- notifications.

Use a constrained emulator profile close to the Kompakt screen.

---

### Phase 2 — E-Ink simulation discipline

Even on OLED:

- animations off,
- monochrome theme,
- low refresh assumptions,
- large touch targets,
- minimal scrolling,
- no rapidly changing indicators.

Add a developer option for an `EInkMode` if useful.

---

### Phase 3 — Real Kompakt validation

When hardware arrives, validate:

- refresh/ghosting,
- perceived latency,
- typography,
- button sizing,
- scrolling,
- keyboard behavior,
- wake/sleep,
- notifications,
- background sync,
- network behavior,
- app installation/update workflow.

Expect tuning rather than architectural redesign.

---

## Initial Build Scope

The first end-to-end build should support:

1. Device enrollment/authentication.
2. Today.
3. Chat list + chat thread.
4. Agents list + agent run detail.
5. Tasks.
6. Notes.
7. Inbox.
8. Generic item detail/actions.
9. Minimal offline cache.
10. Basic notifications.

Projects can initially be read-only and minimal.

---

## Open Technical Questions

Still to decide:

- final API transport and authentication details,
- whether Tailscale is used at all,
- exact local cache technology,
- whether STT is local or server-side,
- whether calendar data is server-projected or read through Android Calendar Provider,
- whether the APK should integrate physical buttons,
- exact notification policy,
- whether chats can stream incrementally or update in larger chunks for E-Ink,
- update/distribution mechanism for the APK.

These should be resolved experimentally and documented as decisions land.

