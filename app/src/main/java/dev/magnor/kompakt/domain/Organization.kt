@file:UseSerializers(InstantIso8601Serializer::class)

package dev.magnor.kompakt.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.serializers.InstantIso8601Serializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * Projects/Areas mirror the vault PARA structure (D004) — the app invents
 * no hierarchy of its own. Read-only projection in v0.1.
 */
@Serializable(with = ProjectStatus.Serializer::class)
enum class ProjectStatus(val wire: String) {
    ACTIVE("active"),
    PAUSED("paused"),
    DONE("done"),
    UNKNOWN("unknown");

    object Serializer : SafeEnumSerializer<ProjectStatus>(UNKNOWN, entries, ProjectStatus::wire)
}

@Serializable
data class Project(
    override val id: EntityId,
    val name: String,
    val status: ProjectStatus = ProjectStatus.ACTIVE,
    val currentGoal: String? = null,
    val nextAction: String? = null,
    val attentionCount: Int = 0,
    override val revision: Long = 1,
    override val updatedAt: Instant,
) : SyncEntity

@Serializable
data class Area(
    override val id: EntityId,
    val name: String,
    val description: String = "",
    override val revision: Long = 1,
    override val updatedAt: Instant,
) : SyncEntity
