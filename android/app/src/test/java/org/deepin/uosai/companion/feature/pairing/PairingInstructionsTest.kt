package org.deepin.uosai.companion.feature.pairing

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingInstructionsTest {
    @Test
    fun describesTheLocalSecureQrPairingFlow() {
        assertEquals(
            "Scan the one-time QR code from UOS AI desktop. The secure Tailscale connection and certificate verification are included in the QR code.",
            PairingInstructions.summary,
        )
    }
}
