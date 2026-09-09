package org.deepin.uosai.companion.core.pairing

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.test.assertFailsWith

class PairingUriTest {
    private val nowMs = 1_700_000_000_000L

    @Test
    fun parsesAValidTailnetHostnameAsWss() {
        val invitation = PairingUri.parse(
            "uos-ai://pair?v=1&host=uos-ai.example.ts.net&port=45980&pairingSecret=abc&expiresAtMs=1700000300000&hostDisplayName=UOS-AI",
            nowMs,
        )

        assertEquals("wss://uos-ai.example.ts.net:45980/", invitation.webSocketUrl().toString())
    }

    @Test
    fun parsesAV2LocalInvitationWithCertificatePin() {
        val invitation = PairingUri.parse(
            "uos-ai://pair?v=2&transport=local&host=192.168.1.7&port=45980&pairingSecret=abc&expiresAtMs=1700000300000&hostDisplayName=UOS-AI&tlsSpkiSha256=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            nowMs,
        )

        assertEquals(PairingTransport.LOCAL, invitation.transport)
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", invitation.tlsSpkiSha256)
        assertEquals("wss://192.168.1.7:45980/", invitation.webSocketUrl())
    }

    @Test
    fun rejectsUnsafeV2LocalAddresses() {
        listOf("8.8.8.8", "127.0.0.1", "169.254.1.1", "0.0.0.0", "192.168.1.999").forEach { host ->
            assertFailsWith<PairingUriError.UnsafeHost> {
                PairingUri.parse(localInvitation(host), nowMs)
            }
        }
    }

    @Test
    fun rejectsInvalidV2CertificatePins() {
        assertFailsWith<PairingUriError.MissingOrInvalidParameter> {
            PairingUri.parse(localInvitation(tlsSpkiSha256 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"), nowMs)
        }
        assertFailsWith<PairingUriError.MissingOrInvalidParameter> {
            PairingUri.parse(localInvitation(tlsSpkiSha256 = "not_base64!"), nowMs)
        }
    }

    @Test
    fun rejectsDuplicatedUnexpectedAndExpiredV2Invitations() {
        assertFailsWith<PairingUriError.MissingOrInvalidParameter> {
            PairingUri.parse(localInvitation() + "&host=192.168.1.8", nowMs)
        }
        assertFailsWith<PairingUriError.UnexpectedParameter> {
            PairingUri.parse(localInvitation() + "&redirect=https%3A%2F%2Fexample.com", nowMs)
        }
        assertFailsWith<PairingUriError.Expired> {
            PairingUri.parse(localInvitation(expiresAtMs = 1_699_999_999_999L), nowMs)
        }
    }

    @Test
    fun rejectsExpiredPublicAndUnexpectedInvitations() {
        assertFailsWith<PairingUriError.Expired> {
            PairingUri.parse(
                "uos-ai://pair?v=1&host=uos-ai.example.ts.net&port=45980&pairingSecret=abc&expiresAtMs=1699999999999&hostDisplayName=UOS-AI",
                nowMs,
            )
        }
        assertFailsWith<PairingUriError.UnsafeHost> {
            PairingUri.parse(
                "uos-ai://pair?v=1&host=example.com&port=45980&pairingSecret=abc&expiresAtMs=1700000300000&hostDisplayName=UOS-AI",
                nowMs,
            )
        }
        assertFailsWith<PairingUriError.UnexpectedParameter> {
            PairingUri.parse(
                "uos-ai://pair?v=1&host=uos-ai.example.ts.net&port=45980&pairingSecret=abc&expiresAtMs=1700000300000&hostDisplayName=UOS-AI&redirect=https%3A%2F%2Fexample.com",
                nowMs,
            )
        }
    }

    private fun localInvitation(
        host: String = "192.168.1.7",
        expiresAtMs: Long = 1_700_000_300_000L,
        tlsSpkiSha256: String = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    ) = "uos-ai://pair?v=2&transport=local&host=$host&port=45980&pairingSecret=abc&expiresAtMs=$expiresAtMs&hostDisplayName=UOS-AI&tlsSpkiSha256=$tlsSpkiSha256"
}
