package dev.magnor.kompakt.data.remote

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Enrollment wire contract (Phase 4) — pins the exact request/response
 * shapes verified live against the coordinator's devices.py:
 *
 *   POST /v1/devices/enroll          {name, public_key} → 201 {device_id, status}
 *   GET  /v1/devices/{id}/challenge  → {nonce}
 *   POST /v1/devices/{id}/activate   {nonce, signature}
 *        → 200 {device_token, capabilities} · 202 pending · 403 revoked · 404 unknown
 *
 * Enrollment endpoints are unauthenticated (they ARE the credential path)
 * — a stray Authorization header would break the contract.
 */
class EnrollmentContractTest {

    private lateinit var server: MockWebServer
    private lateinit var api: EnrollmentApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = EnrollmentApi(baseUrl = server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `enroll sends snake_case body and parses snake_case response`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"device_id":"dev-abc123","status":"pending"}"""),
        )

        val response = api.enroll(name = "Kompakt", publicKeyBase64 = "pubkey-b64")

        assertEquals("dev-abc123", response.deviceId)
        assertEquals("pending", response.status)

        val recorded = server.takeRequest()
        assertEquals("/v1/devices/enroll", recorded.path)
        assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"))
        val sent = recorded.body.readUtf8()
        assertTrue(sent.contains("\"name\":\"Kompakt\""))
        assertTrue(sent.contains("\"public_key\":\"pubkey-b64\""))
    }

    @Test
    fun `enroll sends no Authorization header`() = runTest {
        server.enqueue(MockResponse().setBody("""{"device_id":"d","status":"pending"}"""))
        api.enroll("n", "k")
        assertEquals(null, server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `challenge returns nonce`() = runTest {
        server.enqueue(MockResponse().setBody("""{"nonce":"bm9uY2U="}"""))

        assertEquals("bm9uY2U=", api.challenge("dev-1"))

        assertEquals("/v1/devices/dev-1/challenge", server.takeRequest().path)
    }

    @Test
    fun `activate 200 maps to Active with token and capabilities`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"device_token":"tok-xyz","capabilities":["task.read","today.read"]}""",
            ),
        )

        val result = api.activate("dev-1", "bm9uY2U=", "c2ln")

        assertTrue(result is EnrollmentApi.ActivationResult.Active)
        result as EnrollmentApi.ActivationResult.Active
        assertEquals("tok-xyz", result.token)
        assertEquals(listOf("task.read", "today.read"), result.capabilities)

        val recorded = server.takeRequest()
        assertEquals("/v1/devices/dev-1/activate", recorded.path)
        val sent = recorded.body.readUtf8()
        assertTrue(sent.contains("\"nonce\":\"bm9uY2U=\""))
        assertTrue(sent.contains("\"signature\":\"c2ln\""))
    }

    @Test
    fun `activate 202 maps to Pending`() = runTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"status":"pending"}"""))
        assertEquals(
            EnrollmentApi.ActivationResult.Pending,
            api.activate("dev-1", "n", "s"),
        )
    }

    @Test
    fun `activate 403 maps to Revoked`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"revoked"}"""))
        assertEquals(
            EnrollmentApi.ActivationResult.Revoked,
            api.activate("dev-1", "n", "s"),
        )
    }

    @Test
    fun `activate 404 maps to UnknownDevice`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"no such device"}"""))
        assertEquals(
            EnrollmentApi.ActivationResult.UnknownDevice,
            api.activate("dev-1", "n", "s"),
        )
    }

    @Test(expected = dev.magnor.kompakt.domain.RepositoryException::class)
    fun `activate 401 (bad signature) throws`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"signature invalid"}"""))
        api.activate("dev-1", "n", "s")
    }
}
