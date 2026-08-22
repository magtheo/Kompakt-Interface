# Protocol and Sync

## Purpose

This document defines the client/server interaction contract for the Mudita Kompakt application.

It covers:

- delivery,
- foreground updates,
- background updates,
- local reminders,
- protocol compatibility,
- capability negotiation,
- incremental synchronization,
- offline capture,
- mutation idempotency,
- object revisions,
- deletion/tombstones,
- conflict behavior,
- and recovery after missed events.

The central model is:

> The server is authoritative. The client maintains a small local projection and an outbound queue for a narrow set of safe offline mutations.

---

# 1. System Model

```text
                  SERVER / COORDINATOR

           authoritative entity model
       projects / areas / tasks / notes
       chats / agents / agent runs / inbox

                     │
          ┌──────────┼───────────┐
          │          │           │
          ▼          ▼           ▼
       HTTPS      live stream    ntfy
        API       foreground   background
          │          │           │
          └──────────┼───────────┘
                     ▼
                Kompakt APK
                     │
            local cache + queue
                     │
                     ▼
              AlarmManager
             local reminders
```

The client does not maintain an independent organizational hierarchy.

The server/coordinator supplies the entity model derived from the vault and adapters.

---

# 2. Delivery Semantics

The app should depend on **delivery semantics**, not on one transport.

The semantic contract is:

1. A server-side state change occurs.
2. If the app is online, the client should learn about it promptly.
3. If an event is missed, the client must recover it through incremental sync.
4. The transport may change without changing the domain/UI layers.
5. The client must not assume push delivery is perfect.

---

# 3. Foreground Delivery

When the app is open, use a live connection to the coordinator.

Candidate transports:

- Server-Sent Events (SSE),
- WebSocket.

Exact transport remains an implementation choice.

The live channel may notify the client of events such as:

```text
task.updated
note.created
agent.started
agent.completed
agent.needs_input
project.updated
inbox.created
calendar.updated
```

Recommended event envelope:

```json
{
  "event_id": "evt_123",
  "type": "agent.completed",
  "entity_type": "agent_run",
  "entity_id": "run_456",
  "revision": 12,
  "cursor": "chg_009912",
  "timestamp": "2026-08-22T10:30:00Z"
}
```

The event does not need to contain the complete canonical object.

The client may fetch the updated entity or trigger incremental sync.

---

# 4. Background Delivery

The phone is de-Googled.

Do not assume Firebase Cloud Messaging.

The intended background design is:

```text
ntfy push
    +
periodic WorkManager sync
    +
cursor replay
```

## 4.1 ntfy

The server already runs ntfy.

Use authenticated per-device or per-user topics.

A push event should contain only enough information to:

- identify the event class,
- identify the affected object,
- optionally deep-link to a route,
- prompt the client to sync.

Avoid placing sensitive full object contents in the push payload unless there is a clear need.

Example:

```json
{
  "type": "agent.needs_input",
  "entity_id": "run_456",
  "route": "/agents/runs/run_456",
  "cursor": "chg_009912"
}
```

The ntfy transport is not the source of truth.

## 4.2 Periodic WorkManager fallback

Because MuditaOS K may suspend or kill background connections, periodic synchronization provides recovery.

Initial target interval:

```text
approximately 15–30 minutes
```

subject to Android scheduling constraints and real-device testing.

The fallback sync should pull only incremental changes.

Do not perform expensive full-state reloads on every wake.

## 4.3 Missed push recovery

If ntfy delivery is missed:

```text
client last cursor = C100
server current cursor = C135
```

The client calls:

```text
GET /v1/changes?since=C100
```

and receives the relevant changes through `C135`.

This is the correctness mechanism.

Push is only the latency optimization.

---

# 5. Local Delivery

Use local scheduling for events already known on-device.

Examples:

- task reminder,
- calendar reminder,
- scheduled local alert.

Mechanism:

```text
AlarmManager
    ↓
BroadcastReceiver
    ↓
local notification
```

This path works without network connectivity.

Local scheduled items should reconcile with server state when the app next syncs.

---

# 6. Unified Client Update Interface

Transport-specific code should feed one internal update layer.

```text
ForegroundStream
NtfyReceiver
PeriodicSync
ManualRefresh
        │
        ▼
   SyncCoordinator
        │
        ▼
 repositories/cache
        │
        ▼
      UI state
```

The UI must not care whether an update arrived through SSE, WebSocket, ntfy, WorkManager, or manual refresh.

---

# 7. API Versioning

All stable APIs should use explicit URL versioning.

```text
/v1/...
```

A breaking protocol change requires a new version.

Within one version:

- do not rename existing fields incompatibly,
- do not change field semantics incompatibly,
- do not remove supported operations without migration,
- additive optional fields are allowed.

