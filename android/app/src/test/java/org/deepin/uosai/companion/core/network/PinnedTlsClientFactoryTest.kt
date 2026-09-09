package org.deepin.uosai.companion.core.network

import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.deepin.uosai.companion.core.pairing.PairingTransport

class PinnedTlsClientFactoryTest {
    @Test
    fun matchingSpkiPinCompletesHttpsHandshake() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val server = MockWebServer()
        server.use { webServer ->
            webServer.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
            webServer.enqueue(MockResponse().setBody("paired"))

            val response = PinnedTlsClientFactory.create("localhost", spkiPin(certificate))
                .newCall(Request.Builder().url(webServer.url("/")).build())
                .execute()

            response.use { assertEquals(200, it.code) }
        }
    }

    @Test
    fun mismatchedSpkiPinRejectsHttpsHandshake() {
        val serverCertificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val otherCertificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val server = MockWebServer()
        server.use { webServer ->
            webServer.useHttps(HandshakeCertificates.Builder().heldCertificate(serverCertificate).build().sslSocketFactory(), false)

            assertFailsWith<IOException> {
                PinnedTlsClientFactory.create("localhost", spkiPin(otherCertificate))
                    .newCall(Request.Builder().url(webServer.url("/")).build())
                    .execute()
            }
        }
    }

    @Test
    fun rejectsTailnetAndMissingPins() {
        assertFailsWith<IllegalArgumentException> {
            PinnedTlsClientFactory.create(PairingTransport.TAILNET, "localhost", spkiPin(HeldCertificate.Builder().build()))
        }
        assertFailsWith<IllegalArgumentException> {
            PinnedTlsClientFactory.create(PairingTransport.LOCAL, "localhost", null)
        }
    }

    private fun spkiPin(certificate: HeldCertificate): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(certificate.certificate.publicKey.encoded),
    )
}
