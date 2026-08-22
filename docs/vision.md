# Vision

## Purpose

This project is a **Mudita Kompakt-native personal interface for a server-centered computing system**.

The phone is not intended to become a general-purpose smartphone or a miniature laptop. Its role is to act as a **small, low-distraction, voice-first control surface** for:

- daily life,
- tasks,
- notes,
- calendar context,
- conversations,
- AI agents,
- projects,
- and items that require the user's attention.

Most computation, orchestration, durable state, web access, AI execution, and project data live on the server.

The phone should primarily help the user:

1. **See** what matters now.
2. **Capture** thoughts, tasks, notes, and requests quickly.
3. **Ask** general questions in conversational chats.
4. **Delegate** work to AI agents.
5. **Inspect** what running agents are doing.
6. **Decide** when an agent or task needs approval or direction.
7. **Remember** personal commitments without turning the phone into a distracting application platform.

---

## Core Product Idea

The Kompakt is best treated as:

> A personal daily dashboard, capture tool, conversational interface, and agent terminal backed by a server.

The server compensates for the things the Kompakt is intentionally poor at:

- heavy web browsing,
- large-screen workflows,
- long-form editing,
- local AI inference,
- code inspection,
- large document review,
- complex project management,
- and high-frequency visual interaction.

The Kompakt contributes what is valuable:

- small physical form factor,
- E-Ink display,
- low distraction,
- cellular connectivity,
- microphone,
- phone/SMS capability,
- a persistent always-carried interface,
- and enough Android support to run a purpose-built APK.

---

## Product Principles

### 1. The phone is a thin client

The phone should not own the important state of the system.

Durable state should live on the server:

- tasks,
- notes,
- chats,
- agent runs,
- projects,
- inbox items,
- project metadata,
- credentials,
- and AI/tool integrations.

The phone may keep a small cache for responsiveness and temporary offline use.

---

### 2. The phone is not a vault

The Kompakt should be treated as a **low-trust endpoint**.

Do not store:

- master passwords,
- recovery codes,
- root SSH keys,
- powerful API tokens,
- database credentials,
- long-lived secrets,
- or other high-value credentials.

The device should have a narrow, revocable identity that grants only the capabilities required by the app.

If the phone is lost or compromised, access should be revocable without threatening the server or the rest of the system.

---

### 3. Distinct interaction modes stay distinct

The app should not collapse everything into one generic "AI interface."

The following concepts are separate:

- **Chat** — conversational thinking and general questions.
- **Agents** — autonomous or semi-autonomous workers running on the server.
- **Tasks** — structured things that need to be done.
- **Notes** — persistent captured information or thoughts.
- **Projects** — organizational context.
- **Today** — an aggregated view of what matters now.
- **Inbox** — an aggregated view of things that need attention.

These may reference each other, but should not silently become each other.

Examples:

- a chat message may be explicitly saved as a note,
- an agent result may explicitly create a task,
- a task may explicitly be sent to an agent,
- a note may explicitly be opened in chat for discussion.

The transition should be visible and intentional.

---

## Primary Domains

### Chat

Chat is a general-purpose conversational interface.

It should support:

- multiple chat threads,
- persistent conversation history,
- general questions,
- research requests,
- brainstorming,
- project conversations,
- follow-up questions,
- and temporary or disposable chats if useful later.

Chat is not the same thing as an agent.

A chat is a **conversation context**.

---

### Agents

Agents represent active workers and ongoing capabilities on the server.

The user should be able to:

- see which agents exist,
- see which are running,
- inspect what they are currently doing,
- see recent runs,
- open results,
- message or redirect an agent,
- stop a run when permitted,
- and respond when an agent needs input.

The UI should distinguish between:

#### Persistent agents
Long-lived roles or workers.

Examples:

- project coordinator,
- job-search agent,
- research agent,
- server maintenance agent.

#### Agent runs
Specific executions.

Examples:

- audit PR #55,
- compare two technologies,
- find summer courses,
- inspect a CI failure.

Agents are closer to a **process manager** than to a chat application.

---

### Tasks

Tasks are structured commitments.

A task may contain:

- title,
- status,
- due date/time,
- project,
- notes,
- source,
- and available actions.

Common actions include:

- complete,
- postpone,
- edit,
- assign to project,
- ask agent,
- and archive.

