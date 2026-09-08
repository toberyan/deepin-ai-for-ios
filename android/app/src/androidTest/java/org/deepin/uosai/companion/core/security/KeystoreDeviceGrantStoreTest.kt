package org.deepin.uosai.companion.core.security

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.deepin.uosai.companion.core.protocol.PairingGrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreDeviceGrantStoreTest {
    @Test
    fun savesReadsAndClearsDeviceGrant() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = KeystoreDeviceGrantStore(context, "device-grant-test")
        store.clear()
        val grant = DeviceGrant(
            pairingGrant = PairingGrant("device-1", "secret-token", "Y700", listOf("workspace-1")),
            host = "uos-ai.example.ts.net",
            port = 45980,
            hostDisplayName = "UOS AI",
        )

        store.save(grant)

        assertEquals(grant, store.load())
        store.clear()
        assertNull(store.load())
    }
}
