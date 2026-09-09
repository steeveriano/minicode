package com.danielealbano.androidremotecontrolmcp.ui.screens

import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The reach tile answers "who can get to this phone right now", so a wrong answer here understates
 * the exposure of a device that is reachable from the internet.
 */
@DisplayName("serverReach")
class ServerReachTest {
    @Test
    fun `localhost with no tunnel is local`() {
        assertEquals(
            ServerReach.LOCAL,
            serverReach(BindingAddress.LOCALHOST, TunnelStatus.Disconnected),
        )
    }

    @Test
    fun `network binding with no tunnel is lan`() {
        assertEquals(
            ServerReach.LAN,
            serverReach(BindingAddress.NETWORK, TunnelStatus.Disconnected),
        )
    }

    @Test
    fun `a connected tunnel is internet even when bound to localhost`() {
        // The tunnel reaches the loopback interface from outside, so the binding does not contain
        // the exposure — reporting LOCAL here would be the dangerous answer.
        assertEquals(
            ServerReach.INTERNET,
            serverReach(BindingAddress.LOCALHOST, TunnelStatus.Connected(emptyList(), TunnelProviderType.CLOUDFLARE)),
        )
    }

    @Test
    fun `a connecting tunnel is not yet internet`() {
        assertEquals(
            ServerReach.LAN,
            serverReach(BindingAddress.NETWORK, TunnelStatus.Connecting),
        )
    }

    @Test
    fun `a failed tunnel falls back to the binding`() {
        assertEquals(
            ServerReach.LOCAL,
            serverReach(BindingAddress.LOCALHOST, TunnelStatus.Error("boom")),
        )
    }
}
