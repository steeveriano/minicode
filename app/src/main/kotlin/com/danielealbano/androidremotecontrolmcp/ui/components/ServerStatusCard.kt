@file:Suppress("FunctionNaming", "MagicNumber", "LongParameterList")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ChannelConnectionStatus
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme
import com.danielealbano.androidremotecontrolmcp.ui.theme.WarningAmber

private const val ANIMATION_DURATION_MS = 300
private const val PULSE_DURATION_MS = 1400

/**
 * The dashboard's primary panel: what the server is doing, and the one action that changes it.
 *
 * The running state is the headline rather than a row in a list — it is the single fact the screen
 * exists to report, and on a phone it has to be readable without focusing. The event channel stays
 * a compact secondary row, because it is a supporting service and giving it equal weight was part
 * of what made the old card read as an undifferentiated list.
 *
 * @param endpointSummary Where the server can be reached, shown under the headline. Empty hides it.
 */
@Composable
fun ServerStatusCard(
    serverStatus: ServerStatus,
    channelStatus: ChannelConnectionStatus,
    channelEnabled: Boolean,
    onMcpStartClick: () -> Unit,
    onMcpStopClick: () -> Unit,
    onChannelStartClick: () -> Unit,
    onChannelStopClick: () -> Unit,
    startEnabled: Boolean,
    modifier: Modifier = Modifier,
    endpointSummary: String = "",
) {
    DashboardPanel(modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            ServerHeadline(
                serverStatus = serverStatus,
                endpointSummary = endpointSummary,
                onStartClick = onMcpStartClick,
                onStopClick = onMcpStopClick,
            )

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(14.dp))

            SecondaryServiceRow(
                label = stringResource(R.string.dashboard_service_channel),
                statusText = channelStatusToText(channelStatus, channelEnabled),
                statusColor = channelStatusToColor(channelStatus, channelEnabled),
                buttonText =
                    if (channelEnabled) {
                        stringResource(R.string.server_action_stop)
                    } else {
                        stringResource(R.string.server_action_start)
                    },
                buttonEnabled = channelStartStopButtonEnabled(channelEnabled, startEnabled),
                onButtonClick = if (channelEnabled) onChannelStopClick else onChannelStartClick,
            )
        }
    }
}

@Composable
private fun ServerHeadline(
    serverStatus: ServerStatus,
    endpointSummary: String,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
) {
    val running = serverStatus is ServerStatus.Running
    val statusColor = serverStatusToColor(serverStatus)
    val animatedColor by animateColorAsState(
        targetValue = statusColor,
        animationSpec = tween(durationMillis = ANIMATION_DURATION_MS),
        label = "statusColor",
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDot(color = animatedColor, pulsing = running)
                InlineGap()
                Text(
                    text = serverStatusToText(serverStatus),
                    style =
                        MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.4).sp,
                        ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(4.dp))
            TileLabel(stringResource(R.string.dashboard_service_mcp))
            if (endpointSummary.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = endpointSummary,
                    style =
                        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        PrimaryServiceAction(
            running = running,
            enabled = mcpStartStopButtonEnabled(serverStatus),
            onClick = if (running) onStopClick else onStartClick,
        )
    }
}

/**
 * Stop is outlined and Start is filled: the accent marks the action that starts work, so a running
 * server is not competing with its own button for attention.
 */
@Composable
private fun PrimaryServiceAction(
    running: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    if (running) {
        OutlinedButton(onClick = onClick, enabled = enabled) {
            Text(stringResource(R.string.server_action_stop))
        }
    } else {
        Button(
            onClick = onClick,
            enabled = enabled,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
        ) {
            Text(stringResource(R.string.server_action_start))
        }
    }
}

@Composable
private fun PulsingDot(
    color: Color,
    pulsing: Boolean,
) {
    if (!pulsing) {
        StatusDot(color = color, size = 12)
        return
    }
    // A slow breath, not a blink: it should read as "alive" in peripheral vision without pulling
    // the eye away from whatever the user is actually doing.
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(PULSE_DURATION_MS),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "pulseAlpha",
    )
    StatusDot(color = color.copy(alpha = alpha), size = 12)
}

@Composable
private fun SecondaryServiceRow(
    label: String,
    statusText: String,
    statusColor: Color,
    buttonText: String,
    buttonEnabled: Boolean,
    onButtonClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            StatusDot(color = statusColor, size = 8)
            InlineGap(8)
            Column {
                Text(text = label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedButton(onClick = onButtonClick, enabled = buttonEnabled) {
            Text(text = buttonText)
        }
    }
}

/**
 * Whether the MCP Server start/stop button is enabled. Only transient states disable it.
 *
 * Starting the server no longer requires the accessibility service. That permission is needed by
 * the accessibility tools alone, each of which already fails with a clear error when it is
 * missing; gating the whole server on it also blocked the file, storage, app-management and
 * notification tools, which do not use it. On a sideloaded install Android additionally places
 * the permission behind "restricted settings", which some devices make hard or impossible to
 * reach — so the gate could lock a user out of the server entirely.
 */
internal fun mcpStartStopButtonEnabled(status: ServerStatus): Boolean =
    when (status) {
        is ServerStatus.Running -> true
        is ServerStatus.Stopped -> true
        else -> false
    }

/**
 * Whether the Event Channel start/stop button is enabled. Stop (enabled) is always allowed;
 * Start requires [startEnabled] (accessibility granted).
 */
internal fun channelStartStopButtonEnabled(
    channelEnabled: Boolean,
    startEnabled: Boolean,
): Boolean = if (channelEnabled) true else startEnabled

@Preview(showBackground = true)
@Composable
private fun ServerStatusCardRunningPreview() {
    AndroidRemoteControlMcpTheme(darkTheme = true) {
        ServerStatusCard(
            serverStatus = ServerStatus.Running(port = 8080, bindingAddress = "0.0.0.0"),
            channelStatus = ChannelConnectionStatus.Active,
            channelEnabled = true,
            onMcpStartClick = {},
            onMcpStopClick = {},
            onChannelStartClick = {},
            onChannelStopClick = {},
            startEnabled = true,
            endpointSummary = "192.168.1.42 · port 8080",
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ServerStatusCardStoppedPreview() {
    AndroidRemoteControlMcpTheme(darkTheme = true) {
        ServerStatusCard(
            serverStatus = ServerStatus.Stopped,
            channelStatus = ChannelConnectionStatus.Idle,
            channelEnabled = false,
            onMcpStartClick = {},
            onMcpStopClick = {},
            onChannelStartClick = {},
            onChannelStopClick = {},
            startEnabled = true,
        )
    }
}
