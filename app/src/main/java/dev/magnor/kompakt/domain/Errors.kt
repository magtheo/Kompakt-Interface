package dev.magnor.kompakt.domain

/**
 * Repository-level failures. These mirror the HTTP contract the real
 * repositories will map in Phase 3+ (protocol §13–§15), so ViewModels can
 * be written against them now.
 */

/** 409 analog: the server revision moved on from `expected_revision`. */
class RevisionConflictException(
    val currentRevision: Long,
) : RepositoryException("object changed on server (current revision $currentRevision)")

/** Server permanently refused the mutation (4xx). Visible to the user with a reason. */
class CaptureRejectedException(val reason: String) :
    RepositoryException("capture rejected: $reason")

open class RepositoryException(message: String) : Exception(message)
