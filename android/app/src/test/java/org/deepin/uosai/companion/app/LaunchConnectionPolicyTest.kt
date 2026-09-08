package org.deepin.uosai.companion.app

import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchConnectionPolicyTest {
    @Test
    fun incomingPairingUriIsHandledBeforeAnySavedGrantReconnect() {
        val calls = mutableListOf<String>()
        val policy = LaunchConnectionPolicy(
            reconnectSavedGrant = { calls += "reconnect" },
            pairInvitation = { invitation -> calls += "pair:$invitation" },
        )
        val expiredInvitation = "uos-ai://pair?v=1&host=uos-ai.example.ts.net&port=45980&pairingSecret=abc&expiresAtMs=1"

        policy.handle(expiredInvitation)

        assertEquals(listOf("pair:$expiredInvitation"), calls)
    }

    @Test
    fun noIncomingPairingUriReconnectsSavedGrant() {
        val calls = mutableListOf<String>()
        val policy = LaunchConnectionPolicy(
            reconnectSavedGrant = { calls += "reconnect" },
            pairInvitation = { invitation -> calls += "pair:$invitation" },
        )

        policy.handle(null)

        assertEquals(listOf("reconnect"), calls)
    }
}
