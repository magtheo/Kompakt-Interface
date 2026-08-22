package dev.magnor.kompakt.data.security

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * Device identity — the Ed25519 key pair from security.md "Device
 * Enrollment". The private key never leaves the device; only the raw
 * 32-byte public key is transmitted during enrollment, and activation
 * signs a server nonce to prove possession.
 */
interface DeviceKeyProvider {
    /** Generate a key pair if none exists. Returns the b64 public key. */
    fun generateIfAbsent(): String

    /** b64 raw public key (32 bytes), or null when not generated. */
    fun publicKeyBase64(): String?

    /** Sign `message` (activation nonce) → 64-byte signature, or null when no key. */
    fun sign(message: ByteArray): ByteArray?
}

/**
 * Software Ed25519 identity (BouncyCastle lightweight API — no JCE
 * provider registration, safe on every Android version). The 32-byte
 * private seed is persisted through a [SecretVault]; on a real device
 * that means Keystore-AES-wrapped at rest (see [KeystoreSecretVault]).
 */
class SoftwareDeviceKeyProvider(private val vault: SecretVault) : DeviceKeyProvider {

    private fun privateKey(): Ed25519PrivateKeyParameters? {
        val seedB64 = vault.get(SEED_KEY) ?: return null
        val seed = java.util.Base64.getDecoder().decode(seedB64)
        if (seed.size != 32) return null
        return Ed25519PrivateKeyParameters(seed, 0)
    }

    override fun generateIfAbsent(): String {
        privateKey()?.let { return publicKeyB64(it) }
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        vault.put(SEED_KEY, java.util.Base64.getEncoder().encodeToString(seed))
        return publicKeyB64(Ed25519PrivateKeyParameters(seed, 0))
    }

    override fun publicKeyBase64(): String? {
        val priv = privateKey() ?: return null
        return publicKeyB64(priv)
    }

    override fun sign(message: ByteArray): ByteArray? {
        val priv = privateKey() ?: return null
        val signer = Ed25519Signer()
        signer.init(true, priv)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    private fun publicKeyB64(priv: Ed25519PrivateKeyParameters): String {
        val pub: Ed25519PublicKeyParameters = priv.generatePublicKey()
        return java.util.Base64.getEncoder().encodeToString(pub.encoded)
    }

    private companion object {
        const val SEED_KEY = "device.ed25519.seed"
    }
}

/** Test implementation — key lives only in memory. */
class InMemoryDeviceKeyProvider : DeviceKeyProvider {
    private val delegate = SoftwareDeviceKeyProvider(InMemorySecretVault())
    override fun generateIfAbsent(): String = delegate.generateIfAbsent()
    override fun publicKeyBase64(): String? = delegate.publicKeyBase64()
    override fun sign(message: ByteArray): ByteArray? = delegate.sign(message)
}

/** Verify an Ed25519 signature against a raw b64 public key (tests). */
fun verifyEd25519(publicKeyBase64: String, message: ByteArray, signature: ByteArray): Boolean {
    val pub = Ed25519PublicKeyParameters(java.util.Base64.getDecoder().decode(publicKeyBase64), 0)
    val verifier = Ed25519Signer()
    verifier.init(false, pub)
    verifier.update(message, 0, message.size)
    return verifier.verifySignature(signature)
}
