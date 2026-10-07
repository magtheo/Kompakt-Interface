package dev.magnor.kompakt.ui.viewmodels

import dev.magnor.kompakt.data.PendingFirstMessages
import dev.magnor.kompakt.data.repository.ChatRepository
import dev.magnor.kompakt.data.repository.NoteRepository
import dev.magnor.kompakt.data.repository.TopicRepository
import dev.magnor.kompakt.data.repository.WorkspaceRepository
import dev.magnor.kompakt.domain.ChatExchange
import dev.magnor.kompakt.domain.ChatThread
import dev.magnor.kompakt.domain.ChatThreadDraft
import dev.magnor.kompakt.domain.ChatTopic
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.EntityKind
import dev.magnor.kompakt.domain.Message
import dev.magnor.kompakt.domain.MessageRole
import dev.magnor.kompakt.domain.MessageStatus
import dev.magnor.kompakt.domain.Note
import dev.magnor.kompakt.domain.NoteDraft
import dev.magnor.kompakt.domain.OfflineException
import dev.magnor.kompakt.domain.RequestId
import dev.magnor.kompakt.domain.Workspace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** T-022d: fixed reference data for scope pickers. */
private object StaticTopics : TopicRepository {
    override fun observeTopics(): Flow<List<ChatTopic>> =
        flowOf(listOf(ChatTopic(id = "evershift", label = "Evershift")))
}

/** T-022b: records save-note drafts; observe is never used by the VM path. */
private class ColdNoteRepository : NoteRepository {
    val createdDrafts = mutableListOf<NoteDraft>()
    var createOutcome: (NoteDraft) -> Note = { draft ->
        Note(id = "note_saved", text = draft.text, createdAt = t0Note, updatedAt = t0Note)
    }
    override fun observeNotes(projectId: EntityId?, areaId: EntityId?): Flow<List<Note>> = flowOf(emptyList())
    override fun observeNote(id: EntityId): Flow<Note?> = flowOf(null)
    override suspend fun getNote(id: EntityId): Note? = null
    override suspend fun createNote(draft: NoteDraft, requestId: RequestId): Note {
        createdDrafts += draft
        return createOutcome(draft)
    }
    override suspend fun updateNote(id: EntityId, text: String, expectedChecksum: String): Note =
        throw UnsupportedOperationException()
}

private val t0Note = Instant.parse("2026-08-25T12:00:00Z")

/** Shared idle instance — existing tests never touch the note path. */
private val StaticNotes = ColdNoteRepository()

private object StaticWorkspaces : WorkspaceRepository {
    override fun observeWorkspaces(): Flow<List<Workspace>> =
        flowOf(listOf(Workspace(ref = "roblox-toolkit", label = "Roblox Toolkit")))
}

/**
 * A chat repository whose observe flows are COLD ONE-SHOT fetches over a
 * mutable snapshot — exactly the RemoteChatRepository semantics. This is
 * what makes the send-overlay + refresh-tick logic observable in a JVM test.
 */
