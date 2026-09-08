package org.deepin.uosai.companion.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionReducerTest {
    @Test
    fun stopsRetryingAfterTheBoundedRetryBudget() {
        var state: ConnectionState = ConnectionState.Connected
        repeat(4) { state = ConnectionReducer.reduce(state, ConnectionEvent.ConnectionLost) }

        assertEquals(ConnectionState.Reconnecting(attempt = 4, retryAfterMs = 8_000), state)
        assertEquals(ConnectionState.Disconnected, ConnectionReducer.reduce(state, ConnectionEvent.ConnectionLost))
    }

    @Test
    fun authenticationFailureNeverRetries() {
        assertEquals(
            ConnectionState.AuthenticationRequired,
            ConnectionReducer.reduce(ConnectionState.Connected, ConnectionEvent.AuthenticationFailed),
        )
    }
}
