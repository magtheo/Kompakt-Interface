# AGENTS.md

Guidance for autonomous coding agents working on this repository.

## Source of truth

Before writing code, read the docs in the order given in `README.md`.
The decisions register (`docs/decisions.md`) is binding:

- Do **not** redesign accepted decisions (D001–D023) during implementation.
- If you find a contradiction between docs, or believe a decision is wrong,
  **flag it** and record a new/updated decision — never silently replace
  architecture.

## Hard rules

- The server is authoritative. The client keeps only a cache and a narrow
  offline mutation queue (captures). No parallel hierarchy, taxonomy, or
  canonical store on the device.
- Chat, Agents, Tasks, Notes, and Projects remain distinct object types.
  Transitions between them are explicit user actions only.
- Security is enforced server-side. Client UI state proves nothing. No
  arbitrary shell endpoints, no arbitrary WebView rendering.
- E-Ink constraints apply from the first build, even on OLED/emulator:
  no animated navigation, no ripples, no decorative motion, monochrome,
  high contrast, static status indicators, minimal redraws.
- Build vertical slices (endpoint → repository → view model → screen → real
  interaction), not isolated layers.

## Conventions

- Kotlin + Jetpack Compose + Mudita MMD; StateFlow; Compose Navigation.
- Follow the phase order in `docs/development-plan.md`; do not skip ahead to
  later phases (voice, background delivery, Kompakt tuning) early.
- Keep dependency count low — the APK must remain easy to audit.
- Do not commit secrets of any kind. See `SECURITY.md` and `docs/security.md`.

## Task tracking

Work is tracked in `TASKS.md` using `TODO(T-NNN)` IDs, with branches named
after the task (e.g. `feat/T-003-enrollment`). Orphan detection: grep for
`TODO(T-` references that have no open task.
