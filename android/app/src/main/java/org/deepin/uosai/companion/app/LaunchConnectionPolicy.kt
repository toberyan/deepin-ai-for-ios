package org.deepin.uosai.companion.app

class LaunchConnectionPolicy(
    private val reconnectSavedGrant: () -> Unit,
    private val pairInvitation: (String) -> Unit,
) {
    fun handle(incomingPairingUri: String?) {
        if (incomingPairingUri == null) {
            reconnectSavedGrant()
        } else {
            pairInvitation(incomingPairingUri)
        }
    }
}
