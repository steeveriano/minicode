@file:Suppress("FunctionNaming", "LongMethod", "MagicNumber", "LongParameterList")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.ChannelConnectionStatus
import com.danielealbano.androidremotecontrolmcp.ui.ApprovalActivity
import com.danielealbano.androidremotecontrolmcp.ui.components.AlertAction
import com.danielealbano.androidremotecontrolmcp.ui.components.AlertSeverity
import com.danielealbano.androidremotecontrolmcp.ui.components.AttentionPanel
import com.danielealbano.androidremotecontrolmcp.ui.components.ConnectionInfoCard
import com.danielealbano.androidremotecontrolmcp.ui.components.DashboardAlert
import com.danielealbano.androidremotecontrolmcp.ui.components.MetricRow
import com.danielealbano.androidremotecontrolmcp.ui.components.MetricTile
import com.danielealbano.androidremotecontrolmcp.ui.components.ServerLogsSection
import com.danielealbano.androidremotecontrolmcp.ui.components.ServerStatusCard
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ChannelViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.LogsViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.PrivacyViewModel
import com.danielealbano.androidremotecontrolmcp.utils.NetworkUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    onNavigateToPermissions: () -> Unit,
    onShowAllLogs: () -> Unit,
    onNavigateToNetworkSettings: () -> Unit,
    onNavigateToTunnelSettings: () -> Unit,
    onOpenPrivacySettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel(),
    channelViewModel: ChannelViewModel = hiltViewModel(),
    privacyViewModel: PrivacyViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val logsViewModel: LogsViewModel = hiltViewModel()

    val privacyConfig by privacyViewModel.privacyConfig.collectAsStateWithLifecycle()
    val privacyCardDismissed by privacyViewModel.privacyCardDismissed.collectAsStateWithLifecycle()
    val serverConfig by viewModel.serverConfig.collectAsStateWithLifecycle()
    val serverStatus by viewModel.serverStatus.collectAsStateWithLifecycle()
    val recentServerLogs by logsViewModel.recentServerLogs.collectAsStateWithLifecycle()
    val tunnelStatus by viewModel.tunnelStatus.collectAsStateWithLifecycle()
    val storageLocations by viewModel.storageLocations.collectAsStateWithLifecycle()

    val isAccessibilityEnabled by viewModel.isAccessibilityEnabled.collectAsStateWithLifecycle()
    val isBatteryOptimizationIgnored by viewModel.isBatteryOptimizationIgnored.collectAsStateWithLifecycle()
    val pendingApprovalCount by viewModel.pendingApprovalCount.collectAsStateWithLifecycle()

    val channelConfig by channelViewModel.eventChannelConfig.collectAsStateWithLifecycle()
    val channelStatus by channelViewModel.channelConnectionStatus.collectAsStateWithLifecycle()

    val deviceIp = remember(context) { NetworkUtils.getDeviceIpAddress(context) ?: "N/A" }
    val copiedToClipboardMessage = stringResource(R.string.copied_to_clipboard)
    var showChannelNotConfiguredDialog by remember { mutableStateOf(false) }

    val alerts =
        buildDashboardAlerts(
            accessibilityEnabled = isAccessibilityEnabled,
            authenticationOff = !serverConfig.oauthEnabled && !serverConfig.bearerTokenEnabled,
            batteryOptimized = !isBatteryOptimizationIgnored,
            pendingApprovalCount = pendingApprovalCount,
            localhostOnly = serverConfig.bindingAddress == BindingAddress.LOCALHOST && !serverConfig.tunnelEnabled,
            offerPrivacy = !privacyConfig.enabled && !privacyCardDismissed,
            onNavigateToPermissions = onNavigateToPermissions,
            onRequestBatteryExemption = { viewModel.requestBatteryOptimizationExemption() },
            onReviewApprovals = { context.startActivity(Intent(context, ApprovalActivity::class.java)) },
            onEnableWifi = {
                viewModel.updateBindingAddress(BindingAddress.NETWORK)
                onNavigateToNetworkSettings()
            },
            onSetUpTunnel = {
                viewModel.updateTunnelEnabled(true)
                onNavigateToTunnelSettings()
            },
            onOpenPrivacySettings = onOpenPrivacySettings,
            onDismissPrivacy = { privacyViewModel.dismissPrivacyCard() },
        )

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_server)) },
            windowInsets = WindowInsets(0),
            colors =
                TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
            // One rhythm for the whole column, instead of a Spacer after every block.
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ServerStatusCard(
                serverStatus = serverStatus,
                channelStatus = channelStatus,
                channelEnabled = channelConfig.enabled,
                onMcpStartClick = { viewModel.startServer(context) },
                onMcpStopClick = { viewModel.stopServer(context) },
                onChannelStartClick = {
                    if (channelConfig.endpointUrl.isBlank()) {
                        showChannelNotConfiguredDialog = true
                    } else {
                        channelViewModel.startChannel()
                    }
                },
                onChannelStopClick = { channelViewModel.stopChannel() },
                startEnabled = isAccessibilityEnabled,
                endpointSummary =
                    stringResource(
                        R.string.dashboard_endpoint_summary,
                        if (serverConfig.bindingAddress == BindingAddress.NETWORK) deviceIp else "127.0.0.1",
                        serverConfig.port,
                    ),
            )

            MetricRow(heading = stringResource(R.string.dashboard_metrics_heading)) {
                ReachTile(serverReach(serverConfig.bindingAddress, tunnelStatus))
                MetricTile(
                    label = stringResource(R.string.dashboard_metric_storage),
                    value = storageLocations.size.toString(),
                    valueColor = MaterialTheme.colorScheme.onSurface,
                )
                ChannelTile(channelStatus, channelConfig.enabled)
            }

            AttentionPanel(
                heading = stringResource(R.string.dashboard_attention_heading),
                alerts = alerts,
            )

            ConnectionInfoCard(
                bindingAddress = serverConfig.bindingAddress,
                ipAddress = deviceIp,
                port = serverConfig.port,
                httpsEnabled = serverConfig.httpsEnabled,
                bearerToken = serverConfig.bearerToken,
                tunnelEnabled = serverConfig.tunnelEnabled,
                serverStatus = serverStatus,
                tunnelStatus = tunnelStatus,
                onCopyAll = { text ->
                    clipboardManager.setText(AnnotatedString(text))
                    Toast.makeText(context, copiedToClipboardMessage, Toast.LENGTH_SHORT).show()
                },
                onShare = { text ->
                    val intent =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                    context.startActivity(Intent.createChooser(intent, null))
                },
            )

            ServerLogsSection(
                logs = recentServerLogs,
                onShowMore = onShowAllLogs,
            )
        }
    }

    if (showChannelNotConfiguredDialog) {
        AlertDialog(
            onDismissRequest = { showChannelNotConfiguredDialog = false },
            title = { Text(stringResource(R.string.channel_not_configured_dialog_title)) },
            text = { Text(stringResource(R.string.channel_not_configured_dialog_body)) },
            confirmButton = {
                TextButton(onClick = { showChannelNotConfiguredDialog = false }) {
                    Text(stringResource(R.string.channel_not_configured_dialog_ok))
                }
            },
        )
    }
}

