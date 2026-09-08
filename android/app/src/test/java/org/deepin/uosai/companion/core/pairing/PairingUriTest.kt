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
}
