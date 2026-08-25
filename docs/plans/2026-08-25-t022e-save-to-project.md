# T-022e — Save to project + project detail surface

Date: Aug 25, 2026. Arc: locked T-022c→e decisions (`references/v-061-workspaces.md`).
Server leg = **V-064** (vault-coordinator). Client leg = **T-022e** (this repo).

## Goal

Two deliverables:

1. **"Save to project"** — deliberate note saves can target a vault project
   folder instead of `00 - Inbox/` (run-result "Note" transition gains a
   target picker).
2. **Project detail surface** — tapping a project in Organize opens a detail
   screen: workspace-scoped chats + project notes (the "context" of the
   project), with tasks one tap deeper.

## Grounding (verified against shipped code, Aug 25)

- `POST /v1/notes` (`NoteCreateRequest {request_id, title, text,
  source_type?, source_id?}`) → `create_note_file()` always writes
  `00 - Inbox/<yyyy-mm-dd>-<slug>.md` (unique `-2/-3` suffix), frontmatter
  `title/captured/source/source_id`, one scoped git commit, idempotent via
  mutations ledger, result `{replayed, kind: note_created, note}`.
- Client `NoteDraft` ALREADY has `project_id`/`area_id` `@SerialName` fields
  (null-omitted today; server ignores unknowns per D010) — no DTO change needed.
- `_wire_row` derives `project_id: vault:project:{slug}` from
  `02 - Projects/<name>/…` paths — a note written into a project folder joins
  automatically, zero extra plumbing.
- `list_vault_projects(config)` → `[{name, slug, kind, path}]` (folder name is
  authoritative; `slugify` = lowercase + spaces→hyphens + server overrides).
- `/v1/projects` mixes `vault:project:{slug}` (PARA) and
  `machine:project:{repo_id}` (raw repo ids, mostly lowercase already).
  Workspace chat scope refs are repo-dir slugs — they overlap both families
  (live: `dev-server`, `evershift`).
- `ProjectsScreen` rows currently drill into tasks (`onOpenProjectTasks`).
- `AgentRunDetailViewModel.saveNote()` → `createNote(NoteDraft(text=…,
  sourceType=AGENT_RUN, sourceId=run.id))` → inbox, unconditional.

## V-064 design (server)

- `NoteCreateRequest` + `project_id: str | None = None`.
- Validation: must be a full id `vault:project:{slug}`; resolve slug against
  `list_vault_projects` (slug→**folder name**); unknown slug → 422; other
  prefixes (`machine:`) or bare slugs → 422 with clear detail. Only vault
  projects have folders — machine projects get the chat join instead.
- `create_note_file(..., project_name: str | None = None)`:
  - `project_name` set → `02 - Projects/<name>/<yyyy-mm-dd>-<slug>.md`
    (same unique-suffix logic, folder exists by construction),
  - else → inbox (unchanged behavior).
  - Frontmatter + commit scope identical.
- No new endpoint; `note.write` + `notes` flag unchanged. Old clients keep
  working (field optional); new clients against old servers are safe
  (unknown field ignored → note lands in inbox; acceptable degradation).

## T-022e design (client)

### Save-to-project picker (agent run detail)

- The "Note" transition becomes an expandable target picker (T-022c
  convention: collapsed `Save to: Inbox ▸` ListRow → `Inbox` + one row per
  vault project; `●` none — selection saves + collapses; tap-through:
  selecting a target immediately saves with a transient status line).
- VM: `saveNote(project: Project?)` — null → inbox (current text), else
  `NoteDraft(projectId = project.id)`.
- Picker lists `vault:project:*` only (server contract).

### Project detail screen

- New route `organize/project/{id}` (URL-encoded, same mechanism as
  `chat:{uuid}` args).
- `ProjectsScreen` rows open detail; inside, a "Tasks" row drills into the
  existing task filter (nothing lost).
- Content sections:
  - Header: name + status glyph.
  - **Chats** — threads with `scopeType == "workspace"` and
    `scopeRef == workspaceRefFor(project)`: `vault:project:X → X`,
    `machine:project:X → slugify(X)` (client mirror: lowercase,
    spaces→hyphens; server-side slug overrides not replicated —
    documented limitation, only affects override-named machine repos).
    Tap → chat thread.
  - **Notes** — `observeNotes(projectId = id)` (wire filter already exists);
    machine projects show the D028 honest empty state ("Notes live in the
    vault — machine projects have none"). Tap → note editor.
- Fake mode: demo project note + workspace-scoped thread so the surface is
  indistinguishable (established convention).

## Tests

- Server (`tests/test_notes.py` extension): project-target create lands in
  the project folder + joins; unknown slug 422; machine/bare 422; default
  inbox unchanged; replay idempotency with target; collision suffix.
- Client: create-note wire body carries `project_id` only when set;
  run-detail VM passes target into draft; ProjectDetail VM chat/note
  filtering (vault + machine slugify); RoutesTest count ripple.

## Out of scope (deliberate)

- Area targets (`area_id` stays a client-anticipated field only).
- Chat-message-level save (message action rows unchanged this task).
- Server-side workspace↔project resolution endpoint (client-side join over
  existing lists; revisit if slug drift bites).
- Tasks section inside project detail (row-link only).
