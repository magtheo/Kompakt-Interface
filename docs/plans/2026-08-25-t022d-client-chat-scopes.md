# T-022d client leg — chat scopes UI (topic picker, propose chip, workspace chats)

Server side (V-062/V-063) is live and E2E-verified. This plan is the Android
slice. Design source of truth: server repo `docs/plans/2026-08-25-t022d-chat-scopes.md`.

## Wire contract (verified against live coordinator source, not guessed)

- `GET /v1/chat/topics` → `{"topics": [{"id": <bucket.key>, "label": <bucket.name>}]}`
- `POST /v1/chats` body gains optional `scope_type` + `scope_ref` (both null = general)
- `POST /v1/chats/{id}/scope` body `{request_id, scope_type?, scope_ref?}` → `{"chat": thread}` — nulls clear; the ONLY re-scope path
- Send envelope gains `proposed_topic: {id, label}` (unscoped threads only) and `workspace: {state: settled|pending|busy|error|unavailable, execution_id?, committed?}`
- Thread wire gains `scope_type`, `scope_ref`, `scope_label`, `pending_reply` (bool)
- `pending_reply: true` == a workspace turn is still running → messages GET does server-side catch-up → client polls while flag is set

## Changes

### 1. domain/Chat.kt
- `ChatThread` + `scopeType/scopeRef/scopeLabel: String? = null`, `pendingReply: Boolean = false` (defaults → old-server tolerant; explicitNulls=false → absent decodes as default)
- `ChatThreadDraft` + `scopeType/scopeRef: String? = null`
- New `ChatTopic(id, label)` @Serializable — reference data, mirrors Workspace
- `ChatExchange` + `proposedTopic: ChatTopic? = null`, `workspaceState: String? = null` (state only — execution_id/committed stay undecoded; UI needs neither)

### 2. Repositories
- `ChatRepository.setScope(chatId, scopeType, scopeRef, requestId): ChatThread`
- New `TopicRepository.observeTopics(): Flow<List<ChatTopic>>` (GET /v1/chat/topics, decodeList "topics", cold one-shot)

### 3. Remote (RemoteRepositories.kt)
- CreateBody + scope fields; SendEnvelope + proposed_topic/workspace (workspace wire = {state} only, ignoreUnknownKeys eats the rest)
- setScope → POST /v1/chats/$id/scope; RemoteTopicRepository

### 4. Fakes + FakeData
- FakeData: `topics` (mirror live registry subset); thread_002 scoped topic/kodeverket (demo of scoped row)
- FakeChatRepository: honors draft scope at create; setScope mutates (label looked up from injected topics+workspaces); sendMessage proposes on unscoped sends when text contains a topic id/label (deterministic demo of the chip)
- FakeTopicRepository over FakeData.topics

### 5. AppContainer
- RemoteStack.topics + SwitchTopicRepository + `val topicRepository`; SwitchChat gains setScope; fakeTopics field

### 6. ChatListViewModel
- + topicRepository, workspaceRepository ctor args; `topics`/`workspaces` StateFlows
- `newChat(scopeType = null, scopeRef = null)` — picker passes selection

### 7. ChatThreadViewModel
- + topicRepository, workspaceRepository ctor args (header re-scope picker)
- `proposedTopic: StateFlow<ChatTopic?>` — set from send result; cleared on dismiss/apply/scope change/new send without proposal
- `applyProposal()` → setScope("topic", id) → refresh; `dismissProposal()`; `setScope(type, ref)` public for header picker
- pending_reply polling: collect `thread`; when pendingReply → delay 15 s → refreshTick++ (self-sustaining until flag clears; messages GET triggers server catch-up)

### 8. UI (ChatScreens.kt)
- List screen: "New chat" row expands scope picker (General / topics / workspaces) — T-022c inline ListRow convention, ● marks current, tap selects AND creates
- Thread screen: header scope row ("Scope: label|none" ▸/▾) expanding to same picker (tap → viewModel.setScope); propose chip row ("Move to topic: X?" [Move][Not now]) below header when proposedTopic != null; pendingReply row ("Workspace turn running — updating…")
- Shared private `ScopePickerRows` composable; no new material deps

### 9. Tests
- ChatContractTest: topics GET (path/auth/envelope/decode + empty); create-with-scope body (scope fields present when set, absent when null); scope POST body + decode; send envelope with proposed_topic + workspace decode
- ChatViewModelsTest: ctor ripples (thread VM 9 sites + list VM 1); ColdChatRepository + setScope recording; new: proposal surfaces → apply lands setScope("topic", id) + clears; dismiss clears; pendingReply poll ticks refresh after virtual 15 s and stops when flag clears; list newChat carries scope into draft
- FakeData threads: scope fields defaulted — no fixture ripples

## Verification
verify.sh (lint + compile + 214+ tests). On-device leg rides b-4 with the phone.
