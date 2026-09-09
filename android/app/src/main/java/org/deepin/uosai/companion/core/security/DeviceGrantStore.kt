package org.deepin.uosai.companion.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.deepin.uosai.companion.core.pairing.PairingTransport
import org.deepin.uosai.companion.core.protocol.PairingGrant

@Serializable
data class DeviceGrant(
    val pairingGrant: PairingGrant,
    val host: String,
    val port: Int,
    val hostDisplayName: String,
    val transport: PairingTransport = PairingTransport.TAILNET,
    val tlsSpkiSha256: String? = null,
)

interface DeviceGrantStore {
    fun load(): DeviceGrant?
    fun save(grant: DeviceGrant)
    fun clear()
}

/** Stores only the post-pairing grant. The QR pairing secret is never persisted. */
class KeystoreDeviceGrantStore(
    context: Context,
    private val keyAlias: String = "uos-ai-companion-device-grant",
) : DeviceGrantStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        "uos-ai-companion-secure-state",
        Context.MODE_PRIVATE,
    )
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun load(): DeviceGrant? {
        val encoded = preferences.getString(STORED_GRANT, null) ?: return null
        return try {
            val parts = encoded.split('.', limit = 2)
            require(parts.size == 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                updateAAD(AAD)
            }
            json.decodeFromString(cipher.doFinal(encrypted).decodeToString())
        } catch (_: Exception) {
            // A restore to a different device cannot decrypt this material. Treat it as logged out.
            clear()
            null
        }
    }

    override fun save(grant: DeviceGrant) {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
            updateAAD(AAD)
        }
        val encrypted = cipher.doFinal(json.encodeToString(grant).encodeToByteArray())
        val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(preferences.edit().putString(STORED_GRANT, value).commit()) { "Unable to store device grant" }
    }

    override fun clear() {
        preferences.edit().remove(STORED_GRANT).commit()
        if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
    }

    private fun secretKey(): SecretKey {
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val STORED_GRANT = "encryptedGrant"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        val AAD = "uos-ai-companion-device-grant-v1".encodeToByteArray()
        val json = Json { ignoreUnknownKeys = true }
    }
}
