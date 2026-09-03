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

## D024 — Chat replies are server-generated via a swappable OpenAI-compatible backend

**Status:** Accepted (Aug 2026 — implemented in vault-coordinator V-050 / T-009)

The assistant messages in a chat thread are generated **server-side**: the
coordinator owns `chat_threads`/`chat_messages` as source of truth and proxies
completion requests to an OpenAI-compatible endpoint. The default and reference
backend is the **local Hermes API server** (`127.0.0.1:8642`); any
OpenAI-compatible server substitutes via a single `chat:` config block
(base URL, model, timeout) — no code changes.

Rules that follow:

- **The phone never talks to the LLM directly.** One auth surface (the
  coordinator), one wire contract; the model is configuration, not
  architecture.
- **Chat ≠ agent** (D003): this backend answers conversational messages only.
  Autonomous execution is D025's domain.
- Model choice is an e-ink UX concern the coordinator owns: reasoning-merged
  outputs (e.g. deepseek-v4 via the Hermes API flattening) leak chain-of-thought
  into message content; shipping model must produce clean single-message
  replies (claude-sonnet-4 verified).
- Send latency is LLM-bound; the wire contract already carries a per-call
  client timeout override for chat sends (120 s). LLM failure degrades to an
  honest assistant note — a send never fails because generation did.

---

## D025 — Agent orchestration is a coordinator-internal port; Warren is the reference adapter

**Status:** Accepted (Aug 2026, planning — executes at Phase 8)

The Agents surface is served through an **`AgentBackend` port inside the
coordinator** — a small, domain-side interface (~7 operations: list agents,
dispatch, get run, read events, steer, cancel, result) that adapters translate
to concrete backends. No backend's API shapes leak into `/v1/`.

- **Reference adapter: Warren** (jayminwest/warren, MIT, self-hosted) driving
  **Pi (Oh My Pi)** harnesses — worktree/Docker-isolated runs, NDJSON event
  streams with bounded polling reads, mid-run steering, cancel, plan-runs with
  pause/resume ("needs input"), cost analytics, guaranteed pushed-branch
  output. Deployed localhost-only behind the coordinator; its single bearer
  token never leaves the host.
- **Second adapter: OpenCode** (session-flavored: monitor + message, no
  workload semantics). The port is not considered final until this second
  implementation exists — **rule of two**: no generalizing before two concrete
  adapters, no third backend before the port is locked.
- **Capability-gated surface**: backends declare power (steer, cancel,
  scheduled triggers…); flags flow through `/v1/capabilities`; the app hides
  actions the backend cannot honor — fail-closed, per D019/D021.
- **`agents.backend: warren | opencode | none`** config selects; `none` gates
  the tab off. Minimal third-party install = coordinator + chosen backend
  adapter + APK.
- **Coordinator DB persists a run projection** — the phone-visible Agent/AgentRun
  history survives a backend swap; adapters are translators, not owners.

Distribution implication (D023): other operators bring their own stack —
OpenCode-only, Warren, or a custom control plane behind a written adapter —
and the client stays identical. This is the "email model" applied to
orchestration: general at the domain boundary, specific inside adapters.

Refusing: a universal agent protocol with N optional fields; the port stays
minimal and domain-derived.

Related: dev-server's AI Control Plane Executor is effectively superseded by
Warren for containerized run workloads (documented in the vault research note,
2026-08-23); SLE v2 SDK work is a different layer and unaffected.

---

## D026 — Conversation editing is destructive and branch-free, built on one server primitive

**Status:** Accepted (Aug 2026 — coordinator V-054 / app T-013)

Chat threads have no versioned history and no client-side branches.
Every "go back" interaction composes exactly two server operations:
the **truncate** primitive (`POST /v1/chats/{id}/truncate`, idempotent,
`chat.write`-gated, deletes rows after a cursor) and a normal send.

- **Edit** = truncate before the message, then send the new text.
- **Revert to here** = truncate through the end.
- **Regenerate** = truncate before the assistant reply, resend the same text.
- No forks, no "other branch" navigation, no undo beyond what the
  server has already deleted. What is dropped is gone.