private class ColdChatRepository(
    var snapshot: List<Message>,
    var thread: ChatThread? = null,
    var sendOutcome: suspend (EntityId, String) -> ChatExchange = { _, text ->
        throw IllegalStateException("not expected")
    },
) : ChatRepository {
    val sentRequestIds = mutableListOf<RequestId>()
    var threadFetches = 0

    override fun observeThreads(): Flow<List<ChatThread>> = flow {
        emit(listOfNotNull(thread))
    }
    override fun observeThread(id: EntityId): Flow<ChatThread?> = flow {
        threadFetches++
        emit(thread)
    }
    /** T-051: counts cold observe fetches (one emission per collection). */
    var messagesFetches = 0

    /** T-051: gate/hook before each emission — suspend = fetch in flight, throw = fetch failed. */
    var beforeMessagesEmit: suspend () -> Unit = { }

    override fun observeMessages(chatId: EntityId): Flow<List<Message>> = flow {
        messagesFetches++
        beforeMessagesEmit()
        emit(snapshot)
    }
    override suspend fun getThread(id: EntityId): ChatThread? = thread
    override suspend fun createThread(draft: ChatThreadDraft, requestId: RequestId): ChatThread =
        throw UnsupportedOperationException()
    override suspend fun sendMessage(chatId: EntityId, text: String, requestId: RequestId): ChatExchange {
        sentRequestIds.add(requestId)
        return sendOutcome(chatId, text)
    }

    /** T-022d: recorded (scopeType, scopeRef) — nulls mean "clear". */
    val scopeCalls = mutableListOf<Triple<String?, String?, RequestId>>()
    var setScopeOutcome: (String?, String?) -> Unit = { _, _ -> }

    override suspend fun setScope(chatId: EntityId, scopeType: String?, scopeRef: String?, requestId: RequestId): ChatThread {
        scopeCalls.add(Triple(scopeType, scopeRef, requestId))
        setScopeOutcome(scopeType, scopeRef)
        return thread ?: throw IllegalStateException("no thread")
    }

    /** Recorded (keepThrough, requestId) pairs. */
    val truncations = mutableListOf<Pair<EntityId?, RequestId>>()
    var truncateOutcome: (EntityId?) -> Unit = { keepThrough ->
        snapshot = if (keepThrough == null) {
            emptyList()
        } else {
            val idx = snapshot.indexOfFirst { it.id == keepThrough }
            if (idx >= 0) snapshot.take(idx + 1) else snapshot
        }
    }

    override suspend fun truncate(chatId: EntityId, keepThrough: EntityId?, requestId: RequestId) {
        truncations.add(keepThrough to requestId)
        truncateOutcome(keepThrough)
    }
}

