# Kompakt-Interface

A Mudita Kompakt-native personal interface for a server-centered computing system.

A thin, low-distraction, voice-first Android client backed by an authoritative
server: Today dashboard, conversational chats, agent/process management,
vault-structured tasks and notes, calendar surface, notifications and
app-switcher quick surfaces, and universal capture — with the server owning
all durable state, AI execution, and credentials.

**Status:** actively developed and running on real hardware (Mudita Kompakt,
inkOS). Core surfaces are implemented and field-tested; see `TASKS.md` for the
per-task history and `docs/development-plan.md` for the phase plan. The client
is one half of a pair — it is useless without its server
([vault-coordinator](https://github.com/magtheo/vault-coordinator)), which owns
the data, the LLM/agent backends, and enrollment.

## What it is not

- Not a standalone app: no server, no data. There is no local account system
  and no cloud service run by us — you run your own coordinator.
- Not a general launcher/OS replacement (though it does integrate deeply
  with the Kompakt's capacitive keys and e-ink constraints).
- Not finished: voice, background delivery, and Kompakt-specific tuning are
  later phases per the development plan.

## Documentation

The documents in `docs/` are the source of truth. Read in this order:

1. [`docs/vision.md`](docs/vision.md) — what the product is and is not
2. [`docs/user-experience.md`](docs/user-experience.md) — screens, interaction modes, E-Ink UX rules
3. [`docs/technical-architecture.md`](docs/technical-architecture.md) — stack, client architecture, domain model
4. [`docs/protocol-and-sync.md`](docs/protocol-and-sync.md) — delivery, versioning, capability negotiation, revisions/cursors, offline queue
5. [`docs/security.md`](docs/security.md) — trust model, enrollment, device trust tiers, action risk classes
6. [`docs/decisions.md`](docs/decisions.md) — decisions register (D001–D036); binding, do not redesign during implementation
7. [`docs/development-plan.md`](docs/development-plan.md) — phased implementation plan and v0.1 definition of done
8. [`docs/distribution.md`](docs/distribution.md) — three-artifact distribution model, third-party adoption paths, protocol boundary rules (D023)

## Building

Requirements: Android SDK 35, JDK 17. Standard Gradle build:

```bash
./gradlew :app:assembleDebug
```

Unit tests (CI runs the same):

```bash
./scripts/verify.sh          # or: ./gradlew :app:testDebugUnitTest
```

### Endpoints (deployment-specific)

Connectivity is windowed WireGuard to a single server endpoint (D032). The
endpoint addresses are **not** in source control: they are injected from the
untracked `local.properties` via BuildConfig, defaulting to RFC 5737
documentation addresses:

```properties
# local.properties (git-ignored)
kompakt.wg.lanEndpoint=192.168.1.10:51821
kompakt.wg.publicEndpoint=vpn.example.com:51821
```

The end-state (T-049) is for enrollment to deliver endpoints per device, so no
build-time configuration is needed at all.

### Live-server smoke test (optional)

`LiveServerSmokeTest` runs only when `KOMPACT_LIVE_URL` and
`KOMPACT_LIVE_TOKEN` are set — secrets never live in the repo.

## First milestone

> A normal Android phone can securely enroll, open Today, browse vault-derived
> Projects/Areas/Tasks/Notes, and create a confirmed task/note capture through
> the real server.

## License

Apache License 2.0 — see [LICENSE](LICENSE). Resolved as decision
[D036](docs/decisions.md); earlier "to be decided" notes are superseded.
