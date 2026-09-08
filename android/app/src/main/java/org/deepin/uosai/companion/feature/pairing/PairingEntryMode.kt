package org.deepin.uosai.companion.feature.pairing

sealed interface PairingEntryMode {
    data object Scanner : PairingEntryMode
    data object Manual : PairingEntryMode

    companion object {
        fun initial(): PairingEntryMode = Scanner
    }
}

fun PairingEntryMode.showManualEntry(): PairingEntryMode = PairingEntryMode.Manual

fun PairingEntryMode.showScanner(): PairingEntryMode = PairingEntryMode.Scanner
