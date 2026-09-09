@file:Suppress("FunctionNaming", "LongMethod")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.components.SettingsRow
import com.danielealbano.androidremotecontrolmcp.ui.components.SettingsSection
import com.danielealbano.androidremotecontrolmcp.ui.navigation.SettingsRoute

/**
 * The settings index, grouped.
 *
 * Nine equally weighted rows made the user read all nine to find one. The groups answer the
 * question someone actually arrives with — how it is reached, who may reach it, what it may
 * touch — rather than mirroring the order the features were built in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsIndexScreen(
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_settings)) },
            windowInsets = WindowInsets(0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingsSection(title = stringResource(R.string.settings_group_connection)) {
                SettingsRow(
                    icon = Icons.Default.Tune,
                    title = stringResource(R.string.settings_general_title),
                    subtitle = stringResource(R.string.settings_general_subtitle),
                    onClick = { onNavigate(SettingsRoute.General.route) },
                )
                SettingsRow(
                    icon = Icons.Default.Cloud,
                    title = stringResource(R.string.settings_tunnel_title),
                    subtitle = stringResource(R.string.settings_tunnel_subtitle),
                    onClick = { onNavigate(SettingsRoute.Tunnel.route) },
                )
                SettingsRow(
                    icon = Icons.Default.CellTower,
                    title = stringResource(R.string.channel_title),
                    subtitle = stringResource(R.string.event_channel_subtitle),
                    onClick = { onNavigate(SettingsRoute.ChannelSettings.route) },
                    showDivider = false,
                )
            }

            SettingsSection(title = stringResource(R.string.settings_group_security)) {
                SettingsRow(
                    icon = Icons.Default.Key,
                    title = stringResource(R.string.settings_access_title),
                    subtitle = stringResource(R.string.settings_access_subtitle),
                    onClick = { onNavigate(SettingsRoute.Access.route) },
                )
                SettingsRow(
                    icon = Icons.Default.Lock,
                    title = stringResource(R.string.settings_security_title),
                    subtitle = stringResource(R.string.settings_security_subtitle),
                    onClick = { onNavigate(SettingsRoute.Security.route) },
                )
                SettingsRow(
                    icon = Icons.Default.AdminPanelSettings,
                    title = stringResource(R.string.settings_permissions_title),
                    subtitle = stringResource(R.string.settings_permissions_subtitle),
                    onClick = { onNavigate(SettingsRoute.Permissions.route) },
                    showDivider = false,
                )
            }

            SettingsSection(title = stringResource(R.string.settings_group_device_data)) {
                SettingsRow(
                    icon = Icons.Default.Shield,
                    title = stringResource(R.string.settings_privacy_title),
                    subtitle = stringResource(R.string.settings_privacy_subtitle),
                    onClick = { onNavigate(SettingsRoute.Privacy.route) },
                )
                SettingsRow(
                    icon = Icons.Default.Folder,
                    title = stringResource(R.string.settings_storage_title),
                    subtitle = stringResource(R.string.settings_storage_subtitle),
                    onClick = { onNavigate(SettingsRoute.Storage.route) },
                )
                SettingsRow(
                    icon = Icons.Default.Build,
                    title = stringResource(R.string.settings_mcp_tools_title),
                    subtitle = stringResource(R.string.settings_mcp_tools_subtitle),
                    onClick = { onNavigate(SettingsRoute.McpTools.route) },
                    showDivider = false,
                )
            }
        }
    }
}
