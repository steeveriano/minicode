package com.danielealbano.androidremotecontrolmcp.ui.screens

import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus

/**
 * Where the device can be reached from, derived from how the server is bound and whether a tunnel
 * is up. It is the fact a user actually needs when deciding whether the phone is exposed, and it
 * was previously only inferrable by reading three separate settings.
 */
internal enum class ServerReach { LOCAL, LAN, INTERNET }

internal fun serverReach(
    bindingAddress: BindingAddress,
    tunnelStatus: TunnelStatus,
): ServerReach =
    when {
        tunnelStatus is TunnelStatus.Connected -> ServerReach.INTERNET
        bindingAddress == BindingAddress.NETWORK -> ServerReach.LAN
        else -> ServerReach.LOCAL
    }