---

# 8. Capability Negotiation

The server must expose a capabilities endpoint.

Earlier design discussion referenced both:

```text
/v1/system/capabilities
```

and:

```text
/v1/capabilities
```

The exact canonical path must be chosen once during implementation.

Example response:

```json
{
  "server_protocol": 3,
  "minimum_client_protocol": 2,
  "features": {
    "today": true,
    "inbox": true,
    "projects": true,
    "areas": true,
    "agent_runs": true,
    "voice_capture": false,
    "offline_capture": true
  }
}
```

---

# 9. Client Compatibility Rules

The client must hard-stop when:

```text
client_protocol < minimum_client_protocol
```

and show an explicit compatibility error.

If a feature flag is false, do not show or enable that feature.

The client must ignore unknown optional fields and safely handle unknown enum/action values.

Unknown values must never trigger arbitrary behavior.

---

# 10. Object Sync Model

Every synchronizable object should expose:

```text
id
revision
updated_at
```

Example:

```json
{
  "id": "task_123",
  "revision": 7,
  "updated_at": "2026-08-22T10:00:00Z",
  "title": "Review PR",
  "status": "open"
}
```

`revision` increases whenever the canonical representation changes.

---

# 11. Change Cursor

The server maintains an ordered change stream.

The client stores:

```text
last_sync_cursor
```

Example endpoint:

```text
GET /v1/changes?since=chg_009912
```

Example response:

```json
{
  "next_cursor": "chg_009930",
  "changes": [
    {
      "type": "updated",
      "entity_type": "task",
      "entity_id": "task_123",
      "revision": 8
    },
    {
      "type": "deleted",
      "entity_type": "note",
      "entity_id": "note_456",
      "revision": 3
    }
  ]
}
```

The client may fetch canonical objects or consume included representations if the final API supports that.

---

# 12. Tombstones

Deleted objects must remain observable long enough for clients to learn that deletion occurred.

A tombstone/change record should include at least:

```text
entity_type
entity_id
revision
deleted_at
```

The client should remove or mark the cached object when the tombstone is processed.

The exact tombstone retention window remains an implementation detail.

---

# 13. Mutations and Expected Revision

Updates to an existing object should include the version the client believes it is editing.

```json
{
  "expected_revision": 7,
  "status": "completed"
}
```

If the current server revision is still `7`, accept and advance the revision.

If the server revision has moved on, return:

```text
409 Conflict
```

The server should return or make available the current canonical object.

---

# 14. Conflict Handling

v0.1 intentionally avoids complex merge behavior.

On `409`:

1. refetch current object,
2. reconcile locally,
3. apply a simple object-specific policy,
4. retry only when safe.

No general-purpose merge UI is required.

Because v0.1 is single-user, simultaneous edits are expected to be uncommon.

---

# 15. Idempotency

Replayable mutations must use an idempotency/request key.

```json
{
  "request_id": "device-kompakt-01-01J...",
  "type": "capture",
  "payload": {
    "text": "Call dentist tomorrow"
  }
}
```

If the same `request_id` arrives twice, the server must not create two separate objects.

The server should return the original result or equivalent stable outcome.

---

# 16. Offline Scope

The v0.1 offline queue is deliberately narrow.

## Supported

- create task,
- create note,
- create capture,
- possibly queue a low-risk agent request if explicitly supported.

## Not required

- full chat conversations,
- live agent execution,
- broad editing of existing objects,
- complex project mutations.

Reason:

> Offline capture matters. Offline server-dependent interaction does not need to be simulated.

---

# 17. Offline Mutation Lifecycle

Conceptual local states:

```text
pending
sending
confirmed
failed
```

Example:

```text
user captures note offline
        ↓
pending
        ↓
connectivity restored
        ↓
sending
        ↓
server accepts request_id
        ↓
confirmed
```

Transient failures may retry.

Permanent rejection should remain user-visible with a reason.

---

# 18. Local Cache

The local database/cache is a projection.

It may contain:

- Today data,
- recent tasks,
- recent notes,
- recent inbox entries,
- recent chat snippets,
- recent agent summaries,
- current projects/areas needed for navigation.

The client should not assume cache contents are complete.

Each cached entity should retain:

```text
id
revision
updated_at
sync state
```

---

# 19. Organize and Vault Projection

The client does not create its own project/area taxonomy.

The server exposes the vault/coordinator structure.

Conceptual endpoints may include:

```text
GET /v1/projects
GET /v1/areas
GET /v1/tasks?project_id=...
GET /v1/tasks?area_id=...
GET /v1/notes?project_id=...
GET /v1/notes?area_id=...
```

The exact route structure is still open.

Adding or changing vault structure should flow through the coordinator into the app projection.

