# Distribution and Reuse Model

## Purpose

Defines what gets published when the system goes open-source (Phase 16), how third
parties adopt it, and the rules that keep the parts interchangeable. Binds D023;
executed by Phase 16 of `development-plan.md`.

## The three artifacts

1. **kompakt-protocol** (new repository, to be created) — the standalone `/v1/`
   protocol specification: endpoints, wire format, capability negotiation,
   enrollment and trust model, error semantics, sync semantics. This is the
   product boundary. A third-party backend author implements against this
   document alone and never needs to read client or coordinator code. Versioned
   by protocol version, not code version.
2. **Kompakt-Interface** (this repository) — the Android client. Audience: end
   users (APK) and client contributors.
3. **vault-coordinator** (reference implementation) — the Python server.
   Audience: self-hosters who want the full stack. Ships with the vault adapter
   isolated so it is swappable.

## Why not a monorepo

- The D021 contract-first architecture *is* the separation. One repo signals
  "one product with an internal API" — the opposite of the email model
  (any client, any server, shared spec).
- Audiences differ: a Go backend implementor should not have to clone an
  Android toolchain; an APK user does not care about the coordinator.
- Cross-cutting change is already handled where it belongs: `/v1/` versioning,
  capability negotiation, and contract tests on both sides (MockWebServer on the
  client, live smoke against the coordinator). A monorepo's atomic commits add
  nothing.
- Release cadences differ: APK builds vs `git pull` + service restart vs spec
  revisions.

## Adoption paths (how others make use of it)

1. **Full-stack self-host** — run vault-coordinator + this app. Requires a vault
   matching the coordinator's reader conventions, **or** a custom vault adapter.
   Constraint: coordinator vault paths/conventions must become configuration,
   not baked-in constants, before this path is fully supported (see Gaps).
2. **Own backend, same app (email model)** — implement `/v1/` per
   kompakt-protocol behind any storage (SQL, plain files, SaaS). The app cannot
   tell the difference; the contract tests are what guarantee that. Nothing in
   the client assumes a Markdown vault.
3. **Fork the coordinator** — keep the server, swap the vault adapter for the
   desired structure.

## Binding protocol rules

These keep backends interchangeable and are part of the contract, not stylistic
preferences:

- **The wire is resource-shaped, never vault-shaped.** No file paths, PARA
  terminology, frontmatter conventions, or storage details may appear in any
  `/v1/` request or response. Object IDs use the defined ID spaces (D021:
  `vault:…`, `machine:…`, `vikunja:…`, `repo:…`). A "vault" is an
  implementation detail of one reference server.
- **The wire contract is owned by kompakt-protocol.** Changes to endpoints,
  wire fields, or semantics = protocol version bump + updated contract tests on
  both sides, never a silent drift.
- **Publication gate:** a leakage audit sweeps every `/v1/` response for
  vault-concept leakage before anything goes public (Phase 16 checklist item).

## Sequencing when going public

1. Extract the protocol spec from this repository's docs
   (`protocol-and-sync.md`, the enrollment/trust sections of `security.md`,
   D021 wire details, D022 enrollment wire) into the `kompakt-protocol` repo.
   This is the keystone — do it first.
2. Publish vault-coordinator with the vault adapter isolated and its README
   identifying it as the reference implementation of kompakt-protocol.
3. Publish the client per Phase 16 as written (README, license, SECURITY.md,
   CONTRIBUTING.md, signed APK, checksums).

The three can land in any actual order, but artifacts 2 and 3 reference the
protocol repo, so it must exist first.

## Current gaps (honest state, Aug 2026)

- Coordinator vault paths/conventions are partly baked in — needs
  config-extraction before "bring your own vault" self-hosting is real.
- Single-user per instance. Fine for self-host; multi-tenant is explicitly not
  v0.1 (`development-plan.md` §20).
- Auth is per-instance: each server mints its own admin/device tokens. No
  central service, no phone-home — a strength for publication.
- License decision still open (Deferred Decisions; Apache-2.0 direction per
  Phase 0 licensing note).

## Relationship to existing docs

- `development-plan.md` Phase 16 executes this model.
- `protocol-and-sync.md` + D021/D022 define the technical contract this
  distributes.
- `decisions.md` D023 binds the three-artifact split.
