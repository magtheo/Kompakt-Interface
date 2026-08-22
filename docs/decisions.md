# Decisions Register

## Purpose

This document records product and architecture decisions that have landed for the Mudita Kompakt personal interface.

Its purpose is to prevent design drift during implementation, especially when coding agents work on the project over time.

Each decision has an ID and status.

Statuses:

- **Accepted** — current source-of-truth decision.
- **Proposed** — preferred direction but not yet fully landed.
- **Superseded** — replaced by a later decision.
- **Deferred** — intentionally not decided for v0.1.

---

## D001 — Server is authoritative

**Status:** Accepted

The server is the authoritative source of truth for tasks, notes, chats, agents, agent runs, projects, areas, inbox items, relationships, and durable user state.

The phone may keep a small cache and an offline mutation queue, but must not become a parallel source of truth.

### Consequences

- replacing the phone does not lose important data,
- sync remains server-centered,
- the client must tolerate stale cache,
- writes must flow through server APIs,
- object revisions and incremental sync are required.

---

## D002 — The APK is open source

**Status:** Accepted

The Android client will be open source.

Security must not depend on hidden source code, hidden endpoints, hidden request formats, or obscured client-side rules.

### Consequences

- no secrets may be embedded in the APK or repository,
- modified APKs must not gain extra authority,
- API authorization must be server-enforced,
- release signing and supply-chain security matter.

---

## D003 — Chat, Agents, Tasks, and Notes remain distinct

**Status:** Accepted

The following remain separate concepts:

- Chat
- Agents
- Tasks
- Notes

They may reference one another, but must not silently become one another.

Allowed explicit transitions include:

- Chat → Save as note
- Chat → Create task
- Chat → Send to agent
- Agent result → Discuss in chat
- Agent result → Create task
- Task → Ask agent
- Note → Discuss in chat

---

## D004 — Organize follows the vault

**Status:** Accepted

The app must not invent an independent organizational hierarchy.

The **Organize** surface renders the server/vault entity model through the coordinator.

```text
Organize
├── Projects
├── Areas
├── Tasks
└── Notes
```

The server-side vault already defines:

- **Projects** — project folders such as Evershift or KodeVerket,
- **Areas** — long-lived areas such as Health, Career, Personal, Economy,
- **Tasks** — aggregated from adapters and linked through relationships,
- **Notes** — markdown files in the vault.

Projects and Areas are the primary browse axes. Tasks and Notes may be shown as flat lists filterable by project or area.

Adding or changing structure in the vault should flow through the coordinator into the app automatically.

---

## D005 — Phone note writes use capture routing

**Status:** Accepted for v0.1

Free-form editing of the full note vault is not a v0.1 phone feature.

Phone note creation goes through the capture flow:

```text
voice/text
    ↓
server proposes type/route
    ↓
user confirms
    ↓
server routes to vault destination
```

This preserves the existing **one capture = one item** model.

Quick note capture and note browsing are supported. Full Obsidian-style editing remains a desktop workflow.

---

## D006 — v0.1 is single-user, multi-device

**Status:** Accepted

v0.1 supports one person, one server environment, and multiple enrolled devices.

```text
user
├── kompakt-01
├── pixel-01
└── laptop-01
```

Explicitly out of scope:

- organizations,
- teams,
- invitations,
- multi-tenant isolation,
- user-to-user permissions,
- general RBAC for multiple users.

Authorization is primarily about device identity, device trust class, and operation capability.

---

## D007 — Devices have trust tiers

**Status:** Accepted

Different devices may have different capability levels.

### Low trust

Example: Mudita Kompakt.

Typical capabilities:

- read personal summaries,
- capture,
- chat,
- notes/tasks,
- inspect agents,
- low-risk agent actions.

### Medium trust

Example: GrapheneOS / capability phone.

May support more sensitive workflows.

### Admin trust

Example: trusted laptop/workstation.

May authorize administrative operations, credential changes, privileged infrastructure work, and high-impact actions.

---

## D008 — Delivery is multi-path and transport-swappable

**Status:** Accepted

Server-to-phone delivery uses three conceptual mechanisms.

### Foreground

While the app is open:

- live coordinator connection,
- SSE or WebSocket implementation,
- near-immediate Today/Inbox/agent updates.

### Background

When the app is not foregrounded:

- ntfy-based push,
- authenticated per-device topics,
- deep-link/event payload,
- periodic WorkManager sync fallback,
- missed events recovered through incremental sync.

### Local

For events already known on-device:

- AlarmManager,
- local Android notification,
- no network dependency.

The app should expose a single internal update interface while transport remains replaceable.

Real-device testing will determine final polling intervals and background behavior.

---

## D009 — Delivery semantics are independent of transport

