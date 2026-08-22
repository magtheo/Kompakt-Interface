package dev.magnor.kompakt.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/** Identifier of a domain object as assigned by the server (e.g. "task_123"). */
typealias EntityId = String

/** Idempotency key for replayable mutations (docs/protocol-and-sync.md §15). */
typealias RequestId = String

/** Opaque cursor into the server change stream (e.g. "chg_009912"). */
typealias SyncCursorValue = String

/**
 * Central JSON configuration for all server wire formats.
 *
 * Protocol rules (docs/protocol-and-sync.md §9): the client must ignore
 * unknown optional fields and never crash on forward-compat payloads.
 */
val KompaktJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

/**
 * Enum serializer that maps an unknown wire value to the enum's UNKNOWN
 * entry instead of throwing. The server is updated weekly while the phone
 * is updated rarely — unknown enum values are the *live* scenario, not an
 * edge case (protocol §9: unknown values must never trigger arbitrary
 * behavior).
 *
 * Every server-driven enum declares `val wire: String`, an UNKNOWN entry,
 * and a companion `Serializer` built from this class. Enums are serialized
 * as their wire string.
 */
open class SafeEnumSerializer<T : Enum<T>>(
    private val unknownValue: T,
    private val all: List<T>,
    private val wireOf: (T) -> String,
) : KSerializer<T> {

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        all.first()::class.qualifiedName ?: "SafeEnum",
        PrimitiveKind.STRING,
    )

    override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeString(wireOf(value))
    }

    override fun deserialize(decoder: Decoder): T {
        val raw = decoder.decodeString()
        return all.firstOrNull { wireOf(it) == raw } ?: unknownValue
    }
}

/**
 * Fields every synchronizable object carries (protocol §10):
 * `id`, `revision`, `updated_at`. `revision` increases whenever the
 * canonical representation changes; mutations send `expected_revision`
 * and reconcile on 409 (protocol §13/§14).
 */
interface SyncEntity {
    val id: EntityId
    val revision: Long
    val updatedAt: Instant
}
