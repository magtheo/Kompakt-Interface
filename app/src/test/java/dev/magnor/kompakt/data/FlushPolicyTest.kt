package dev.magnor.kompakt.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class FlushPolicyTest {

    private class Clock(startAt: Long = 0L) {
        private val t = AtomicLong(startAt)
        val now: () -> Long = { t.get() }
        fun advance(ms: Long) = t.addAndGet(ms)
    }

    @Test
    fun `empty queue never triggers`() {
        val clock = Clock()
        val policy = FlushPolicy(now = clock.now)
        assertEquals(false, policy.shouldAttempt(0))
        assertEquals(false, policy.shouldAttempt(0))
    }

    @Test
    fun `first event with parked capture triggers`() {
        val clock = Clock()
        val policy = FlushPolicy(now = clock.now)
        assert(policy.shouldAttempt(1))
    }

    @Test
    fun `burst of events is rate limited to one attempt`() {
        val clock = Clock()
        val policy = FlushPolicy(now = clock.now, minIntervalMs = 5_000)
        assert(policy.shouldAttempt(2))          // first wins
        clock.advance(100)                        // link flap
        assertEquals(false, policy.shouldAttempt(2))
        clock.advance(1_000)                      // still inside window
        assertEquals(false, policy.shouldAttempt(2))
    }

    @Test
    fun `attempt allowed again after interval elapses`() {
        val clock = Clock()
        val policy = FlushPolicy(now = clock.now, minIntervalMs = 5_000)
        assert(policy.shouldAttempt(1))
        clock.advance(5_000)
        assert(policy.shouldAttempt(1))
    }

    @Test
    fun `rejected attempt during rate window does not extend it`() {
        val clock = Clock()
        val policy = FlushPolicy(now = clock.now, minIntervalMs = 5_000)
        assert(policy.shouldAttempt(1))
        clock.advance(1_000)
        assertEquals(false, policy.shouldAttempt(1))  // suppressed, no re-arm
        clock.advance(4_000)                           // 5s since the real attempt
        assert(policy.shouldAttempt(1))
    }

    @Test
    fun `queue draining to empty is a no-op`() {
        val clock = Clock()
        val policy = FlushPolicy(now = clock.now)
        assert(policy.shouldAttempt(3))
        clock.advance(10_000)
        assertEquals(false, policy.shouldAttempt(0))
    }
}
