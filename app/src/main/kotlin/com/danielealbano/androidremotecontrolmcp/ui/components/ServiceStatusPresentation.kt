@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ChannelConnectionStatus
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.ui.theme.WarningAmber

/*
 * How a service's state is spelled and coloured. Kept apart from the panel that lays it out, so the
 * meaning of a state can be read without wading through layout.
 */

@Composable
internal fun serverStatusToText(status: ServerStatus): String =
    when (status) {
        is ServerStatus.Running -> stringResource(R.string.server_status_running)
        is ServerStatus.Stopped -> stringResource(R.string.server_status_stopped)
        is ServerStatus.Starting -> stringResource(R.string.server_status_starting)
        is ServerStatus.Stopping -> stringResource(R.string.server_status_stopping)
        is ServerStatus.Error -> stringResource(R.string.server_status_error, status.message)
    }

/**
 * Stopped is neutral, not red.
 *
 * A stopped server is a normal resting state the user chose, and colouring it as an error trained
 * the eye to ignore the one colour that should mean something is wrong. Red is kept for an actual
 * error, amber for the transitions, and the accent for running — the same accent as the launcher
 * icon and the primary action.
 */
@Composable
internal fun serverStatusToColor(status: ServerStatus): Color =
    when (status) {
        is ServerStatus.Running -> MaterialTheme.colorScheme.primary
        is ServerStatus.Stopped -> MaterialTheme.colorScheme.onSurfaceVariant
        is ServerStatus.Starting -> WarningAmber
        is ServerStatus.Stopping -> WarningAmber
        is ServerStatus.Error -> MaterialTheme.colorScheme.error
    }

@Composable
internal fun channelStatusToText(
    status: ChannelConnectionStatus,
    enabled: Boolean,
): String =
    if (!enabled) {
        stringResource(R.string.server_status_stopped)
    } else {
        when (status) {
            is ChannelConnectionStatus.Idle -> stringResource(R.string.channel_status_idle)
            is ChannelConnectionStatus.Active -> stringResource(R.string.channel_status_active)
            is ChannelConnectionStatus.Error -> status.message
        }
    }

@Composable
internal fun channelStatusToColor(
    status: ChannelConnectionStatus,
    enabled: Boolean,
): Color =
    if (!enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        when (status) {
            is ChannelConnectionStatus.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
            is ChannelConnectionStatus.Active -> MaterialTheme.colorScheme.primary
            is ChannelConnectionStatus.Error -> MaterialTheme.colorScheme.error
        }
    }