Rules that follow:

- The server is the only editor of record (D001/D011 lineage): the
  client never rewrites its local copy except by refetching after
  truncation — optimistic UI applies to sends only.
- `request_id` idempotency covers both the send and the truncate, so
  a retried edit/revert/regenerate cannot double-delete or double-send.
- Agent run transcripts reuse the same conversation layout but are
  append-only observations of backend events — no truncate semantics
  exist there, and the UI must not imply them.

---

## D027 — LLM replies render a restricted markdown subset, monochrome-only

**Status:** Accepted (Aug 2026 — app T-014)

Assistant messages (chat and agent dialogue events) render a fixed
markdown subset via a dependency-free in-app parser feeding the MMD
text component's `AnnotatedString` overload. No WebView, no
third-party renderer, no HTML — ever (protocol constraint: responses
are structured text, never arbitrary markup).

Supported: headings, bullet/ordered/task lists (nested), fenced code
blocks, quotes, rules, `**bold**`, `*italic*`, `***both***`, `` `code` ``,
`~~strike~~`, `[label](url)` rendered as an underlined label.

Deliberately unsupported:

- `_underscore emphasis_` — snake_case identifiers would mangle;
  asterisk-only, always,
- tables and nested inline styles — rare in conversation, costly on
  e-ink; render as plain text instead of breaking,
- emphasis requires non-space content boundaries (`2 * 3 * 4` stays
  literal — CommonMark rule).

Styling is monochrome-only (weight, family, decoration, size — D017):
no color, no animation; code blocks are bordered monospace cards.
Message previews (thread lists, jump indexes) strip markers rather
than render them.

---

## D028 — Notes are file-authoritative vault objects; the server is a walk-index over them

**Status:** Accepted (Aug 2026 — coordinator V-060a / app notes leg)

Notes are Markdown files in the Obsidian vault. There is no notes table:
`GET /v1/notes` walks the vault (PARA layout) on demand and projects rows;
reads and writes resolve directly to files. The scratchpad file carries
role `scratchpad` and stays pinned first in the list; files under
`00 - Inbox/` carry role `inbox`; everything else is role `note`. List
rows are capped (200) and carry `id/title/preview/category/role/
updated_at` plus optional `project_id` (`vault:project:<x>`), `area_id`
(`vault:area:<x>`), `source_type`/`source_id` derived from path and
frontmatter. Detail adds the full `text` (frontmatter included) and a
`checksum`.

Write path: `PUT /v1/notes/{id}` is a text write-through with an
optimistic lock — the client sends `expected_checksum`; on mismatch the
server answers 409 with the fresh note attached, the client reloads, and
nothing is lost. `POST /v1/notes` (deliberate save) creates an individual
file under `00 - Inbox/`, idempotent per `request_id` because file + git
commit are external effects (§15). Every mutation lands as a vault git
commit — the vault remains the durable store and the coordinator stays a
projection (D001/D004 lineage).

---

## D029 — Chat has three explicit scope tiers; re-scoping is one endpoint and never automatic

**Status:** Accepted (Aug 2026 — coordinator V-062/V-063 / app T-022d)

A chat thread carries `scope_type` + `scope_ref` (both null = general):

- **General** — unscoped conversation against the swappable LLM backend
  (D024). Sends on unscoped threads may return a `proposed_topic`
  `{id, label}` chip computed by the deterministic sorter rules
  (alias-in-first-line 5 / keyword-in-first-line 3 / keyword-in-body 2,
  threshold 3, registry order breaks ties) — a proposal only; the server
  never applies it.
- **Topic** — vault-seeded context. The topic registry IS the notes
  sorter's bucket registry (one source of truth, exposed as
  `GET /v1/chat/topics`); the bucket's `00 - Inbox/*.md` seed files land
  in the first assistant reply of a scoped thread.
