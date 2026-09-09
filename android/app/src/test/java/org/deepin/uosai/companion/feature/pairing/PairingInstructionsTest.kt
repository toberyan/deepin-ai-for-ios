package org.deepin.uosai.companion.feature.pairing

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingInstructionsTest {
    @Test
    fun describesTheLocalSecureQrPairingFlow() {
        assertEquals(
            "Scan the one-time QR code from UOS AI desktop. Keep this device on the selected Wi-Fi or reachable Tailscale network.",
            PairingInstructions.summary,
        )
    }
}