/**
 * T-009: thread send lifecycle — optimistic PENDING row, server ack overlay,
 * refresh dedupe (no duplicate rows), failure restores the draft, and retry
 * of the same text reuses the idempotency key (§11).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadViewModelTest {

    private val t0 = Instant.parse("2026-08-23T12:00:00Z")
    private lateinit var repo: ColdChatRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun msg(id: String, role: MessageRole, content: String, status: MessageStatus = MessageStatus.SENT) =
        Message(
            id = id, chatId = "chat_1", role = role, content = content,
            createdAt = t0, updatedAt = t0, status = status,
        )

    @Test
    fun `send shows optimistic pending then acks both messages without duplicates`() = runTest {
        repo = ColdChatRepository(snapshot = emptyList())
        var tick = 0
        repo.sendOutcome = { _, text ->
            val user = msg("chatmsg:u1", MessageRole.USER, text)
            val assistant = msg("chatmsg:a1", MessageRole.ASSISTANT, "reply to $text")
            // The server now holds both — the NEXT observe sees them (refresh tick).
            repo.snapshot = listOf(user, assistant)
            ChatExchange(user, assistant)
        }
        val vm = ChatThreadViewModel(repo, StaticTopics, StaticWorkspaces, StaticNotes,"chat_1", { "req-${tick++}" }, { t0 })
        val collector = launch(UnconfinedTestDispatcher()) {
            vm.messages.collect { /* keep the StateFlow hot */ }
        }

        vm.onDraftChange("hello server")
        vm.send()

        val rendered = vm.messages.value
        assertEquals(2, rendered.size)
        assertEquals("hello server", rendered[0].content)
        assertEquals(MessageRole.ASSISTANT, rendered[1].role)
        assertEquals("reply to hello server", rendered[1].content)
        assertTrue(rendered.all { it.status != MessageStatus.PENDING }) // ack replaced the optimistic row
        assertTrue(ChatSendState.Idle == vm.sendState.value)
        assertEquals("", vm.draft.value)
        collector.cancel()
    }

    /**
     * T-053: the send POST is held open for the whole agent turn
     * (RemoteChatRepository: timeoutSeconds = 120). Long before it returns,
     * the server has persisted the user message and flagged pending_reply —
     * so the V-063 poll refetches a snapshot that already contains the
     * server's copy. The optimistic local row must reconcile against that
     * snapshot or the sender sees their own message twice until the ack.
     */
    @Test
    fun `poll refetch while the send POST is in flight does not duplicate the outgoing message`() = runTest {
        // Share runTest's scheduler with Main so delay() is virtual-time driven.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val turnSettled = CompletableDeferred<Unit>()
        repo = ColdChatRepository(snapshot = emptyList(), thread = generalThread().copy(pendingReply = true))
        repo.sendOutcome = { _, text ->
            turnSettled.await() // POST held open for the whole agent turn
            val user = msg("chatmsg:u1", MessageRole.USER, text)
            val assistant = msg("chatmsg:a1", MessageRole.ASSISTANT, "reply to $text")
            repo.snapshot = listOf(user, assistant)
            ChatExchange(user, assistant)
        }
        val vm = ChatThreadViewModel(repo, StaticTopics, StaticWorkspaces, StaticNotes,"chat_1", { "req-dup" }, { t0 })
        val collector = launch(UnconfinedTestDispatcher()) { vm.messages.collect { } }
        testScheduler.runCurrent()

        try {
            vm.onDraftChange("hello server")
            vm.send()
            testScheduler.runCurrent()
            assertEquals(1, vm.messages.value.size) // optimistic PENDING row on screen
            assertEquals(MessageStatus.PENDING, vm.messages.value[0].status)

            // Mid-turn: the server already holds the user message; the pending
            // poll (15 s cadence) refetches and the snapshot carries the copy.
            repo.snapshot = listOf(msg("chatmsg:u1", MessageRole.USER, "hello server"))
            testScheduler.advanceTimeBy(15_000)
            testScheduler.runCurrent()

            // The outgoing message must render exactly once.
            assertEquals(1, vm.messages.value.count { it.role == MessageRole.USER })
            assertEquals("hello server", vm.messages.value.single { it.role == MessageRole.USER }.content)

            // Turn settles: the POST returns, the refresh tick dedupes by id.
            turnSettled.complete(Unit)
            testScheduler.runCurrent()
            val rendered = vm.messages.value
            assertEquals(2, rendered.size)
            assertTrue(rendered.all { it.status != MessageStatus.PENDING })
            assertTrue(ChatSendState.Idle == vm.sendState.value)
        } finally {
            // ALWAYS drain: on failure the re-arming poll delay (and the
            // suspended POST await) would otherwise spin runTest's virtual
            // clock forever — a hang instead of a red result.
            turnSettled.complete(Unit)
            repo.thread = generalThread() // pending_reply clears → loop exits
            testScheduler.advanceTimeBy(30_000)
            testScheduler.runCurrent()
            collector.cancel()
        }
    }

    @Test
    fun `send failure removes optimistic row and restores draft for retry`() = runTest {
        repo = ColdChatRepository(snapshot = emptyList())
        repo.sendOutcome = { _, _ -> throw RuntimeException("connection refused") }
        val vm = ChatThreadViewModel(repo, StaticTopics, StaticWorkspaces, StaticNotes,"chat_1", { "req-x" }, { t0 })
        val collector = launch(UnconfinedTestDispatcher()) {
            vm.messages.collect { }
        }

        vm.onDraftChange("will fail")
        vm.send()

        assertTrue(vm.messages.value.isEmpty()) // optimistic row withdrawn
        val failed = vm.sendState.value
        assertTrue(failed is ChatSendState.Failed)
        assertEquals("will fail", (failed as ChatSendState.Failed).text)
        assertEquals("will fail", vm.draft.value)
        collector.cancel()
    }

    @Test
    fun `retry of same text reuses the request id, changed text gets fresh`() = runTest {
        repo = ColdChatRepository(snapshot = emptyList())
        var attempt = 0
        repo.sendOutcome = { _, text ->
            attempt++
            if (attempt == 1) throw RuntimeException("timeout after landing")
            val user = msg("chatmsg:u1", MessageRole.USER, text)
            repo.snapshot = listOf(user)
            ChatExchange(user, null)
        }
        var counter = 0
        val vm = ChatThreadViewModel(repo, StaticTopics, StaticWorkspaces, StaticNotes,"chat_1", { "req-${counter++}" }, { t0 })
        val collector = launch(UnconfinedTestDispatcher()) {
            vm.messages.collect { }
        }

        vm.onDraftChange("same text")
        vm.send() // fails (but "landed" server-side)
        vm.send() // retry — same text, reuses the key; succeeds (draft cleared after)
        vm.onDraftChange("other text")
        vm.send() // different logical send — fresh key

        assertEquals(listOf("req-0", "req-0", "req-1"), repo.sentRequestIds)
        collector.cancel()
    }

    @Test
    fun `blank send is a no-op`() = runTest {
        repo = ColdChatRepository(snapshot = emptyList())
        val vm = ChatThreadViewModel(repo, StaticTopics, StaticWorkspaces, StaticNotes,"chat_1", { "req" }, { t0 })
        vm.onDraftChange("   ")
        vm.send()
        assertTrue(repo.sentRequestIds.isEmpty())
        assertTrue(vm.messages.value.isEmpty())
    }

    // ── T-013 history operations (truncate composition) ────────────────

    private fun seededConversation() = listOf(
        msg("u1", MessageRole.USER, "first question"),
        msg("a1", MessageRole.ASSISTANT, "first answer"),
        msg("u2", MessageRole.USER, "second question"),
        msg("a2", MessageRole.ASSISTANT, "second answer"),
    )

    @Test
    fun `revert keeps the prefix through the anchor and drops the rest`() = runTest {
        repo = ColdChatRepository(snapshot = seededConversation())
        val vm = ChatThreadViewModel(repo, StaticTopics, StaticWorkspaces, StaticNotes,"chat_1", { "req-t" }, { t0 })
        val collector = launch(UnconfinedTestDispatcher()) { vm.messages.collect { } }

        vm.revertTo(msg("u2", MessageRole.USER, "second question"))

        assertEquals(listOf("u2" to "req-t"), repo.truncations.map { it.first to it.second })
        assertEquals(listOf("u1", "a1", "u2"), vm.messages.value.map { it.id })
        assertTrue(ChatSendState.Idle == vm.sendState.value)
        collector.cancel()
    }

    @Test
    fun `revert failure surfaces a notice and leaves history intact`() = runTest {
        repo = ColdChatRepository(snapshot = seededConversation())
        repo.truncateOutcome = { throw RuntimeException("server said no") }
        val vm = ChatThreadViewModel(repo, StaticTopics, StaticWorkspaces, StaticNotes,"chat_1", { "req-t" }, { t0 })
        val collector = launch(UnconfinedTestDispatcher()) { vm.messages.collect { } }

        vm.revertTo(msg("u1", MessageRole.USER, "first question"))

        assertTrue(vm.notice.value?.contains("Revert failed") == true)
        assertEquals(4, vm.messages.value.size)
        collector.cancel()
    }

    @Test
    fun `edit truncates before the target and sends the rewritten text`() = runTest {
        repo = ColdChatRepository(snapshot = seededConversation())
        repo.sendOutcome = { _, text ->
            val user = msg("u3", MessageRole.USER, text)
            val assistant = msg("a3", MessageRole.ASSISTANT, "reply to $text")
            repo.snapshot = repo.snapshot + listOf(user, assistant)
            ChatExchange(user, assistant)
        }
        val vm = ChatThreadViewModel(repo, StaticTopics, StaticWorkspaces, StaticNotes,"chat_1", { "req-e" }, { t0 })
        val collector = launch(UnconfinedTestDispatcher()) { vm.messages.collect { } }

        vm.beginEdit(msg("u2", MessageRole.USER, "second question"))
        assertTrue(vm.composerMode.value is ChatComposerMode.EditFrom)
        assertEquals("second question", vm.draft.value)

        vm.onDraftChange("rephrased question")
        vm.send()

        // Truncated everything after a1, then sent the rewrite.
        assertEquals(listOf("a1"), repo.truncations.map { it.first })
        assertEquals(1, repo.sentRequestIds.size)
        val rendered = vm.messages.value
        assertEquals(listOf("u1", "a1", "u3", "a3"), rendered.map { it.id })
        assertEquals("rephrased question", rendered[2].content)
        assertTrue(vm.composerMode.value is ChatComposerMode.Plain)
        assertTrue(ChatSendState.Idle == vm.sendState.value)
        collector.cancel()
    }

    @Test
    fun `edit failure restores the draft and does not send`() = runTest {
        repo = ColdChatRepository(snapshot = seededConversation())
        repo.truncateOutcome = { throw RuntimeException("offline") }
        val vm = ChatThreadViewModel(repo, StaticTopics, StaticWorkspaces, StaticNotes,"chat_1", { "req-e" }, { t0 })
        val collector = launch(UnconfinedTestDispatcher()) { vm.messages.collect { } }

        vm.beginEdit(msg("u2", MessageRole.USER, "second question"))
        vm.onDraftChange("never lands")
        vm.send()

        assertTrue(repo.sentRequestIds.isEmpty())
        assertEquals("never lands", vm.draft.value)
        assertTrue(vm.notice.value?.contains("Edit failed") == true)
        assertTrue(ChatSendState.Idle == vm.sendState.value)
        collector.cancel()
    }

    @Test
    fun `regenerate drops the trailing pair and resends the same user text`() = runTest {
        repo = ColdChatRepository(snapshot = seededConversation())
        val sent = mutableListOf<String>()
        repo.sendOutcome = { _, text ->
            sent.add(text)
            val user = msg("u4", MessageRole.USER, text)
            val assistant = msg("a4", MessageRole.ASSISTANT, "better answer")
            repo.snapshot = listOf(
                msg("u1", MessageRole.USER, "first question"),
                msg("a1", MessageRole.ASSISTANT, "first answer"),
                user,
                assistant,
            )
            ChatExchange(user, assistant)
        }
        val vm = ChatThreadViewModel(repo, StaticTopics, StaticWorkspaces, StaticNotes,"chat_1", { "req-r" }, { t0 })
        val collector = launch(UnconfinedTestDispatcher()) { vm.messages.collect { } }

        vm.regenerate()

        // Kept through a1 (message before the trailing pair), resent u2's text.
        assertEquals(listOf("a1"), repo.truncations.map { it.first })
        assertEquals(listOf("second question"), sent)
        assertEquals(listOf("u1", "a1", "u4", "a4"), vm.messages.value.map { it.id })
        assertTrue(ChatSendState.Idle == vm.sendState.value)
        collector.cancel()
    }

    // ── T-022d chat scopes ──────────────────────────────────────────────

    private fun generalThread() = ChatThread(
        id = "chat_1", title = "General",
        createdAt = t0, updatedAt = t0,
    )

    @Test
    fun `send surfaces the proposed topic and Move applies it via setScope`() = runTest {
        val topic = ChatTopic(id = "evershift", label = "Evershift")
        repo = ColdChatRepository(
            snapshot = emptyList(),
            thread = generalThread(),
        )
        repo.sendOutcome = { _, text ->
            val user = msg("u1", MessageRole.USER, text)
            val assistant = msg("a1", MessageRole.ASSISTANT, "reply")
            repo.snapshot = listOf(user, assistant)
            ChatExchange(user, assistant, proposedTopic = topic)
        }
        val vm = ChatThreadViewModel(repo, StaticTopics, StaticWorkspaces, StaticNotes,"chat_1", { "req-p" }, { t0 })
        val collector = launch(UnconfinedTestDispatcher()) { vm.messages.collect { } }

        vm.onDraftChange("about greedy meshing")
        vm.send()

        // Suggestion surfaced, but the thread stays unscoped — never auto-applied.
        assertEquals(topic, vm.proposedTopic.value)
        assertEquals(null, vm.thread.value?.scopeType)

        vm.applyProposal()

        assertEquals(listOf(Triple("topic", "evershift", "req-p")), repo.scopeCalls)
        assertNull(vm.proposedTopic.value) // chip gone after applying
        collector.cancel()
    }

    @Test
    fun `Not now dismisses the proposal without any server call`() = runTest {
        repo = ColdChatRepository(snapshot = emptyList(), thread = generalThread())
        repo.sendOutcome = { _, text ->
            val user = msg("u1", MessageRole.USER, text)
            repo.snapshot = listOf(user)
            ChatExchange(user, null, proposedTopic = ChatTopic("evershift", "Evershift"))
        }
        val vm = ChatThreadViewModel(repo, StaticTopics, StaticWorkspaces, StaticNotes,"chat_1", { "req-d" }, { t0 })
        val collector = launch(UnconfinedTestDispatcher()) { vm.messages.collect { } }

        vm.onDraftChange("hello")
        vm.send()
        vm.dismissProposal()

        assertNull(vm.proposedTopic.value)
        assertTrue(repo.scopeCalls.isEmpty())
        collector.cancel()
    }

    @Test
    fun `a fresh send without a proposal clears any stale chip`() = runTest {
        repo = ColdChatRepository(snapshot = emptyList(), thread = generalThread())
        var first = true
        repo.sendOutcome = { _, text ->
            val user = msg("u${repo.snapshot.size + 1}", MessageRole.USER, text)
            val exchange = if (first) {
                first = false
                ChatExchange(user, null, proposedTopic = ChatTopic("evershift", "Evershift"))
            } else {
                ChatExchange(user, null)
            }
            repo.snapshot = repo.snapshot + user
            exchange
        }
        val vm = ChatThreadViewModel(repo, StaticTopics, StaticWorkspaces, StaticNotes,"chat_1", { "req-c" }, { t0 })
        val collector = launch(UnconfinedTestDispatcher()) { vm.messages.collect { } }

        vm.onDraftChange("first")
        vm.send()
        assertTrue(vm.proposedTopic.value != null)
        vm.onDraftChange("second")
        vm.send()

        assertNull(vm.proposedTopic.value) // no proposal on second send → chip cleared
        collector.cancel()
    }

    @Test
    fun `pending workspace reply re-polls until the flag clears`() = runTest {
        // Share runTest's scheduler with Main so delay() is virtual-time driven.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val pending = generalThread().copy(pendingReply = true)
        repo = ColdChatRepository(snapshot = emptyList(), thread = pending)

        ChatThreadViewModel(repo, StaticTopics, StaticWorkspaces, StaticNotes,"chat_1", { "req-w" }, { t0 })
        // init's poll collector keeps `thread` hot — no external collector needed.

        testScheduler.runCurrent()
        assertEquals(1, repo.threadFetches) // initial fetch sees pendingReply

        testScheduler.advanceTimeBy(15_000)
        testScheduler.runCurrent()
        assertEquals(2, repo.threadFetches) // poll fired, still pending → reschedules

        repo.thread = pending.copy(pendingReply = false) // turn settled server-side
        testScheduler.advanceTimeBy(15_000)
        testScheduler.runCurrent()
        assertEquals(3, repo.threadFetches) // one more poll observes the clear…

        testScheduler.advanceTimeBy(60_000)
        testScheduler.runCurrent()
        assertEquals(3, repo.threadFetches) // …and polling stops for good
    }

    @Test
    fun `settled threads never poll`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repo = ColdChatRepository(snapshot = emptyList(), thread = generalThread())
        ChatThreadViewModel(repo, StaticTopics, StaticWorkspaces, StaticNotes,"chat_1", { "req-s" }, { t0 })
        testScheduler.runCurrent()
        val afterSubscribe = repo.threadFetches

        testScheduler.advanceTimeBy(120_000)
        testScheduler.runCurrent()

        assertEquals(afterSubscribe, repo.threadFetches) // no ticks for a settled thread
    }

    @Test
    fun `saveNote snapshots the reply with chat provenance and never user messages`() = runTest {
        repo = ColdChatRepository(snapshot = emptyList(), thread = generalThread())
        val notes = ColdNoteRepository()
        val reply = msg("a1", MessageRole.ASSISTANT, "Compare X and Y — Y wins on latency.")
        val vm = ChatThreadViewModel(repo, StaticTopics, StaticWorkspaces, notes, "chat_1", { "req-n" }, { t0 })

        vm.saveNote(reply)
        vm.saveNote(msg("u1", MessageRole.USER, "user text must not be saved"))

        assertEquals(1, notes.createdDrafts.size) // user message ignored
        val draft = notes.createdDrafts[0]
        assertEquals("Compare X and Y — Y wins on latency.", draft.text)
        assertEquals(EntityKind.CHAT, draft.sourceType)
        assertEquals("chat_1", draft.sourceId)
        assertTrue(vm.notice.value!!.startsWith("Note saved"))
    }

    @Test
    fun `saveNote failure surfaces a notice without touching history`() = runTest {
        repo = ColdChatRepository(snapshot = emptyList(), thread = generalThread())
        val notes = ColdNoteRepository()
        notes.createOutcome = { throw RuntimeException("server unreachable") }
        val vm = ChatThreadViewModel(repo, StaticTopics, StaticWorkspaces, notes, "chat_1", { "req-n" }, { t0 })

        vm.saveNote(msg("a1", MessageRole.ASSISTANT, "some reply"))

        assertTrue(vm.notice.value!!.startsWith("Note save failed"))
    }

    // ── T-051: honest load state (Loading/Offline vs false "No messages") ──

    @Test
    fun `loaded is false while the messages fetch is in flight`() = runTest {
        val gate = CompletableDeferred<Unit>()
        repo = ColdChatRepository(snapshot = emptyList())
        repo.beforeMessagesEmit = { gate.await() } // cold dial: nothing landed yet
        val vm = ChatThreadViewModel(repo, StaticTopics, StaticWorkspaces, StaticNotes, "chat_1", { "req-l" }, { t0 })
        val collector = launch(UnconfinedTestDispatcher()) { vm.loaded.collect { } }

        // Loading — the transcript must never claim a fetched history yet.
        assertFalse(vm.loaded.value)
        collector.cancel()
    }

    @Test
    fun `loaded flips true once the messages fetch lands`() = runTest {
        repo = ColdChatRepository(snapshot = seededConversation())
        val vm = ChatThreadViewModel(repo, StaticTopics, StaticWorkspaces, StaticNotes, "chat_1", { "req-l" }, { t0 })
        val collector = launch(UnconfinedTestDispatcher()) { vm.loaded.collect { } }

        assertTrue(vm.loaded.value)
        assertEquals(1, repo.messagesFetches)
        collector.cancel()
    }

    @Test
    fun `loaded stays true while a post-send refresh refetch is in flight`() = runTest {
        repo = ColdChatRepository(snapshot = seededConversation())
        repo.sendOutcome = { _, text ->
            val user = msg("u3", MessageRole.USER, text)
            val assistant = msg("a3", MessageRole.ASSISTANT, "reply to $text")
            repo.snapshot = repo.snapshot + listOf(user, assistant)
            ChatExchange(user, assistant)
        }
        val gate = CompletableDeferred<Unit>()
        repo.beforeMessagesEmit = { if (repo.messagesFetches >= 2) gate.await() } // refetch hangs
        val vm = ChatThreadViewModel(repo, StaticTopics, StaticWorkspaces, StaticNotes, "chat_1", { "req-s" }, { t0 })
        val collector = launch(UnconfinedTestDispatcher()) { vm.loaded.collect { } }
        assertTrue(vm.loaded.value) // first fetch landed

        vm.onDraftChange("hello again")
        vm.send() // ack raises the refresh tick → refetch starts, gated in flight

        // Sticky: the in-flight refetch must not flash Loading over the
        // existing conversation (plan T-051, Calendar's sticky-loaded).
        assertTrue(vm.loaded.value)
        assertEquals(2, repo.messagesFetches)

        gate.complete(Unit)
        assertTrue(vm.loaded.value) // refetch landed — still true, no flicker
        collector.cancel()
    }

    @Test
    fun `loaded stays false when the first messages fetch fails offline`() = runTest {
        // Fakes bypass degradeTransport: the observe call itself throws, as
        // remote auth/protocol failures do — the VM must swallow it in the
        // loaded chain rather than crash the sharing coroutine (D037:
        // transport honesty is TransportStatus's job, screen-level).
        repo = ColdChatRepository(snapshot = seededConversation())
        repo.beforeMessagesEmit = { throw OfflineException(RuntimeException("no route")) }
        val vm = ChatThreadViewModel(repo, StaticTopics, StaticWorkspaces, StaticNotes, "chat_1", { "req-f" }, { t0 })
        val collector = launch(UnconfinedTestDispatcher()) { vm.loaded.collect { } }

        // Offline first open: Loading/Offline, never a false "No messages".
        assertFalse(vm.loaded.value)
        collector.cancel()
    }
}

