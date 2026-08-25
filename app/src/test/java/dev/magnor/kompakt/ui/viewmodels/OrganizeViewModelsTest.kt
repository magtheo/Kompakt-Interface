package dev.magnor.kompakt.ui.viewmodels

import dev.magnor.kompakt.data.repository.ChatRepository
import dev.magnor.kompakt.data.repository.NoteRepository
import dev.magnor.kompakt.data.repository.OrganizationRepository
import dev.magnor.kompakt.domain.Area
import dev.magnor.kompakt.domain.ChatThread
import dev.magnor.kompakt.domain.ChatThreadDraft
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.Note
import dev.magnor.kompakt.domain.Project
import dev.magnor.kompakt.domain.RequestId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-022e: the project context surface — workspace-chat join (client-side,
 * over existing thread lists) + project-filtered notes (repo query, the
 * V-064 `GET /v1/notes?project_id=` path). Cold one-shot fakes mirror the
 * remote repo semantics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectDetailViewModelTest {

    private val t0 = Instant.parse("2026-08-25T12:00:00Z")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class TestOrgRepository(private val projects: List<Project>) : OrganizationRepository {
        override fun observeProjects(): Flow<List<Project>> = flow { emit(projects) }
        override fun observeProject(id: EntityId): Flow<Project?> =
            flow { emit(projects.firstOrNull { it.id == id }) }
        override fun observeAreas(): Flow<List<Area>> = flow { emit(emptyList()) }
        override fun observeArea(id: EntityId): Flow<Area?> = flow { emit(null) }
    }

    /** Cold one-shot; records the requested filter (the wire's query param). */
    private class TestNoteRepository(private val notes: List<Note>) : NoteRepository {
        var requestedProjectId: EntityId? = null
        override fun observeNotes(projectId: EntityId?, areaId: EntityId?): Flow<List<Note>> = flow {
            requestedProjectId = projectId
            emit(notes)
        }
        override fun observeNote(id: EntityId): Flow<Note?> = throw UnsupportedOperationException()
        override suspend fun getNote(id: EntityId) = throw UnsupportedOperationException()
        override suspend fun createNote(draft: dev.magnor.kompakt.domain.NoteDraft, requestId: RequestId) =
            throw UnsupportedOperationException()
        override suspend fun updateNote(id: EntityId, text: String, expectedChecksum: String) =
            throw UnsupportedOperationException()
    }

    private class TestChatRepository(private val threads: List<ChatThread>) : ChatRepository {
        override fun observeThreads(): Flow<List<ChatThread>> = flow { emit(threads) }
        override fun observeThread(id: EntityId) = throw UnsupportedOperationException()
        override fun observeMessages(chatId: EntityId) = throw UnsupportedOperationException()
        override suspend fun getThread(id: EntityId) = throw UnsupportedOperationException()
        override suspend fun createThread(draft: ChatThreadDraft, requestId: RequestId) =
            throw UnsupportedOperationException()
        override suspend fun sendMessage(chatId: EntityId, text: String, requestId: RequestId) =
            throw UnsupportedOperationException()
        override suspend fun truncate(chatId: EntityId, keepThrough: EntityId?, requestId: RequestId) =
            throw UnsupportedOperationException()
        override suspend fun setScope(chatId: EntityId, scopeType: String?, scopeRef: String?, requestId: RequestId) =
            throw UnsupportedOperationException()
    }

    private fun thread(id: String, scopeType: String? = null, scopeRef: String? = null) = ChatThread(
        id = id, title = "Chat $id", createdAt = t0, updatedAt = t0,
        scopeType = scopeType, scopeRef = scopeRef,
    )

    @Test
    fun `workspace ref maps vault and machine project id families`() {
        // vault ids are already slugs (server slugify) — pass through
        assertEquals("kodeverket", ProjectDetailViewModel.workspaceRefFor("vault:project:kodeverket"))
        // machine ids carry a repo name — client-side slug mirror
        assertEquals("dev-server", ProjectDetailViewModel.workspaceRefFor("machine:project:Dev Server"))
        // anything else has no workspace binding
        assertNull(ProjectDetailViewModel.workspaceRefFor("area:area_1"))
    }

    @Test
    fun `detail joins workspace chats and queries notes for the project`() = runTest {
        val project = Project(id = "machine:project:evershift", name = "Evershift", updatedAt = t0)
        val notes = TestNoteRepository(listOf(Note(id = "vault:note:1", title = "Mesh idea", updatedAt = t0)))
        val chats = TestChatRepository(
            listOf(
                thread("thread_1", scopeType = "workspace", scopeRef = "evershift"),
                thread("thread_2", scopeType = "workspace", scopeRef = "dev-server"), // other project
                thread("thread_3", scopeType = "topic", scopeRef = "evershift"), // wrong family
                thread("thread_4"), // general chat
            ),
        )
        val vm = ProjectDetailViewModel(TestOrgRepository(listOf(project)), notes, chats, project.id)
        val collector = launch(UnconfinedTestDispatcher()) { vm.state.collect { } }

        assertTrue(vm.state.value.loaded)
        assertEquals(project.id, vm.state.value.project?.id)
        assertEquals(project.id, notes.requestedProjectId) // notes arrive pre-filtered by query
        assertEquals(listOf("thread_1"), vm.state.value.chats.map { it.id })
        assertEquals(listOf("vault:note:1"), vm.state.value.notes.map { it.id })
        collector.cancel()
    }

    @Test
    fun `vault project joins chats by its slug`() = runTest {
        val project = Project(id = "vault:project:kodeverket", name = "KodeVerket", updatedAt = t0)
        val notes = TestNoteRepository(emptyList())
        val chats = TestChatRepository(
            listOf(
                thread("thread_kv", scopeType = "workspace", scopeRef = "kodeverket"),
                thread("thread_ev", scopeType = "workspace", scopeRef = "evershift"),
            ),
        )
        val vm = ProjectDetailViewModel(TestOrgRepository(listOf(project)), notes, chats, project.id)
        val collector = launch(UnconfinedTestDispatcher()) { vm.state.collect { } }

        assertEquals(listOf("thread_kv"), vm.state.value.chats.map { it.id })
        collector.cancel()
    }

    @Test
    fun `unknown project id still loads with empty surface`() = runTest {
        val notes = TestNoteRepository(emptyList())
        val chats = TestChatRepository(
            listOf(thread("thread_1", scopeType = "workspace", scopeRef = "evershift")),
        )
        val vm = ProjectDetailViewModel(TestOrgRepository(emptyList()), notes, chats, "machine:project:ghost")
        val collector = launch(UnconfinedTestDispatcher()) { vm.state.collect { } }

        assertTrue(vm.state.value.loaded)
        assertNull(vm.state.value.project)
        assertTrue(vm.state.value.chats.isEmpty())
        assertTrue(vm.state.value.notes.isEmpty())
        collector.cancel()
    }
}
