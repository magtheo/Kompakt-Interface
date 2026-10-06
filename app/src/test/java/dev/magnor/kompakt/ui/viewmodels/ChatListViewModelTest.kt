package dev.magnor.kompakt.ui.viewmodels

import dev.magnor.kompakt.data.repository.ChatRepository
import dev.magnor.kompakt.data.repository.TopicRepository
import dev.magnor.kompakt.data.repository.WorkspaceRepository
import dev.magnor.kompakt.domain.ChatExchange
import dev.magnor.kompakt.domain.ChatThread
import dev.magnor.kompakt.domain.ChatThreadDraft
import dev.magnor.kompakt.domain.ChatTopic
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.Message
import dev.magnor.kompakt.domain.RequestId
import dev.magnor.kompakt.domain.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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

/**
 * Cold one-shot fakes mirroring the remote observe semantics: a `null` flow
 * is a fetch still in flight (nothing emitted — tunnel dial), an emitted
 * list is the landed snapshot. This is what makes the combined UiState's
 * `loaded` flag observable in a JVM test.
 */
private class ColdChatListRepository(
    private val threads: Flow<List<ChatThread>>? = null,
    var createOutcome: (ChatThreadDraft) -> ChatThread = { _ -> throw UnsupportedOperationException() },
) : ChatRepository {
    override fun observeThreads(): Flow<List<ChatThread>> = threads ?: emptyFlow()
    override fun observeThread(id: EntityId) = throw UnsupportedOperationException()
    override fun observeMessages(chatId: EntityId): Flow<List<Message>> = throw UnsupportedOperationException()
    override suspend fun getThread(id: EntityId) = throw UnsupportedOperationException()
    override suspend fun createThread(draft: ChatThreadDraft, requestId: RequestId): ChatThread =
        createOutcome(draft)
    override suspend fun sendMessage(chatId: EntityId, text: String, requestId: RequestId): ChatExchange =
        throw UnsupportedOperationException()
    override suspend fun truncate(chatId: EntityId, keepThrough: EntityId?, requestId: RequestId) =
        throw UnsupportedOperationException()

    override suspend fun setScope(
        chatId: EntityId,
        scopeType: String?,
        scopeRef: String?,
        requestId: RequestId,
    ): ChatThread = throw UnsupportedOperationException()
}

private class ColdTopics(private val topics: Flow<List<ChatTopic>>? = null) : TopicRepository {
    override fun observeTopics(): Flow<List<ChatTopic>> = topics ?: emptyFlow()
}

private class ColdWorkspaces(private val workspaces: Flow<List<Workspace>>? = null) : WorkspaceRepository {
    override fun observeWorkspaces(): Flow<List<Workspace>> = workspaces ?: emptyFlow()
}

/**
 * T-051: the chat list exposes ONE combined UiState (T-024 pattern) over the
 * three cold one-shot observe fetches. `loaded` flips only once every fetch
 * has landed, so the screen can tell Loading from a genuinely empty list and
 * never renders a false "No chats yet" mid-fetch. Fakes bypass
 * `degradeTransport`, so the offline path is asserted at screen level
 * (Task 4), not here — these tests cover the genuine-empty path instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatListViewModelTest {

    private val t0 = Instant.parse("2026-08-23T12:00:00Z")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun thread(id: String) =
        ChatThread(id = id, title = "Chat $id", createdAt = t0, updatedAt = t0)

    @Test
    fun `state starts unloaded with empty lists while fetches are in flight`() = runTest {
        val vm = ChatListViewModel(ColdChatListRepository(), ColdTopics(), ColdWorkspaces(), t0)
        val collector = launch(UnconfinedTestDispatcher()) { vm.state.collect { } }

        val initial = vm.state.value
        assertFalse(initial.loaded)
        assertTrue(initial.threads.isEmpty())
        assertTrue(initial.topics.isEmpty())
        assertTrue(initial.workspaces.isEmpty())
        collector.cancel()
    }

    @Test
    fun `loaded stays false until every source has emitted`() = runTest {
        val vm = ChatListViewModel(
            ColdChatListRepository(flowOf(listOf(thread("chat_1")))), // threads landed…
            ColdTopics(), // …topics still in flight — combine must wait
            ColdWorkspaces(flowOf(listOf(Workspace(ref = "roblox-toolkit", label = "Roblox Toolkit")))),
            t0,
        )
        val collector = launch(UnconfinedTestDispatcher()) { vm.state.collect { } }

        assertFalse(vm.state.value.loaded)
        collector.cancel()
    }

    @Test
    fun `loaded flips true with all data once the three fetches land`() = runTest {
        val expected = listOf(thread("chat_1"), thread("chat_2"))
        val vm = ChatListViewModel(
            ColdChatListRepository(flowOf(expected)),
            ColdTopics(flowOf(listOf(ChatTopic(id = "evershift", label = "Evershift")))),
            ColdWorkspaces(flowOf(listOf(Workspace(ref = "roblox-toolkit", label = "Roblox Toolkit")))),
            t0,
        )
        val collector = launch(UnconfinedTestDispatcher()) { vm.state.collect { } }

        val state = vm.state.value
        assertTrue(state.loaded)
        assertEquals(expected, state.threads)
        assertEquals(listOf("evershift"), state.topics.map { it.id })
        assertEquals(listOf("roblox-toolkit"), state.workspaces.map { it.ref })
        collector.cancel()
    }

    @Test
    fun `an emitted empty thread list is loaded and genuinely empty`() = runTest {
        val vm = ChatListViewModel(
            ColdChatListRepository(flowOf(emptyList())), // fetch landed: honest empty
            ColdTopics(flowOf(listOf(ChatTopic(id = "evershift", label = "Evershift")))),
            ColdWorkspaces(flowOf(listOf(Workspace(ref = "roblox-toolkit", label = "Roblox Toolkit")))),
            t0,
        )
        val collector = launch(UnconfinedTestDispatcher()) { vm.state.collect { } }

        val state = vm.state.value
        assertTrue(state.loaded)
        assertTrue(state.threads.isEmpty())
        collector.cancel()
    }

}
