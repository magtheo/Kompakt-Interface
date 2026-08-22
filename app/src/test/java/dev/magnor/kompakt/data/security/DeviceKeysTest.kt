package dev.magnor.kompakt.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Device identity (Phase 4, security.md "Device Enrollment"):
 * - Ed25519 sign/verify round-trip against the raw 32-byte public key,
 * - seed persistence through the vault (same key after reload),
 * - generateIfAbsent is idempotent (one key per device, forever).
 */
class DeviceKeysTest {

    @Test
    fun `generated public key is 32 raw bytes base64`() {
        val keys = SoftwareDeviceKeyProvider(InMemorySecretVault())
        val pub = keys.generateIfAbsent()
        assertEquals(32, Base64.getDecoder().decode(pub).size)
    }

    @Test
    fun `signature verifies against public key`() {
        val vault = InMemorySecretVault()
        val keys = SoftwareDeviceKeyProvider(vault)
        val pub = keys.generateIfAbsent()
        val message = "server-nonce-123".toByteArray()

        val signature = keys.sign(message)
        assertNotNull(signature)
        assertEquals(64, signature!!.size)
        assertTrue(verifyEd25519(pub, message, signature))
    }

    @Test
    fun `signature fails on altered message`() {
        val keys = SoftwareDeviceKeyProvider(InMemorySecretVault())
        val pub = keys.generateIfAbsent()
        val signature = keys.sign("nonce-a".toByteArray())!!

        assertFalse(verifyEd25519(pub, "nonce-b".toByteArray(), signature))
    }

    @Test
    fun `seed persists through vault — same key after reload`() {
        val vault = InMemorySecretVault()
        val first = SoftwareDeviceKeyProvider(vault)
        val pub1 = first.generateIfAbsent()
        val signature1 = first.sign("m".toByteArray())!!

        // "Reboot": new provider, same vault backing store.
        val second = SoftwareDeviceKeyProvider(vault)
        assertEquals(pub1, second.publicKeyBase64())
        assertTrue(verifyEd25519(pub1, "m".toByteArray(), second.sign("m".toByteArray())!!))
        // Deterministic signatures: same key + message → identical bytes
        // (Ed25519 is deterministic, RFC 8032).
        assertArrayEquals(signature1, second.sign("m".toByteArray())!!)
    }

    @Test
    fun `generateIfAbsent is idempotent`() {
        val keys = SoftwareDeviceKeyProvider(InMemorySecretVault())
        assertEquals(keys.generateIfAbsent(), keys.generateIfAbsent())
    }

    @Test
    fun `distinct vaults produce distinct keys`() {
        val a = SoftwareDeviceKeyProvider(InMemorySecretVault()).generateIfAbsent()
        val b = SoftwareDeviceKeyProvider(InMemorySecretVault()).generateIfAbsent()
        assertNotEquals(a, b)
    }

    @Test
    fun `no key before generation — sign returns null`() {
        val keys = SoftwareDeviceKeyProvider(InMemorySecretVault())
        assertNull(keys.publicKeyBase64())
        assertNull(keys.sign("x".toByteArray()))
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertTrue("signatures differ", expected.contentEquals(actual))
    }
}