- **Workspace** — repo-bound execution via OpenCode, not the chat LLM
  (D025 lineage). Sends are async: the thread flips `pending_reply`,
  the reply settles later, and each successful turn auto-commits the
  repo (`committed: true/false` on settle).

All re-scoping — chip Apply, picker change, un-scope back to General —
goes through exactly one primitive, `POST /v1/chats/{id}/scope`
(idempotent, `chat.write`-gated; absent/null `scope_type` clears the
scope). Scope changes are always explicit user actions, mirroring the
capture confirm rule (§20): the system may propose, never re-route
silently. The client polls the thread while `pending_reply` is set
(15 s tick); the change cursor (§11) remains the correctness mechanism
for settlement.

---

## D031 — Today is a three-tab surface (NOW / TASKS / ATTENTION) with one scope-limited mutation

Today renders as three swipeable/tappable tabs instead of five stacked
sections. Each tab has exactly one dominant element (a NEXT-UP hero card, a
checkbox task list, a stack of attention cards); empty sections do not render
at all. The Agents section and Recent-note row are removed from Today —
agents stay reachable via More → Agents and attention deep-links; their
future placement in the UI is explicitly open.

Quick-complete (tap ○ → ✓ on the TASKS tab) is the single deliberate
exception to "Today is a view, not a source of truth". It rides the existing
revision-guarded `completeTask` mutation; NOW and ATTENTION remain pure
views. No other mutation may be added to Today without a new decision.

(D030 is reserved for the calendar surface decision recorded in
`docs/plans/2026-08-25-t023-calendar.md`.)

## D032 — The phone is not a mesh client: connectivity is windowed WireGuard to one endpoint, owned by the app

The Kompakt leaves the Tailscale mesh. Measured cause (2026-09-02
forensics): a full mesh client on a de-Googled phone has no FCM signaling
channel, so Tailscale falls back to ~36 s keepalives — 99.8% of app AP-wakeups,
modem awake 82% of on-battery time, ~6%/h drain. This is true regardless of
control plane (SaaS or Headscale); it is a property of running the full client.

Replacement: a plain WireGuard tunnel embedded in the Kompakt-Interface app
(`com.wireguard.android:tunnel` library, own VpnService) with a single static
peer — dev-server. Connectivity is **windowed and app-scheduled**: tunnel up →
pull (ntfy, Radicale, coordinator API) → tunnel down, on a ~15 min cadence and
on app-foreground. WireGuard itself is silent between windows; nothing dials
in. AllowedIPs on the phone cover only the server's tunnel address — the
phone never becomes a router for other traffic.

Consequences:

- The tailnet remains for laptops/admin nodes; dev-server bridges both
  (mesh member + wg endpoint). SSH/ops to the phone from outside is
  LAN-only; the phone is a spoke, never a destination.
- The Tailscale app stays installed on the phone as a dormant fallback
  (appops-restricted); it is not part of normal operation.
- Notifications arrive batched at window boundaries; true emergencies ride
  cellular SMS/calls, which are unaffected.
- Sync state is cursors, not sessions: missed windows catch up on the next
  one. The server treats the phone as an occasionally-connected client.

This supersedes the vault ground rule "One VPN substrate: Tailscale / no
WireGuard app" (2026-08-31) for this device — that rule predates the battery
findings. For all *other* devices the rule stands.

## D033 — Hermes joins the agents panel as a third backend: trusted lane, adapter-minted identity

The phone's agents panel gains the Hermes agent (the same brain that
runs the chat surface) — not by teaching the app a new protocol, but by
adding a third `AgentBackend` adapter inside vault-coordinator (D025:
general at the /v1 boundary, specific inside adapters). Zero app
changes, zero protocol changes, zero new credentials.

Two decisions inside it:

- **Trusted lane.** Warren runs sandboxed; OpenCode runs unsandboxed in
  a chosen repo directory. Hermes runs on the whole home server with
  its full toolset — the widest lane yet, surfaced honestly as
  `sandboxed: false` so the UI can badge it. The trust boundary is the
  dispatch act itself: the user typed the prompt on the phone.