---

# 20. Capture Routing

Phone-side note/task creation uses the capture pipeline.

```text
user speech/text
      ↓
POST /v1/capture/interpret
      ↓
server proposal
      ↓
user confirm/change
      ↓
POST /v1/capture/commit
      ↓
coordinator routes to vault/entity
```

Example proposal:

```json
{
  "proposed_type": "task",
  "title": "Call dentist",
  "due_at": "2026-08-23",
  "project_id": null,
  "area_id": "personal"
}
```

The exact endpoint split may differ.

The important semantic rule is:

> The server may propose structure, but the user confirms before structured creation.

---

# 21. Chat Connectivity

Chat is server-dependent in v0.1.

If offline:

- previously cached content may be readable,
- new messages do not need to be fully supported as an offline conversational workflow.

The UI may either queue a message explicitly or require connectivity.

The protocol does not require offline multi-message chat.

---

# 22. Agent Connectivity

Agent interaction requires connectivity.

The client may cache:

- agent list,
- latest status,
- recent result summaries.

Starting or controlling an agent run requires the server.

Background completion is surfaced through the delivery model:

```text
foreground stream
or
ntfy
or
periodic cursor sync
```

---

# 23. Reconnection

When returning online:

1. authenticate device/session,
2. fetch server capabilities if needed,
3. send pending idempotent mutations,
4. process results,
5. run incremental sync from last cursor,
6. update cache,
7. refresh visible screen.

Pending outbound mutations should be submitted before or in coordination with change replay so the client can reconcile its own writes cleanly.

Exact ordering may be tuned during implementation.

---

# 24. Manual Refresh

The user should be able to trigger a manual sync.

```text
Sync now
```

Manual refresh should call the same sync coordinator as automated mechanisms.

---

# 25. Error Handling

## Server unavailable

```text
Server unavailable.

Cached data is shown.
New captures will be queued.
```

## Authentication revoked

```text
This device is no longer authorized.

Re-enrollment is required.
```

## Protocol incompatible

```text
This app version is not compatible with the server.

Update required.
```

## Conflict

Prefer silent/simple recovery where safe.

If user intervention is necessary:

```text
This item changed on another device.

Current server version has been loaded.
```

---

# 26. Security Requirements

All transports must preserve the security architecture.

### HTTPS API

- encrypted transport,
- authenticated device,
- server-side authorization.

### Foreground stream

- authenticated,
- same capability boundary as REST/API,
- no broader permissions.

### ntfy

- authenticated topics,
- minimal payload,
- no high-value secrets,
- push is advisory, not authoritative.

### WorkManager

- uses normal authenticated sync API.

### AlarmManager

- schedules only local information already obtained by the app.

---

# 27. Testing Requirements

At minimum test:

### Delivery

- foreground event received,
- missed ntfy push recovered by cursor sync,
- WorkManager sync catches changes,
- local AlarmManager reminder fires offline.

### Compatibility

- old supported client with newer server,
- unsupported client below minimum protocol,
- unknown JSON field,
- unknown enum/action.

### Sync

- incremental update,
- tombstone processing,
- stale revision conflict,
- cursor persistence,
- reconnect after long offline period.

### Offline

- capture queued,
- duplicate replay prevented by request ID,
- successful confirmation,
- permanent rejection surfaced.

### Security

- unauthenticated change request rejected,
- valid low-trust device blocked from high-risk action,
- modified/unknown client cannot bypass server authorization.

---

# 28. Real-Device Questions

Mudita Kompakt testing must determine:

- whether ntfy/UnifiedPush remains reliable in background,
- WorkManager scheduling behavior,
- whether aggressive battery management kills live connections,
- acceptable fallback sync interval,
- notification presentation,
- deep-link reliability,
- whether foreground SSE or WebSocket behaves better,
- how much event batching improves E-Ink UX.

These are transport tuning questions, not reasons to change the semantic model.

---

# 29. v0.1 Protocol Definition of Done

The protocol/sync layer is ready for v0.1 when:

- [ ] `/v1/...` compatibility rules are implemented.
- [ ] capability negotiation works.
- [ ] client rejects unsupported protocol versions safely.
- [ ] objects expose revision metadata.
- [ ] incremental cursor sync works.
- [ ] tombstones/deletions propagate.
- [ ] expected-revision conflict detection works.
- [ ] idempotent capture requests work.
- [ ] offline capture queue works.
- [ ] foreground update transport works.
- [ ] background delivery path works in at least one form.
- [ ] periodic incremental fallback works.
- [ ] local scheduled reminders work.
- [ ] missed events are recoverable without relying on push history.
- [ ] low-trust device authorization is enforced server-side.
- [ ] actual Kompakt behavior is validated when hardware is available.
