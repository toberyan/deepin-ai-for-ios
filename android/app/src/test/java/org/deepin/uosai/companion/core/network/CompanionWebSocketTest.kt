package org.deepin.uosai.companion.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import org.deepin.uosai.companion.core.pairing.PairingTransport
import org.deepin.uosai.companion.core.pairing.PairingUri
import org.deepin.uosai.companion.core.protocol.PairingGrant

class CompanionWebSocketTest {
    @Test
    fun localV2InvitationMapsToGrantWithTransportAndCertificatePinBeforeSave() {
        val pin = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val invitation = PairingUri.parse(
            "uos-ai://pair?v=2&transport=local&host=192.168.1.7&port=45980&pairingSecret=abc" +
                "&expiresAtMs=1700000300000&hostDisplayName=UOS-AI&tlsSpkiSha256=$pin",
            nowMs = 1_700_000_000_000,
        )

        val grant = invitation.toDeviceGrant(
            PairingGrant("device-id", "token", "Tablet", emptyList()),
        )

        assertEquals(PairingTransport.LOCAL, grant.transport)
        assertEquals(pin, grant.tlsSpkiSha256)
    }
}