- **Adapter-minted identity.** Hermes's native run ids share the
  `run_` prefix with Warren — passing them through would corrupt the
  registry's prefix-based backend inference. The adapter therefore
  mints `hms_<hex>` execution ids and passes them to Hermes as the
  run's `session_id` (which also buys native multi-turn resume); the
  internal `run_<hex>` per turn never crosses the boundary. The stable
  id and the current-run mapping are persisted in `hermes_session_runs`
  so watcher polls survive coordinator restarts.

Wire honesty follows the port's rules: `live_steering: false` is
genuinely none (no injection API and no spawn-fold queue), commands
are empty, `message.delta` SSE frames are dropped by design (the e-ink
phone never renders streaming text), and a run that vanished in a
backend restart surfaces as FAILED — "run lost — backend restarted?" —
so the V-057 watcher settles instead of polling forever.

Implementation: vault-coordinator V-073 (`src/agents/adapters/hermes.py`,
45 contract checks, suite 560 green).

## D034 — The General chat tier runs the full Hermes agent: the phone's normal chat becomes the Telegram brain

The normal (unscoped) chat tier stops being a thin LLM completion and
routes through the Hermes agent itself — the same brain the user talks
to on Telegram: persistent memory, full toolset, same persona. Not a
lookalike; the actual agent, via the D033 adapter.

Decisions inside it:

- **One thread = one persistent session.** Each general chat thread
  mints its own `hms_` session on first send and reuses it for every
  later message (`chat_threads.agent_execution_id`, `hms_`-prefix
  checked — the same column V-063 uses for OpenCode sessions; the
  prefix disambiguates which backend owns it). The agent's transcript
  lives in Hermes's session DB; the coordinator never replays stored
  history into runs — it just sends the new message, and today's
  session-resume fix does the rest. Same agent, separate conversation:
  the phone thread does not see the Telegram transcript, and vice
  versa.
- **Scope tiers stay honest.** Topic threads keep their vault-seeded
  quick-LLM character (a deliberately scoped lane); workspace threads
  stay on OpenCode (repo-bound). Only the general tier upgrades. If
  the agent backend is down or disabled, general chat falls back to
  the old LLM lane — chat keeps working, never a dead screen.
- **V-063's binding rule carries over.** Chat turns create NO
  `agent_executions` rows; the watcher/alert loop never fires for
  chat. Late replies (> `chat.agent_timeout_s`, default 100 s — under
  the phone's 120 s IO timeout) get an honest "(agent still
  working…)" note plus `pending_turn`, and are backfilled on thread
  open or next send. Busy/failure degrade to honest notes; the user
  message always stands.

Zero APK changes: the send response contract is unchanged
(`message` + `assistant_message` + optional `proposed_topic`); only
who answers differs.

Implementation: vault-coordinator V-074 (23 contract checks; suite
583 green). Adapter gained two restart-survival fixes (send
materializes from the persisted mapping after a coordinator restart;
a lost run no longer kills the session — run lost ≠ session lost,
the next send starts a fresh run in the same session).

## Deferred Decisions

The following remain intentionally open:

- ~~SSE vs WebSocket for foreground transport~~ — **resolved Aug 2026**:
  SSE via `GET /v1/alerts/stream` (V-058 / T-019); unread replay on
  connect, heartbeat comments, read-state dedupe (no Last-Event-ID),
- ~~whether server-side STT is enabled in v0.1~~ — **resolved Aug 2026**:
  yes; CPU faster-whisper behind `POST /v1/voice/transcribe`
  (V-059 / T-021),

- exact canonical capabilities endpoint path ~~(D021: `GET /v1/capabilities`)~~,
- final WorkManager fallback interval,
- exact ntfy topic/payload format,
- exact APK update mechanism,
- physical-button integration,
- exact calendar data integration,
- exact cache retention durations,
- whether Class 4 actions may ever be approved from Kompakt,
- final open-source license for the app repository.

These should be resolved through implementation or real-device testing.
