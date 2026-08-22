package dev.magnor.kompakt.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted-at-rest key/value store for device credentials
 * (security.md "Device Identity and Key Storage").
 *
 * The Ed25519 device key cannot live in Android Keystore directly
 * (Keystore Ed25519 support is device-dependent — must be tested on
 * hardware, not assumed), so v0.1 wraps it: a Keystore-held AES-GCM
 * key encrypts the private seed on disk. The seed is never written in
 * plaintext. Upgrade path: swap the [DeviceKeyProvider] implementation
 * once on-device testing confirms native Keystore Ed25519.
 */
interface SecretVault {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** Test/demo vault — plain in-memory map, no persistence. */
class InMemorySecretVault : SecretVault {
    private val map = mutableMapOf<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String) {
        map[key] = value
    }

    override fun remove(key: String) {
        map.remove(key)
    }
}

/**
 * Android Keystore-backed vault. Each secret is a file under
 * `files/secrets/<sha256(key)>.bin` holding `iv || ciphertext`; the
 * AES-256-GCM key never leaves the Keystore (not exportable by spec).
 */
class KeystoreSecretVault(context: Context) : SecretVault {
    private val dir: File = File(context.filesDir, "secrets").apply { mkdirs() }
    private val keyAlias = "kompakt-secrets-master"

    override fun get(key: String): String? {
        val file = fileFor(key)
        if (!file.isFile) return null
        val blob = file.readBytes()
        val iv = blob.copyOfRange(0, 12)
        val ct = blob.copyOfRange(12, blob.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, iv))
        return try {
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            null // wrong key or corrupt file — treat as absent
        }
    }

    override fun put(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = cipher.iv + ct
        fileFor(key).writeBytes(blob)
    }

    override fun remove(key: String) {
        fileFor(key).delete()
    }

    // ---- internals ----

    private fun fileFor(key: String): File {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(dir, "$name.bin")
    }

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
