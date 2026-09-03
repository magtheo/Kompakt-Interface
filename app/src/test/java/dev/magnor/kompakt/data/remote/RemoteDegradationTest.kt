package dev.magnor.kompakt.data.remote

import dev.magnor.kompakt.domain.ChatThread
import dev.magnor.kompakt.domain.Task
import dev.magnor.kompakt.domain.TaskFilter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-045: transport failures on observe* reads degrade to the fallback
 * value instead of crashing the collecting coroutine (Sept 2 incident:
 * OfflineException from a one-shot fetch inside stateIn(viewModelScope)
 * killed the process and, with it, the a11y key service).
 */
class RemoteDegradationTest {

    /** Start-then-shutdown listener: connection refused → OfflineException. */
    private fun deadApi(): HttpApi {
        val deadServer = okhttp3.mockwebserver.MockWebServer()
        deadServer.start()
        val url = deadServer.url("/").toString()
        deadServer.shutdown()
        return HttpApi(baseUrl = url, token = "t")
    }

    @Test
    fun `offline observeTasks degrades to empty list`() = runTest {
        assertEquals(
            emptyList<Task>(),
            RemoteTaskRepository(deadApi()).observeTasks(TaskFilter.All).first(),
        )
    }

    @Test
    fun `offline observeThreads degrades to empty list`() = runTest {
        assertEquals(
            emptyList<ChatThread>(),
            RemoteChatRepository(deadApi()).observeThreads().first(),
        )
    }

    @Test
    fun `offline observeRun degrades through the map chain to null`() = runTest {
        assertEquals(
            null,
            RemoteAgentRepository(deadApi()).observeRun("run-1").first(),
        )
    }
}
