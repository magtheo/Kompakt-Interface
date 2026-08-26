package dev.magnor.kompakt.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * kotlinx-datetime 0.7 removed `kotlinx.datetime.serializers.
 * InstantIso8601Serializer` (Instant became a java.time typealias on JVM).
 * Local drop-in with the identical wire format — ISO-8601 instant string,
 * e.g. "2026-08-26T19:04:00Z" — so existing @file:UseSerializers annotations
 * keep working unqualified inside this package (T-023 datetime upgrade).
 */
object InstantIso8601Serializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.magnor.kompakt.domain.InstantIso8601", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant =
        Instant.parse(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }
}
