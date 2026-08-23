package dev.magnor.kompakt.data

import dev.magnor.kompakt.domain.CaptureProposal
import dev.magnor.kompakt.domain.CaptureType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PendingCaptureStore (Phase 6 offline queue): enqueue dedupe, durable
 * file persistence across instances, count signal.
 */
class PendingCaptureStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun proposal(title: String) = CaptureProposal(
        proposedType = CaptureType.TASK,
        title = title,
    )

    @Test
    fun `enqueue all remove roundtrip in memory`() = runTest {
        val store = PendingCaptureStore(dir = null)
        store.enqueue(proposal("A"), "r1")
        store.enqueue(proposal("B"), "r2")
        assertEquals(2, store.count.first())
        assertEquals(listOf("r1", "r2"), store.all().map { it.requestId })

        store.remove("r1")
        assertEquals(listOf("r2"), store.all().map { it.requestId })
        assertEquals(1, store.count.first())
    }

    @Test
    fun `same request id replaces not duplicates`() = runTest {
        val store = PendingCaptureStore(dir = null)
        store.enqueue(proposal("old"), "r1")
        store.enqueue(proposal("new"), "r1")
        val all = store.all()
        assertEquals(1, all.size)
        assertEquals("new", all.single().proposal.title)
    }

    @Test
    fun `persists across instances`() = runTest {
        val dir = tmp.newFolder("queue")
        val first = PendingCaptureStore(dir)
        first.enqueue(proposal("durable"), "r9")

        val second = PendingCaptureStore(dir)
        val all = second.all()
        assertEquals(1, all.size)
        assertEquals("durable", all.single().proposal.title)
        assertEquals("r9", all.single().requestId)

        second.remove("r9")
        assertTrue(PendingCaptureStore(dir).all().isEmpty())
    }

    @Test
    fun `corrupt queue file degrades to empty`() = runTest {
        val dir = tmp.newFolder("queue2")
        dir.resolve("pending-captures.json").writeText("{not json")
        val store = PendingCaptureStore(dir)
        assertTrue(store.all().isEmpty())
        assertEquals(0, store.count.first())
    }
}
