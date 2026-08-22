package dev.magnor.kompakt.data.repository

import dev.magnor.kompakt.domain.TodayProjection
import kotlinx.coroutines.flow.Flow

/**
 * Today — the operational overview. A view, not a source of truth: the
 * projection combines entities owned by the other repositories.
 */
interface TodayRepository {
    fun observeToday(): Flow<TodayProjection>
    suspend fun today(): TodayProjection
}
