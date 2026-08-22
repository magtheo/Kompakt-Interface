# Kompakt-Interface

A Mudita Kompakt-native personal interface for a server-centered computing system.

A thin, low-distraction, voice-first Android client backed by an authoritative
server: Today dashboard, conversational chats, agent/process management,
vault-structured tasks and notes, and universal capture — with the server
owning all durable state, AI execution, and credentials.

**Status:** specification stage. The documents in `docs/` are the source of
truth; implementation follows `docs/development-plan.md`.

## Documentation

Read in this order:

1. [`docs/vision.md`](docs/vision.md) — what the product is and is not
2. [`docs/user-experience.md`](docs/user-experience.md) — screens, interaction modes, E-Ink UX rules
3. [`docs/technical-architecture.md`](docs/technical-architecture.md) — stack, client architecture, domain model
4. [`docs/protocol-and-sync.md`](docs/protocol-and-sync.md) — delivery, versioning, capability negotiation, revisions/cursors, offline queue
5. [`docs/security.md`](docs/security.md) — trust model, enrollment, device trust tiers, action risk classes
6. [`docs/decisions.md`](docs/decisions.md) — decisions register (D001–D020); binding, do not redesign during implementation
7. [`docs/development-plan.md`](docs/development-plan.md) — phased implementation plan and v0.1 definition of done

## First milestone

> A normal Android phone can securely enroll, open Today, browse vault-derived
> Projects/Areas/Tasks/Notes, and create a confirmed task/note capture through
> the real server.

## License

To be decided (see Deferred Decisions in `docs/decisions.md`). No substantial
GPL code may be incorporated before this is settled.
