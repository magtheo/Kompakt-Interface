package dev.magnor.kompakt.data

import dev.magnor.kompakt.domain.KompaktJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * T-020 — cross-path alert dedupe, persisted.
 *
 * The live SSE service (T-019) and the periodic fallback worker both
 * deliver the same unread alerts; "seen" must therefore survive process
 * death, or the first worker run after a reboot re-notifies everything
 * still unread. Bounded LRU of ids (alert ids embed the run id, so they
 * never repeat across restarts — the bound only protects file size).
 *
 * Same store conventions as [PendingCaptureStore]: dir == null →
 * in-memory only (fake mode / unit tests); file writes are atomic
 * tmp+rename; all methods synchronized.
 */
class AlertSeenStore(private val dir: File?) {

    @Serializable
    private data class Seen(val ids: List<String>)

    private val memory = LinkedHashSet<String>()
    private var loaded = false
    private val capacity = 256

    @Synchronized
    fun firstSeen(id: String): Boolean {
        ensureLoaded()
        if (id in memory) return false
        remember(id)
        return true
    }

    @Synchronized
    fun markSeen(id: String) {
        ensureLoaded()
        remember(id)
    }

    @Synchronized
    fun seen(id: String): Boolean {
        ensureLoaded()
        return id in memory
    }

    @Synchronized
    fun clear() {
        memory.clear()
        loaded = true // stay authoritative, ignore any file
        dir?.let { File(it, FILE_NAME).delete() }
    }

    /** One file read on first use; memory is authoritative afterwards. */
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val file = dir?.let { File(it, FILE_NAME) } ?: return
        if (!file.exists()) return
        runCatching {
            KompaktJson.decodeFromString<Seen>(file.readText())
        }.getOrNull()?.let { memory.addAll(it.ids) }
    }

    private fun remember(id: String) {
        memory.add(id)
        while (memory.size > capacity) memory.remove(memory.first())
        val dir = dir ?: return
        dir.mkdirs()
        val file = File(dir, FILE_NAME)
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(KompaktJson.encodeToString(Seen(memory.toList())))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private companion object {
        const val FILE_NAME = "alert-seen.json"
    }
}
