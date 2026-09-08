package org.deepin.uosai.companion.feature.pairing

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingEntryModeTest {
    @Test
    fun pairingStartsWithScannerAndAllowsManualFallback() {
        val initial = PairingEntryMode.initial()

        assertEquals(PairingEntryMode.Scanner, initial)
        assertEquals(PairingEntryMode.Manual, initial.showManualEntry())
        assertEquals(PairingEntryMode.Scanner, PairingEntryMode.Manual.showScanner())
    }
}
