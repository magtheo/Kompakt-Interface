@file:UseSerializers(InstantIso8601Serializer::class)

package dev.magnor.kompakt.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.serializers.InstantIso8601Serializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable(with = TaskStatus.Serializer::class)
enum class TaskStatus(val wire: String) {
    OPEN("open"),
    COMPLETED("completed"),
    UNKNOWN("unknown");

    object Serializer : SafeEnumSerializer<TaskStatus>(UNKNOWN, entries, TaskStatus::wire)
}

@Serializable
data class Task(
    override val id: EntityId,
    val title: String,
    val status: TaskStatus = TaskStatus.OPEN,
    @SerialName("due_at")
    val dueAt: Instant? = null,
    @SerialName("project_id")
    val projectId: EntityId? = null,
    @SerialName("area_id")
    val areaId: EntityId? = null,
    /** Free-text detail attached to the task. */
    val notes: String? = null,
    /** Explicit cross-object link (e.g. created from an agent run / chat). */
    @SerialName("source_type")
    val sourceType: EntityKind? = null,
    @SerialName("source_id")
    val sourceId: EntityId? = null,
    override val revision: Long = 1,
    @SerialName("updated_at")
    override val updatedAt: Instant,
) : SyncEntity

/** New-task input. Writes route through the coordinator/vault rules (D0xx). */
@Serializable
data class TaskDraft(
    val title: String,
    @SerialName("due_at")
    val dueAt: Instant? = null,
    @SerialName("project_id")
    val projectId: EntityId? = null,
    @SerialName("area_id")
    val areaId: EntityId? = null,
    @SerialName("source_type")
    val sourceType: EntityKind? = null,
    @SerialName("source_id")
    val sourceId: EntityId? = null,
)

/** Partial update payload — PATCH semantics; null fields are left unchanged. */
@Serializable
data class TaskPatch(
    val title: String? = null,
    @SerialName("due_at")
    val dueAt: Instant? = null,
    val status: TaskStatus? = null,
    @SerialName("project_id")
    val projectId: EntityId? = null,
)

/** Client-side list filtering (not a wire object). */
sealed interface TaskFilter {
    data object All : TaskFilter
    data class Today(val at: Instant) : TaskFilter
    data class ByProject(val projectId: EntityId) : TaskFilter
    data class ByArea(val areaId: EntityId) : TaskFilter
}
