package com.danielealbano.androidremotecontrolmcp.ui.components

import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ServerStatusCard button enablement")
class ServerStatusCardTest {
    private val running = ServerStatus.Running(port = 8080, bindingAddress = "127.0.0.1")

    @Test
    fun `mcp start enabled when stopped, without any permission`() {
        // The accessibility service is not a prerequisite for running the server: only the
        // accessibility tools need it, and on a sideloaded install it can be unreachable.
        assertTrue(mcpStartStopButtonEnabled(ServerStatus.Stopped))
    }

    @Test
    fun `mcp stop always enabled when running`() {
        assertTrue(mcpStartStopButtonEnabled(running))
    }

    @Test
    fun `mcp disabled while starting or stopping or error`() {
        assertFalse(mcpStartStopButtonEnabled(ServerStatus.Starting))
        assertFalse(mcpStartStopButtonEnabled(ServerStatus.Stopping))
        assertFalse(mcpStartStopButtonEnabled(ServerStatus.Error("boom")))
    }

    @Test
    fun `channel start requires startEnabled`() {
        assertTrue(channelStartStopButtonEnabled(channelEnabled = false, startEnabled = true))
        assertFalse(channelStartStopButtonEnabled(channelEnabled = false, startEnabled = false))
    }

    @Test
    fun `channel stop always enabled`() {
        assertTrue(channelStartStopButtonEnabled(channelEnabled = true, startEnabled = false))
        assertTrue(channelStartStopButtonEnabled(channelEnabled = true, startEnabled = true))
    }
}
