package dev.magnor.kompakt.data

import dev.magnor.kompakt.data.remote.EnrollmentApi
import dev.magnor.kompakt.data.security.InMemorySecretVault
import dev.magnor.kompakt.data.security.SoftwareDeviceKeyProvider
import dev.magnor.kompakt.data.security.verifyEd25519
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * EnrollmentManager state machine (Phase 4, dev plan §6) against a
 * scripted fake server:
 *
 *     NotEnrolled → (enroll) → AwaitingApproval → (poll, approved) → Active
 *     Active → (poll) token rotation · (revoke) → NotEnrolled
 *     AwaitingApproval → (poll, still pending) → unchanged
 *
 * The fake server actually VERIFIES the Ed25519 activation signature —
 * catching wiring bugs (wrong nonce bytes, wrong b64 arm) that a
 * canned-response mock would let through.
 */
class EnrollmentManagerTest {

    /** Minimal in-test coordinator: enroll → pending, approve() → active. */
    private class FakeServer : Dispatcher() {
        var approved = false
        var revoked = false
        var deviceToken = "tok-1"
        private val nonce = ByteArray(32) { it.toByte() }
        private var publicKey: String? = null

        fun approve() { approved = true }

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            val body = request.body.readUtf8()
            return when {
                path == "/v1/devices/enroll" -> {
                    publicKey = Regex("\"public_key\":\"([^\"]+)\"").find(body)!!.groupValues[1]
                    MockResponse().setResponseCode(201)
                        .setBody("""{"device_id":"dev-test","status":"pending"}""")
                }
                path.endsWith("/challenge") ->
                    MockResponse().setBody(
                        """{"nonce":"${Base64.getEncoder().encodeToString(nonce)}"}""",
                    )
                path.endsWith("/activate") -> when {
                    revoked -> MockResponse().setResponseCode(403).setBody("""{"detail":"revoked"}""")
                    !approved -> MockResponse().setResponseCode(202).setBody("""{"status":"pending"}""")
                    else -> {
                        // Verify the signature like the real server does.
                        val nonceB64 = Regex("\"nonce\":\"([^\"]+)\"").find(body)!!.groupValues[1]
                        val sigB64 = Regex("\"signature\":\"([^\"]+)\"").find(body)!!.groupValues[1]
                        val ok = verifyEd25519(
                            publicKey!!,
                            Base64.getDecoder().decode(nonceB64),
                            Base64.getDecoder().decode(sigB64),
                        )
                        if (!ok) {
                            MockResponse().setResponseCode(401).setBody("""{"detail":"bad signature"}""")
                        } else {
                            MockResponse().setBody(
                                """{"device_token":"$deviceToken","capabilities":["task.read","today.read"]}""",
                            )
                        }
                    }
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private lateinit var server: MockWebServer
    private lateinit var fake: FakeServer
    private lateinit var vault: InMemorySecretVault

    @Before
    fun setUp() {
        server = MockWebServer()
        fake = FakeServer()
        server.dispatcher = fake
        server.start()
        vault = InMemorySecretVault()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun manager(): EnrollmentManager = EnrollmentManager(
        vault = vault,
        keys = SoftwareDeviceKeyProvider(vault),
        apiFactory = { EnrollmentApi(it) },
    )

    @Test
    fun `full lifecycle enroll approve activate`() = runTest {
        val m = manager()
        assertEquals(EnrollmentManager.State.NotEnrolled, m.state.value)

        val awaiting = m.requestEnrollment(server.url("/").toString(), "Kompakt")
        assertTrue(awaiting is EnrollmentManager.State.AwaitingApproval)
        awaiting as EnrollmentManager.State.AwaitingApproval
        assertEquals("dev-test", awaiting.deviceId)
        assertEquals("Kompakt", awaiting.name)

        // Not yet approved → poll stays in AwaitingApproval.
        assertTrue(m.poll() is EnrollmentManager.State.AwaitingApproval)
        assertNull(m.tokenProvider()())

        // Approve → poll activates with a verifiable signature.
        fake.approve()
        val active = m.poll()
        assertTrue(active is EnrollmentManager.State.Active)
        active as EnrollmentManager.State.Active
        assertEquals("tok-1", m.tokenProvider()())
        assertEquals(listOf("task.read", "today.read"), active.capabilities)
    }

    @Test
    fun `restore from vault at boot — token means Active`() = runTest {
        val first = manager()
        first.requestEnrollment(server.url("/").toString(), "Kompakt")
        fake.approve()
        first.poll()

        // New manager, same vault (app restart).
        val rebooted = manager()
        val state = rebooted.state.value
        assertTrue("expected Active after restore, got $state", state is EnrollmentManager.State.Active)
        assertEquals("tok-1", rebooted.tokenProvider()())
    }

    @Test
    fun `restore from vault without token means AwaitingApproval`() = runTest {
        val first = manager()
        first.requestEnrollment(server.url("/").toString(), "Kompakt") // no approval

        val rebooted = manager()
        val state = rebooted.state.value
        assertTrue(state is EnrollmentManager.State.AwaitingApproval)
        assertNull(rebooted.tokenProvider()())
    }

    @Test
    fun `forget clears state and token`() = runTest {
        val m = manager()
        m.requestEnrollment(server.url("/").toString(), "Kompakt")
        fake.approve()
        m.poll()
        assertTrue(m.state.value is EnrollmentManager.State.Active)

        m.forget()
        assertEquals(EnrollmentManager.State.NotEnrolled, m.state.value)
        assertNull(m.tokenProvider()())
    }

    @Test
    fun `revoked on poll wipes local state`() = runTest {
        val m = manager()
        m.requestEnrollment(server.url("/").toString(), "Kompakt")
        fake.approved = true
        fake.revoked = true
        val result = m.poll()
        assertEquals(EnrollmentManager.State.NotEnrolled, result)
        assertEquals(EnrollmentManager.State.NotEnrolled, m.state.value)
    }

    @Test
    fun `device key survives forget and is reused on re-enroll`() = runTest {
        val m = manager()
        val pub1 = SoftwareDeviceKeyProvider(vault).generateIfAbsent()
        m.requestEnrollment(server.url("/").toString(), "Kompakt")
        m.forget()

        // Re-enroll uses the SAME key (identity persists; only session state cleared).
        m.requestEnrollment(server.url("/").toString(), "Kompakt 2")
        fake.approve()
        val active = m.poll() as EnrollmentManager.State.Active
        assertEquals("Kompakt 2", active.name)
        assertEquals(pub1, SoftwareDeviceKeyProvider(vault).publicKeyBase64())
    }

    @Test
    fun `re-activation while active rotates the token`() = runTest {
        val m = manager()
        m.requestEnrollment(server.url("/").toString(), "Kompakt")
        fake.approve()
        m.poll()
        assertEquals("tok-1", m.tokenProvider()())

        fake.deviceToken = "tok-2"
        val again = m.poll()
        assertTrue(again is EnrollmentManager.State.Active)
        assertEquals("tok-2", m.tokenProvider()())
    }
}
