package dev.magnor.kompakt.data

import dev.magnor.kompakt.domain.CaptureProposal
import dev.magnor.kompakt.domain.KompaktJson
import dev.magnor.kompakt.domain.RequestId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Durable offline capture queue (Phase 6, dev plan §8: "task/note capture
 * should support queued offline operation").
 *
 * A commit that fails while offline is parked here and re-sent with the
 * SAME request id when connectivity returns — the server replays
 * idempotently, so a double flush can never create duplicates.
 *
 * One source of truth at a time: a JSON file (atomically replaced via
 * tmp+rename) when a dir is given, an in-memory list otherwise (fake
 * mode / unit tests).
 */
class PendingCaptureStore(private val dir: File?) {

    @Serializable
    data class Pending(
        @SerialName("request_id") val requestId: RequestId,
        val proposal: CaptureProposal,
        @SerialName("queued_at") val queuedAt: String,
    )

    private val memory: MutableList<Pending> = mutableListOf()
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    init {
        _count.value = all().size
    }

    @Synchronized
    fun enqueue(proposal: CaptureProposal, requestId: RequestId, now: Instant = Clock.System.now()) {
        val items = all().filterNot { it.requestId == requestId } +
            Pending(requestId, proposal, now.toString())
        replaceWith(items)
    }

    @Synchronized
    fun all(): List<Pending> =
        if (dir == null) memory.toList() else readFile()

    @Synchronized
    fun remove(requestId: RequestId) {
        replaceWith(all().filterNot { it.requestId == requestId })
    }

    private fun replaceWith(items: List<Pending>) {
        if (dir == null) {
            memory.clear()
            memory.addAll(items)
        } else {
            writeFile(items)
        }
        _count.value = items.size
    }

    private fun readFile(): List<Pending> {
        val file = File(dir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return runCatching {
            KompaktJson.decodeFromString<List<Pending>>(file.readText())
        }.getOrDefault(emptyList())
    }

    private fun writeFile(items: List<Pending>) {
        val dir = dir ?: return
        dir.mkdirs()
        val file = File(dir, FILE_NAME)
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(KompaktJson.encodeToString(items))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private companion object {
        const val FILE_NAME = "pending-captures.json"
    }
}
