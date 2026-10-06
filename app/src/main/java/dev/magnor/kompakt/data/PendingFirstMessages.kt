package dev.magnor.kompakt.data

import dev.magnor.kompakt.domain.EntityId
import java.util.concurrent.ConcurrentHashMap

/**
 * Hand-off from the new-chat draft screen to the thread screen: the thread
 * is created on first send, and the first message rides along here so the
 * thread opens already sending (optimistic row, normal retry/failure
 * handling) instead of making the user wait on a blank screen.
 * In-memory only — if the process dies between create and hand-off the
 * thread exists empty and the typed text is lost, same as any unsent draft.
 */
class PendingFirstMessages {
    private val byThread = ConcurrentHashMap<EntityId, String>()

    fun put(threadId: EntityId, text: String) {
        byThread[threadId] = text
    }

    /** Returns the stashed text once; later calls return null. */
    fun take(threadId: EntityId): String? = byThread.remove(threadId)
}
