package dev.magnor.kompakt.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-044: the window state machine — up → server → burst → ALWAYS down.
 * Failure anywhere must still tear the tunnel down (battery rule:
 * nothing outlives its window).
 */
class SyncWindowOrchestratorTest {

    private class FakeTunnel {
        var upResult = true
        var upCalls = 0
        var downCalls = 0
        val burstCalls = mutableListOf<Int>()

        suspend fun up(): Boolean {
            upCalls++
            return upResult
        }

        suspend fun down() {
            downCalls++
        }
    }

    @Test
    fun `happy path syncs and tears down`() = runTest {
        val t = FakeTunnel()
        var bursts = 0
        val result = SyncWindowOrchestrator(
            tunnelUp = { t.up() },
            burst = { bursts++ },
            down = { t.down() },
        ).runWindow()
        assertEquals(SyncWindowOrchestrator.Result.Synced, result)
        assertEquals(1, t.upCalls)
        assertEquals(1, t.downCalls)
        assertEquals(1, bursts)
    }

    @Test
    fun `tunnel never up reports offline and still downs`() = runTest {
        val t = FakeTunnel().apply { upResult = false }
        var bursts = 0
        val result = SyncWindowOrchestrator(
            tunnelUp = { t.up() },
            burst = { bursts++ },
            down = { t.down() },
        ).runWindow()
        assertEquals(SyncWindowOrchestrator.Result.Offline, result)
        assertEquals(1, t.downCalls)
        assertEquals(0, bursts)
    }

    @Test
    fun `burst failure still tears tunnel down`() = runTest {
        val t = FakeTunnel()
        val result = SyncWindowOrchestrator(
            tunnelUp = { t.up() },
            burst = { throw RuntimeException("server 500 mid-window") },
            down = { t.down() },
        ).runWindow()
        assertEquals(SyncWindowOrchestrator.Result.Offline, result)
        assertEquals(1, t.downCalls)
    }

    @Test
    fun `tunnel-up throw reports offline without escaping`() = runTest {
        val t = FakeTunnel()
        var bursts = 0
        val result = SyncWindowOrchestrator(
            tunnelUp = { throw RuntimeException("vpn service dead") },
            burst = { bursts++ },
            down = { t.down() },
        ).runWindow()
        assertEquals(SyncWindowOrchestrator.Result.Offline, result)
        assertEquals(0, bursts)
    }
}