/** Unsaved new chat: create-on-first-send and the hand-off to the thread. */
@OptIn(ExperimentalCoroutinesApi::class)
class NewChatViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun threadOf(id: String, draft: ChatThreadDraft) = ChatThread(
        id = id, title = draft.title,
        createdAt = Instant.parse("2026-08-23T12:00:00Z"),
        updatedAt = Instant.parse("2026-08-23T12:00:00Z"),
    )

    @Test
    fun `new chat creates nothing until the first send`() = runTest {
        var createdCount = 0
        val repo = object : ChatRepository by ColdChatRepository(emptyList()) {
            override suspend fun createThread(draft: ChatThreadDraft, requestId: RequestId): ChatThread {
                createdCount++
                return threadOf("chat_9", draft)
            }
        }
        val pending = PendingFirstMessages()
        val vm = NewChatViewModel(repo, pending, null, null) { "req-1" }

        vm.onDraftChange("   ")
        vm.send() // blank — ignored
        assertEquals(0, createdCount)
        assertNull(vm.created.value)

        vm.onDraftChange("hello")
        assertEquals(0, createdCount) // typing alone never creates
        vm.send()

        assertEquals(1, createdCount)
        assertEquals("chat_9", vm.created.value)
        assertEquals("hello", pending.take("chat_9"))
        assertNull(pending.take("chat_9")) // handed over exactly once
        assertEquals("", vm.draft.value)
        vm.consumeCreated()
        assertNull(vm.created.value)
    }

    @Test
    fun `new chat carries the picked scope into the create draft`() = runTest {
        val drafts = mutableListOf<ChatThreadDraft>()
        val repo = object : ChatRepository by ColdChatRepository(emptyList()) {
            override suspend fun createThread(draft: ChatThreadDraft, requestId: RequestId): ChatThread {
                drafts.add(draft)
                return threadOf("chat_10", draft)
            }
        }
        NewChatViewModel(repo, PendingFirstMessages(), "workspace", "roblox-toolkit") { "r" }
            .apply { onDraftChange("a"); send() }
        NewChatViewModel(repo, PendingFirstMessages(), "topic", "evershift") { "r" }
            .apply { onDraftChange("b"); send() }
        NewChatViewModel(repo, PendingFirstMessages(), null, null) { "r" }
            .apply { onDraftChange("c"); send() }

        assertEquals(3, drafts.size)
        assertEquals("workspace", drafts[0].scopeType)
        assertEquals("roblox-toolkit", drafts[0].scopeRef)
        assertEquals("topic", drafts[1].scopeType)
        assertEquals("evershift", drafts[1].scopeRef)
        assertNull(drafts[2].scopeType) // General — no scope fields on the wire
        assertNull(drafts[2].scopeRef)
    }

    @Test
    fun `create failure keeps the draft and retry reuses the same request id`() = runTest {
        val ids = mutableListOf<RequestId>()
        var fail = true
        val repo = object : ChatRepository by ColdChatRepository(emptyList()) {
            override suspend fun createThread(draft: ChatThreadDraft, requestId: RequestId): ChatThread {
                ids.add(requestId)
                if (fail) throw RuntimeException("connection refused")
                return threadOf("chat_11", draft)
            }
        }
        var n = 0
        val pending = PendingFirstMessages()
        val vm = NewChatViewModel(repo, pending, null, null) { "req-${n++}" }

        vm.onDraftChange("keep me")
        vm.send()

        assertTrue(vm.state.value is NewChatState.Failed)
        assertEquals("keep me", vm.draft.value)
        assertNull(vm.created.value)
        assertNull(pending.take("chat_11"))

        fail = false
        vm.send()

        assertEquals(listOf("req-0", "req-0"), ids) // idempotent create across retries
        assertEquals("chat_11", vm.created.value)
        assertEquals("keep me", pending.take("chat_11"))
    }
}