---

### Notes

Notes are persistent captured information.

Notes should be quick to create and should not require immediate organization.

Examples:

- an idea,
- a reminder that is not yet a task,
- a thought about a project,
- a useful observation,
- a piece of information worth keeping.

The system may later suggest project association or classification, but capture should remain frictionless.

---

### Projects

Projects provide context across the system.

A project may relate to:

- tasks,
- notes,
- chats,
- agent runs,
- and project status.

Projects should not become full desktop project-management screens on the Kompakt.

The phone should show a compact projection:

- current goal,
- next action,
- attention needed,
- active agents,
- recent activity.

---

## Aggregated Views

### Today

Today answers:

> What matters now?

It is a view, not a source of truth.

It may combine:

- today's calendar events,
- today's tasks,
- overdue items,
- items needing attention,
- active or recently completed agents,
- and optionally selected notes.

A typical Today screen may contain:

1. date,
2. next event,
3. today's tasks,
4. needs-attention section,
5. active agents,
6. quick capture.

---

### Inbox

Inbox answers:

> What needs me?

It is also a view, not a source of truth.

Inbox items may originate from:

- agents,
- tasks,
- calendar/reminders,
- server/system alerts,
- projects,
- or other integrations.

Examples:

- agent needs approval,
- agent finished,
- task overdue,
- build failed,
- job listing found,
- project blocked.

Selecting an inbox item should open the original underlying object.

---

## Universal Capture

Fast capture is one of the most important functions of the product.

The user should be able to quickly speak or type something and turn it into:

- a note,
- a task,
- a chat message,
- or an agent request.

A future server-side intent classifier may propose the likely destination.

Example:

> "Remind me tomorrow to call the dentist."

The server may propose:

- Type: Task
- Title: Call dentist
- Due: Tomorrow

The user should confirm before structured data is created.

The system should not silently turn conversation into tasks or notes.

---

## Voice-First Interaction

Voice is expected to be the primary text-entry method.

The intended interaction is:

1. speak,
2. inspect the result,
3. correct a small number of words if necessary,
4. submit.

Swipe/glide typing may be used mainly for corrections rather than long-form typing.

Speech recognition may eventually run server-side so the Kompakt does not need to perform heavy local inference.

---

## The Phone and the Server

The conceptual split is:

### Phone

Responsible for:

- presentation,
- capture,
- local interaction,
- a small cache,
- notifications,
- narrow offline queueing,
- and device authentication.

### Server

Responsible for:

- authoritative state,
- AI agents,
- chat state,
- project state,
- task/note persistence,
- web access,
- integrations,
- indexing/search,
- orchestration,
- and high-value credentials.

The phone should remain replaceable.

A replacement device should be able to enroll and regain access to the same server-side state.

---

## Security Vision

The system should be designed under the assumption:

> The phone may eventually be lost or compromised.

A compromised phone may be able to see what the user sees and submit requests using the phone's identity.

It must not automatically gain:

- unrestricted shell access,
- root access,
- server filesystem access,
- secrets,
- master credentials,
- arbitrary deployment ability,
- or destructive administrative control.

The phone should receive only narrow capabilities.

Sensitive or destructive actions should require a more trusted device or a stronger authorization path.

---

## What This Product Is Not

It is not:

- a full Android replacement,
- a general-purpose smartphone suite,
- a browser-centric interface,
- a desktop project-management system,
- a local AI runtime,
- a terminal-first workflow,
- or one giant chatbot screen.

It is a **purpose-built personal operating interface** for an existing server-centered system.

---

## Initial Product Scope

The first useful version should focus on:

### Primary surfaces

- Today
- Chat
- Agents
- Tasks
- Notes
- Inbox

### Supporting surfaces

- Item Detail
- Projects
- Settings
- Capture

Projects may remain minimal in the earliest release.

---

## Longer-Term Direction

Possible later extensions include:

- richer project summaries,
- job-search workflows,
- calendar integration,
- server alerts,
- approval workflows,
- recurring agent jobs,
- background synchronization,
- controlled notifications,
- offline queueing,
- physical-button shortcuts,
- server-side speech recognition,
- and specialized views for common personal workflows.

The design should remain disciplined.

New functionality should be added only when it helps the user:

- see,
- capture,
- ask,
- delegate,
- decide,
- or remember.

