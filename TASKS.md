# Tasks

Convention: `TODO(T-NNN)` in code references an ID below. Branches carry the
ID (e.g. `feat/T-005-enrollment`). Orphan detection: grep for `TODO(T-` with
no matching open task.

Source of truth for phases: `docs/development-plan.md`.
Verification gate: `scripts/verify.sh` (local — GitHub Actions disabled
Aug 2026 due to account billing; re-enable later by `gh workflow enable CI`).

- [x] T-001 Phase 0 — Repository & tooling foundation
- [x] T-002 Phase 1 — App shell + E-Ink navigation (17 placeholder screens, mocked data)
- [ ] T-003 Phase 2 — Domain model + repository interfaces (mock fakes)
- [ ] T-004 Phase 3 — Server contract skeleton (capabilities + read endpoints)
- [ ] T-005 Phase 4 — Device enrollment + security boundary
- [ ] T-006 Phase 5 — Today + Organize vertical slice

## Notes

- T-001: CI workflow committed but disabled (`gh workflow disable CI`) —
  GitHub Actions blocked by billing on the magtheo account. Local gate
  `scripts/verify.sh` is authoritative until re-enabled.
- T-002: More and Organize are distinct screens per dev plan §3 (More ⊃
  Organize/Inbox/Settings; Organize ⊃ Projects/Areas/Tasks/Notes) — 17
  routes total (16 listed screens + More menu itself).
