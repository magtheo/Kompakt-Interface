package dev.magnor.kompakt.data.remote

import dev.magnor.kompakt.data.EnrollmentManager
import dev.magnor.kompakt.data.security.InMemorySecretVault
import dev.magnor.kompakt.data.security.SoftwareDeviceKeyProvider
import dev.magnor.kompakt.domain.Task
import dev.magnor.kompakt.domain.UnauthorizedException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Base64

/**
 * Live end-to-end enrollment against the real coordinator (Phase 4).
 *
 * Skipped unless both env vars are set (same gate as T-004):
 *   KOMPACT_LIVE_URL    e.g. http://localhost:8650
 *   KOMPACT_LIVE_TOKEN  the ADMIN token from config.yaml
 *
 * Covers the whole device lifecycle over the wire:
 *   enroll → admin approve → challenge/sign/activate → authenticated
 *   read with the device token → admin revoke → 401.
 */
class LiveEnrollmentSmokeTest {

    private val liveUrl: String? = System.getenv("KOMPACT_LIVE_URL")
    private val adminToken: String? = System.getenv("KOMPACT_LIVE_TOKEN")

    private fun live(): Boolean = liveUrl != null && adminToken != null

    @Test
    fun `device lifecycle over the wire`() = runTest {
        assumeTrue("KOMPACT_LIVE_URL / KOMPACT_LIVE_TOKEN not set", live())
        val url = liveUrl!!.trimEnd('/')
        val admin = HttpApi(url, adminToken!!)

        val vault = InMemorySecretVault()
        val keys = SoftwareDeviceKeyProvider(vault)
        val manager = EnrollmentManager(
            vault = vault,
            keys = keys,
            apiFactory = { EnrollmentApi(it) },
        )

        // 1. Enroll — unauthenticated.
        val awaiting = manager.requestEnrollment(url, "kompakt-live-smoke")
        assertTrue("expected AwaitingApproval, got $awaiting", awaiting is EnrollmentManager.State.AwaitingApproval)
        awaiting as EnrollmentManager.State.AwaitingApproval
        val deviceId = awaiting.deviceId

        // 2. Admin approves (SSH-equivalent: scripts/devices.py approve).
        admin.post("/v1/admin/devices/$deviceId/approve", "{}")

        // 3. Poll → server challenges → Ed25519 signature → active.
        val active = manager.poll()
        assertTrue("expected Active, got $active", active is EnrollmentManager.State.Active)
        val deviceToken = manager.tokenProvider()()!!

        // 4. The device token reads /v1/ (device principal, capability-gated).
        val asDevice = HttpApi(url, deviceToken)
        val tasks: List<Task> = asDevice.decodeList("/v1/tasks", "tasks")
        println("live smoke: ${tasks.size} tasks readable with device token")
        // Reaching here means auth + envelope + element decode all succeeded;
        // an empty list is fine (server may have no tasks).

        // 5. Admin revokes → the very same token is dead (explicit 401).
        admin.post("/v1/admin/devices/$deviceId/revoke", "{}")
        var threw: UnauthorizedException? = null
        try {
            asDevice.get("/v1/tasks")
        } catch (e: UnauthorizedException) {
            threw = e
        }
        assertTrue("revoked device token must yield 401", threw != null)

        // 6. Server-side hygiene: hard-delete the smoke device row.
        try {
            val cleanup = okhttp3.Request.Builder()
                .delete()
                .url("$url/v1/admin/devices/$deviceId")
                .header("Authorization", "Bearer $adminToken")
                .build()
            HttpApi.defaultClient().newCall(cleanup).execute().use { /* best-effort */ }
        } catch (_: Exception) {
            // revoke already neutralized the record if delete failed
        }
    }
}