@Composable
private fun RowScope.ReachTile(reach: ServerReach) {
    MetricTile(
        label = stringResource(R.string.dashboard_metric_reach),
        value =
            when (reach) {
                ServerReach.LOCAL -> stringResource(R.string.dashboard_reach_local)
                ServerReach.LAN -> stringResource(R.string.dashboard_reach_lan)
                ServerReach.INTERNET -> stringResource(R.string.dashboard_reach_internet)
            },
        // Reaching the internet is the state worth noticing, not the state worth celebrating.
        valueColor =
            when (reach) {
                ServerReach.LOCAL -> MaterialTheme.colorScheme.onSurfaceVariant
                ServerReach.LAN -> MaterialTheme.colorScheme.onSurface
                ServerReach.INTERNET -> MaterialTheme.colorScheme.primary
            },
    )
}

@Composable
private fun RowScope.ChannelTile(
    status: ChannelConnectionStatus,
    enabled: Boolean,
) {
    val active = enabled && status is ChannelConnectionStatus.Active
    MetricTile(
        label = stringResource(R.string.dashboard_metric_channel),
        value =
            if (active) {
                stringResource(R.string.dashboard_value_on)
            } else {
                stringResource(R.string.dashboard_value_off)
            },
        valueColor =
            if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Builds the alert list for [AttentionPanel].
 *
 * Kept separate from the layout so which alerts appear, and how serious each is, is a decision that
 * can be read — and tested — on its own.
 */
@Composable
private fun buildDashboardAlerts(
    accessibilityEnabled: Boolean,
    authenticationOff: Boolean,
    batteryOptimized: Boolean,
    pendingApprovalCount: Int,
    localhostOnly: Boolean,
    offerPrivacy: Boolean,
    onNavigateToPermissions: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onReviewApprovals: () -> Unit,
    onEnableWifi: () -> Unit,
    onSetUpTunnel: () -> Unit,
    onOpenPrivacySettings: () -> Unit,
    onDismissPrivacy: () -> Unit,
): List<DashboardAlert> =
    buildList {
        if (authenticationOff) {
            // No authentication is the only alert that means the device itself is at risk.
            add(
                DashboardAlert(
                    id = "no-auth",
                    icon = Icons.Default.Warning,
                    title = stringResource(R.string.access_no_auth_warning_title),
                    severity = AlertSeverity.CRITICAL,
                ),
            )
        }
        if (pendingApprovalCount > 0) {
            add(
                DashboardAlert(
                    id = "approvals",
                    icon = Icons.Default.Notifications,
                    title = stringResource(R.string.server_pending_approvals_title, pendingApprovalCount),
                    severity = AlertSeverity.WARNING,
                    actions =
                        listOf(
                            AlertAction(stringResource(R.string.server_pending_approvals_action), onReviewApprovals),
                        ),
                ),
            )
        }
        if (!accessibilityEnabled) {
            add(
                DashboardAlert(
                    id = "accessibility",
                    icon = Icons.Default.Warning,
                    title = stringResource(R.string.permission_warning_title),
                    severity = AlertSeverity.WARNING,
                    actions =
                        listOf(
                            AlertAction(stringResource(R.string.permission_warning_action), onNavigateToPermissions),
                        ),
                ),
            )
        }
        if (batteryOptimized) {
            add(
                DashboardAlert(
                    id = "battery",
                    icon = Icons.Default.BatteryAlert,
                    title = stringResource(R.string.battery_optimization_card_title),
                    severity = AlertSeverity.WARNING,
                    actions =
                        listOf(
                            AlertAction(
                                stringResource(R.string.battery_optimization_card_action),
                                onRequestBatteryExemption,
                            ),
                        ),
                ),
            )
        }
        if (localhostOnly) {
            add(
                DashboardAlert(
                    id = "reach",
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.server_network_access_suggestion_title),
                    severity = AlertSeverity.INFO,
                    actions =
                        listOf(
                            AlertAction(stringResource(R.string.server_network_access_suggestion_wifi), onEnableWifi),
                            AlertAction(
                                stringResource(R.string.server_network_access_suggestion_tunnel),
                                onSetUpTunnel,
                            ),
                        ),
                ),
            )
        }
        if (offerPrivacy) {
            add(
                DashboardAlert(
                    id = "privacy",
                    icon = Icons.Default.Lightbulb,
                    title = stringResource(R.string.privacy_card_title),
                    severity = AlertSeverity.INFO,
                    actions =
                        listOf(
                            AlertAction(stringResource(R.string.privacy_card_action), onOpenPrivacySettings),
                            AlertAction(stringResource(R.string.privacy_card_dismiss), onDismissPrivacy),
                        ),
                ),
            )
        }
    }
