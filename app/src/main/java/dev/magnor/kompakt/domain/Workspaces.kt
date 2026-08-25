package dev.magnor.kompakt.domain

import kotlinx.serialization.Serializable

/**
 * T-022c: a git checkout addressable by ref — the unit of "point an agent
 * at this repo". Server-side registry (config + autodiscovery, restart-only
 * refresh); the phone only ever sees `{ref, label}` (D023 — directories
 * never cross the wire).
 *
 * Reference data, not an entity: no id namespace, no revisions, no sync.
 */
@Serializable
data class Workspace(
    val ref: String,
    val label: String,
)