**Status:** Accepted

The application defines the semantic contract first:

> An event becomes available, the client eventually receives or recovers it, and missed events can be replayed through cursor-based sync.

Foreground streaming, ntfy push, periodic sync, and later transports all feed the same update pipeline.

---

## D010 — API uses explicit protocol versioning and capability negotiation

**Status:** Accepted

The API uses versioned routes:

```text
/v1/...
```

A capabilities endpoint returns at least:

```text
server_protocol
minimum_client_protocol
feature flags
```

Example feature flags:

```text
agent_runs
voice_capture
projects
areas
offline_capture
```

The client:

- hard-stops if below `minimum_client_protocol`,
- hides or gates unsupported features,
- ignores unknown optional fields,
- handles unknown enum/action values safely,
- must not crash on forward-compatible additions.

The server does not rename or remove existing fields incompatibly within a version. Breaking behavior requires a new version and migration window where practical.

### Endpoint naming note

Earlier discussion referenced both:

```text
/v1/system/capabilities
```

and:

```text
/v1/capabilities
```

The exact canonical path should be chosen once during implementation and then treated as stable.

---

## D011 — Incremental sync uses revisions and cursors

**Status:** Accepted

Every synchronizable object should expose at least:

```text
id
revision
updated_at
```

Deletions should be represented through tombstones or equivalent change records.

The client pulls incremental changes using a cursor-based endpoint such as:

```text
GET /v1/changes?since=<cursor>
```

Writes against existing objects should include an expected revision.

If stale, the server returns `409 Conflict` with current state available to the client.

No CRDT or complex merge engine is required for v0.1.

---

## D012 — v0.1 offline writes are capture-oriented

**Status:** Accepted

Offline queueing in v0.1 is limited to safe capture-style operations.

Allowed examples:

- create task,
- create note,
- create capture,
- low-risk agent request if explicitly supported.

Not required offline in v0.1:

- full chat interaction,
- live agent runs,
- arbitrary object editing,
- complex conflict resolution.

---

## D013 — Mutations use idempotency keys

**Status:** Accepted

Replayable mutations use a stable request identifier / idempotency key.

The existing coordinator pattern using `request_id` for scheduled mutations should be reused conceptually.

A queued capture can therefore be retried after reconnect without creating duplicate objects.

---

## D014 — Conflict policy is intentionally simple

**Status:** Accepted for v0.1

On revision conflict:

1. server returns `409`,
2. client refetches current state,
3. client reconciles with a simple rule,
4. no dedicated merge UI is required.

Where a final resolution policy is needed, use a simple object-specific or server-defined last-write-wins approach.

Single-user use makes simultaneous edits uncommon.

---

## D015 — KompaktCalendar is a reference implementation

**Status:** Accepted

Use `davidanderlohr/KompaktCalendar` primarily as reference material for:

- Mudita-compatible Gradle/Compose setup,
- MMD usage,
- E-Ink navigation,
- scrolling,
- notifications,
- Android integration.

Do not copy substantial GPLv3 implementation code unless the project intentionally adopts compatible licensing obligations.

---

## D016 — Mudita MMD is the E-Ink UI foundation

**Status:** Accepted

Use Mudita MMD as the primary E-Ink-aware design/component foundation.

Do not recreate basic E-Ink interaction primitives unnecessarily.

---

## D017 — E-Ink behavior is a product constraint, not a later optimization

**Status:** Accepted

From the first build:

- no animated navigation,
- no ripple effects,
- no decorative motion,
- low redraw frequency,
- high contrast,
- static status indicators,
- discrete/jump scrolling where appropriate.

Development on OLED must still respect these constraints.

---

## D018 — Today and Inbox are projections, not stores

**Status:** Accepted

Today and Inbox aggregate objects from other domains and do not own canonical copies.

Selecting an item opens the underlying source object.

---

## D019 — Security is server-enforced

**Status:** Accepted

The server must never trust:

- client UI state,
- hidden buttons,
- app package identity alone,
- client-side capability flags,
- the fact that a request came from the official APK.

Each meaningful operation is authenticated and authorized server-side.

---

## D020 — No powerful secrets live on the Kompakt

**Status:** Accepted

Do not store on the Kompakt:

- password vaults,
- recovery codes,
- root SSH keys,
- server master credentials,
- broad API tokens,
- database credentials,
- administrative secrets.

The device receives only revocable, narrow credentials appropriate to its trust tier.

---

## D021 — Canonical /v1/ wire contract (T-004)

**Status:** Accepted (Aug 2026, implementation)

Resolves deferred items from this document and protocol §8:

- **Canonical capabilities path: `GET /v1/capabilities`** — the `/v1/system/capabilities` spelling in earlier drafts is retired.
- **Wire field naming is snake_case** (`due_at`, `project_id`, `updated_at`), matching the protocol document's examples and the Python coordinator's natural output. The Kotlin domain keeps camelCase properties with `@SerialName` annotations bridging the two.
- **HTTP transport: OkHttp 4.12** (already in the dependency set) with MockWebServer pinning the wire contract in unit tests. No Ktor/Retrofit — minimal dependency footprint for a thin client.
- **List envelopes are keyed objects** (`{"tasks": [...]}`), tolerating unknown envelope fields (future pagination) per protocol §9.
- **Feature flags tell the truth**: `chat`, `agents`, `agent_runs`, `notes`, `offline_capture` are `false` until their coordinator backends exist; endpoints serve shape-valid empty lists so clients exercise the decoding path.
- **Auth**: `/v1/*` shares the coordinator's existing bearer-token middleware until device enrollment (Phase 4) replaces it with per-device tokens.

---

## D022 — Device enrollment and per-device tokens (T-005)

**Status:** Accepted (Aug 2026, implementation)

Resolves the Phase 4 security boundary. Wire and trust semantics:

- **Dual principals.** Admin = the shared config token (full access, incl.
  `/api/*`). Device = a per-device token minted at activation; valid only for
  `/v1/*` reads scoped by capabilities (`task.read`, `project.read`,
  `inbox.read`, `today.read`, `note.read`, …). Writes remain admin-only until
  Phase 5+ action endpoints exist.
- **Enrollment flow.** `POST /v1/devices/enroll {name, public_key}` → pending;
  `GET /v1/devices/{id}/challenge` → nonce; `POST /v1/devices/{id}/activate
  {nonce, signature}` verifies a raw Ed25519 signature over the nonce and
  returns the device token exactly once. Server stores only a SHA-256 hash of
  the token.
- **Anti-enumeration.** Unknown device IDs on challenge receive a decoy nonce
  (valid base64, unusable) — the endpoint does not reveal whether an ID exists.
- **Approve defaults.** `trust_class=low`, capabilities unset → all Class-1
  reads, per `security.md`.
- **Revocation semantics.** Pending activate returns `202 {"status":"pending"}`
  (poll, don't spin). Revoked devices keep their token hash so their requests
  fail with an explicit `device_revoked` 401 instead of a bare 401 — the client
  can guide re-enrollment instead of showing a generic auth error.

Client side: device keypair is Ed25519 via BouncyCastle **lightweight API**
(no JCE provider registration — Android ships a stripped BC namespace). The
seed is wrapped at rest with AndroidKeyStore AES/GCM behind a `SecretVault`
abstraction (JVM tests use an in-memory vault). `HttpApi` takes a token
provider rather than a fixed token, and `AppContainer` flips repositories
Fake→Remote when enrollment activates — no app restart.

---

## D023 — Three-artifact distribution: protocol spec, client, reference server

**Status:** Accepted (Aug 2026, planning — executes at Phase 16)

When the system goes public it ships as **three separate artifacts, not one
repository**:

- **kompakt-protocol** — standalone, independently versioned `/v1/` protocol
  specification. The product boundary; what any third-party backend implements
  (the "email model": any client, any server, shared spec).
- **Kompakt-Interface** — the Android client (this repo).
- **vault-coordinator** — reference server implementation, published with the
  vault adapter isolated so it is swappable.

Rationale: D021's contract-first architecture makes the boundary explicit; a
monorepo would signal a private/internal API and re-couple the pieces the
contract exists to separate. Audiences, release cadences, and versioning differ
per artifact — `/v1/` versioning plus dual-sided contract tests already handle
cross-cutting change, so atomic co-commits add nothing.

Two rules follow:

- **The wire is resource-shaped, never vault-shaped.** No storage paths, PARA
  names, or vault frontmatter conventions may appear in `/v1/` traffic — this
  is what makes third-party backends interchangeable. A leakage audit is a
  Phase 16 gate item.
- **Coordinator vault conventions must become configuration** before
  third-party "bring your own vault" self-hosting is a supported path
  (currently partly baked in).

Detail: `distribution.md`. (D022 remains reserved for the T-005 enrollment
record.)

---

## Deferred Decisions

The following remain intentionally open:

- exact canonical capabilities endpoint path ~~(D021: `GET /v1/capabilities`)~~,
- SSE vs WebSocket for foreground transport,
- final WorkManager fallback interval,
- exact ntfy topic/payload format,
- whether server-side STT is enabled in v0.1,
- exact APK update mechanism,
- physical-button integration,
- exact calendar data integration,
- exact cache retention durations,
- whether Class 4 actions may ever be approved from Kompakt,
- final open-source license for the app repository.

These should be resolved through implementation or real-device testing.
