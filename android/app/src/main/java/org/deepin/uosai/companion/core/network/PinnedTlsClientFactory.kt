package org.deepin.uosai.companion.core.network

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import org.deepin.uosai.companion.core.pairing.PairingTransport

/** Builds isolated clients for a local companion whose leaf public key was authenticated by its QR code. */
object PinnedTlsClientFactory {
    fun create(transport: PairingTransport, host: String, tlsSpkiSha256: String?): OkHttpClient {
        require(transport == PairingTransport.LOCAL) { "Pinned TLS is only valid for local connections" }
        return create(host, requireNotNull(tlsSpkiSha256) { "Local connections require a TLS pin" })
    }

    fun create(host: String, tlsSpkiSha256: String): OkHttpClient {
        require(host.isNotBlank()) { "Pinned TLS requires a host" }
        val expectedPin = decodePin(tlsSpkiSha256)
        val trustManager = PinnedLeafTrustManager(expectedPin)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), null)
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { hostname, _ -> hostname == host }
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private fun decodePin(pin: String): ByteArray = try {
        Base64.getUrlDecoder().decode(pin).also { decoded ->
            require(decoded.size == SHA256_LENGTH && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == pin) {
                "TLS pin must be an unpadded base64url SHA-256 digest"
            }
        }
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("TLS pin must be an unpadded base64url SHA-256 digest", error)
    }

    private class PinnedLeafTrustManager(private val expectedPin: ByteArray) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            throw CertificateException("Client certificates are not supported")
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("Server did not provide a certificate")
            leaf.checkValidity()
            val actualPin = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
            if (!MessageDigest.isEqual(expectedPin, actualPin)) {
                throw CertificateException("Server certificate pin did not match")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private const val SHA256_LENGTH = 32
}
