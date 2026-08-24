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

/**
 * 409 analog for notes (D028 v2): the file's checksum moved on from
 * `expected_checksum`. Carries the FRESH note (text + checksum) so the
 * editor can reload without a second round-trip — the user's edit is
 * never silently merged (wording contract: explicit re-apply only).
 */
class NoteConflictException(val fresh: Note) :
    RepositoryException("note changed on server (checksum mismatch)")

/** Server permanently refused the mutation (4xx). Visible to the user with a reason. */
class CaptureRejectedException(val reason: String) :
    RepositoryException("capture rejected: $reason")

/** 401: the device token is missing, revoked, or wrong — re-enrollment needed (protocol §16). */
class UnauthorizedException :
    RepositoryException("device not authorized — enrollment required")

/** Transport-level failure: no route to server, timeout, DNS. UI offers retry. */
class OfflineException(cause: Throwable) :
    RepositoryException("offline: ${cause.message ?: cause::class.simpleName}")

/** 5xx: the server is alive enough to answer but broken. UI offers retry. */
class ServerUnavailableException(val code: Int) :
    RepositoryException("server unavailable (HTTP $code)")

open class RepositoryException(message: String) : Exception(message)
