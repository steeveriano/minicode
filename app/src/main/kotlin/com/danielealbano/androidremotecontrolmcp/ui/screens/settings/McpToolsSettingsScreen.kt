@file:Suppress("FunctionNaming", "LongMethod")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.OptionalToolPermission
import com.danielealbano.androidremotecontrolmcp.data.model.OptionalToolPermissions
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.ui.components.DashboardPanel
import com.danielealbano.androidremotecontrolmcp.ui.components.HelpHint
import com.danielealbano.androidremotecontrolmcp.ui.components.HelpText
import com.danielealbano.androidremotecontrolmcp.ui.components.SectionIntro
import com.danielealbano.androidremotecontrolmcp.ui.components.TileLabel
import com.danielealbano.androidremotecontrolmcp.ui.theme.WarningAmber
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel

private data class ParamEntry(
    val paramName: String,
    val displayName: String,
)

private data class ToolEntry(
    val toolName: String,
    val displayName: String,
    val params: List<ParamEntry> = emptyList(),
)

private data class ToolCategory(
    /**
     * The group's name. A string resource, unlike the tool and parameter names below: a category is
     * this screen's own copy, while a tool's name is the MCP identifier a client sees on the wire,
     * and translating those would leave the two halves of a pairing unable to refer to each other.
     */
    @StringRes val headerRes: Int,
    val tools: List<ToolEntry>,
)

private val ALL_TOOL_CATEGORIES: List<ToolCategory> =
    listOf(
        ToolCategory(
            R.string.mcp_category_screen,
            listOf(
                ToolEntry(
                    "get_screen_state",
                    "Get screen state",
                    listOf(ParamEntry("include_screenshot", "Include screenshot")),
                ),
            ),
        ),
        ToolCategory(
            R.string.mcp_category_system,
            listOf(
                ToolEntry("press_back", "Press Back"),
                ToolEntry("press_home", "Press Home"),
                ToolEntry("press_recents", "Press Recents"),
                ToolEntry("open_notifications", "Open Notifications"),
                ToolEntry("open_quick_settings", "Open Quick Settings"),
                ToolEntry("dismiss_keyboard", "Dismiss Keyboard"),
            ),
        ),
        ToolCategory(
            R.string.mcp_category_touch,
            listOf(
                ToolEntry("tap", "Tap"),
                ToolEntry("long_press", "Long Press"),
                ToolEntry("double_tap", "Double Tap"),
                ToolEntry("swipe", "Swipe"),
                ToolEntry("scroll", "Scroll"),
            ),
        ),
        ToolCategory(
            R.string.mcp_category_gestures,
            listOf(
                ToolEntry("pinch", "Pinch"),
                ToolEntry("custom_gesture", "Custom Gesture"),
            ),
        ),
        ToolCategory(
            R.string.mcp_category_node_actions,
            listOf(
                ToolEntry("find_nodes", "Find Nodes"),
                ToolEntry("click_node", "Click Node"),
                ToolEntry("long_click_node", "Long Click Node"),
                ToolEntry("tap_node", "Tap Node"),
                ToolEntry("scroll_to_node", "Scroll to Node"),
            ),
        ),
        ToolCategory(
            R.string.mcp_category_text_input,
            listOf(
                ToolEntry("type_append_text", "Type Append Text"),
                ToolEntry("type_insert_text", "Type Insert Text"),
                ToolEntry("type_replace_text", "Type Replace Text"),
                ToolEntry("type_clear_text", "Type Clear Text"),
                ToolEntry("press_key", "Press Key"),
            ),
        ),
        ToolCategory(
            R.string.mcp_category_utility,
            listOf(
                ToolEntry("get_clipboard", "Get Clipboard"),
                ToolEntry("set_clipboard", "Set Clipboard"),
                ToolEntry("wait_for_node", "Wait for Node"),
                ToolEntry("wait_for_idle", "Wait for Idle"),
                ToolEntry("get_node_details", "Get Node Details"),
            ),
        ),
        ToolCategory(
            R.string.mcp_category_file_operations,
            listOf(
                ToolEntry("list_storage_locations", "List Storage Locations"),
                ToolEntry("list_files", "List Files"),
                ToolEntry("read_file", "Read File"),
                ToolEntry("write_file", "Write File"),
                ToolEntry("append_file", "Append File"),
                ToolEntry("file_replace", "File Replace"),
                ToolEntry("download_from_url", "Download from URL"),
                ToolEntry("delete_file", "Delete File"),
                ToolEntry("move_file", "Move File"),
                ToolEntry("disk_usage", "Disk Usage"),
            ),
        ),
        ToolCategory(
            R.string.mcp_category_quarantine,
            listOf(
                ToolEntry("quarantine_files", "Quarantine Files"),
                ToolEntry("list_quarantine_batches", "List Quarantine Batches"),
                ToolEntry("restore_quarantine_batch", "Restore Quarantine Batch"),
                ToolEntry("purge_quarantine_batch", "Purge Quarantine Batch"),
            ),
        ),
        ToolCategory(
            R.string.mcp_category_app_management,
            listOf(
                ToolEntry("open_app", "Open App"),
                ToolEntry("list_apps", "List Apps"),
                ToolEntry("close_app", "Close App"),
            ),
        ),
        ToolCategory(
            R.string.mcp_category_camera,
            listOf(
                ToolEntry("list_cameras", "List Cameras"),
                ToolEntry("list_camera_photo_resolutions", "List Camera Photo Resolutions"),
                ToolEntry("list_camera_video_resolutions", "List Camera Video Resolutions"),
                ToolEntry("take_camera_photo", "Take Camera Photo"),
                ToolEntry("save_camera_photo", "Save Camera Photo"),
                ToolEntry(
                    "save_camera_video",
                    "Save Camera Video",
                    listOf(ParamEntry("audio", "Include audio")),
                ),
            ),
        ),
        ToolCategory(
            R.string.mcp_category_intent,
            listOf(
                ToolEntry("send_intent", "Send Intent"),
                ToolEntry("open_uri", "Open URI"),
            ),
        ),
        ToolCategory(
            R.string.mcp_category_notifications,
            listOf(
                ToolEntry("notification_list", "Notification List"),
                ToolEntry("notification_open", "Notification Open"),
                ToolEntry("notification_dismiss", "Notification Dismiss"),
                ToolEntry("notification_snooze", "Notification Snooze"),
                ToolEntry("notification_action", "Notification Action"),
                ToolEntry("notification_reply", "Notification Reply"),
            ),
        ),
        ToolCategory(
            R.string.mcp_category_location,
            listOf(
                ToolEntry(
                    "get_location",
                    "Get Location",
                    listOf(ParamEntry("fresh_fix", "Allow fresh GPS fix")),
                ),
            ),
        ),
        ToolCategory(
            R.string.mcp_category_sharing,
            listOf(
                ToolEntry("get_shared_content", "Get Shared Content"),
                ToolEntry("share_file_via_web", "Share File via Web"),
            ),
        ),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpToolsSettingsScreen(
    onBack: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val serverStatus by viewModel.serverStatus.collectAsStateWithLifecycle()
    val perms by viewModel.toolPermissionsConfig.collectAsStateWithLifecycle()
    val cameraGranted by viewModel.isCameraPermissionGranted.collectAsStateWithLifecycle()
    val locationGranted by viewModel.isLocationPermissionGranted.collectAsStateWithLifecycle()
    val notificationListenerGranted by viewModel.isNotificationListenerEnabled.collectAsStateWithLifecycle()
    val microphoneGranted by viewModel.isMicrophonePermissionGranted.collectAsStateWithLifecycle()
    val controlsEnabled = serverStatus !is ServerStatus.Running && serverStatus !is ServerStatus.Starting

    // Refresh permissions on ON_RESUME — SAME pattern as PermissionsSettingsScreen.
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    viewModel.refreshPermissionStatus(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isGranted: (OptionalToolPermission) -> Boolean = { permission ->
        isPermissionGranted(
            permission = permission,
            camera = cameraGranted,
            location = locationGranted,
            notificationListener = notificationListenerGranted,
            microphone = microphoneGranted,
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_mcp_tools_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
            windowInsets = WindowInsets(0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionIntro(
                        stringResource(R.string.mcp_tools_restart_hint),
                        modifier = Modifier.weight(1f),
                    )
                    HelpHint(
                        HelpText(
                            stringResource(R.string.settings_mcp_tools_title),
                            stringResource(R.string.help_mcp_tool),
                        ),
                    )
                }
            }
            // One item per category, not per tool: a category is at most ten rows, and keeping it
            // whole is what lets it be drawn as a single panel.
            items(ALL_TOOL_CATEGORIES, key = { it.headerRes }) { category ->
                val categoryPermissions =
                    category.tools.mapNotNull { OptionalToolPermissions.permissionForTool(it.toolName) }.distinct()
                Column {
                    ToolCategoryHeader(
                        header = stringResource(category.headerRes),
                        missingPermission = categoryPermissions.any { !isGranted(it) },
                        onNavigateToPermissions = onNavigateToPermissions,
                    )
                    DashboardPanel {
                        Column {
                            category.tools.forEachIndexed { index, tool ->
                                ToolRow(
                                    tool = tool,
                                    perms = perms,
                                    controlsEnabled = controlsEnabled,
                                    categoryGranted = categoryPermissions.all { isGranted(it) },
                                    isGranted = isGranted,
                                    onNavigateToPermissions = onNavigateToPermissions,
                                    onToolToggle = viewModel::updateToolEnabled,
                                    onParamToggle = viewModel::updateParamEnabled,
                                    showDivider = index < category.tools.lastIndex,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Resolves whether an optional permission is granted from the individual permission flags. */
private fun isPermissionGranted(
    permission: OptionalToolPermission,
    camera: Boolean,
    location: Boolean,
    notificationListener: Boolean,
    microphone: Boolean,
): Boolean =
    when (permission) {
        OptionalToolPermission.CAMERA -> camera
        OptionalToolPermission.LOCATION -> location
        OptionalToolPermission.NOTIFICATION_LISTENER -> notificationListener
        OptionalToolPermission.MICROPHONE -> microphone
    }

@Composable
private fun ToolCategoryHeader(
    header: String,
    missingPermission: Boolean,
    onNavigateToPermissions: () -> Unit,
) {
    if (!missingPermission) {
        TileLabel(header, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        return
    }
    Column(
        modifier =
            Modifier
                .clickable { onNavigateToPermissions() }
                .padding(start = 4.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TileLabel(header)
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = WarningAmber,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.settings_mcp_tools_missing_permission),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToolRow(
    tool: ToolEntry,
    perms: ToolPermissionsConfig,
    controlsEnabled: Boolean,
    categoryGranted: Boolean,
    isGranted: (OptionalToolPermission) -> Boolean,
    onNavigateToPermissions: () -> Unit,
    onToolToggle: (String, Boolean) -> Unit,
    onParamToggle: (String, String, Boolean) -> Unit,
    showDivider: Boolean = false,
) {
    val toolEnabled = perms.isToolEnabled(tool.toolName)
    ListItem(
        headlineContent = { Text(tool.displayName) },
        trailingContent = {
            Switch(
                checked = toolEnabled,
                onCheckedChange = { onToolToggle(tool.toolName, it) },
                enabled = controlsEnabled && categoryGranted,
            )
        },
    )
    if (toolEnabled) {
        tool.params.forEach { param ->
            ToolParamRow(
                toolName = tool.toolName,
                param = param,
                perms = perms,
                controlsEnabled = controlsEnabled,
                categoryGranted = categoryGranted,
                isGranted = isGranted,
                onNavigateToPermissions = onNavigateToPermissions,
                onParamToggle = onParamToggle,
            )
        }
    }
    if (showDivider) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ToolParamRow(
    toolName: String,
    param: ParamEntry,
    perms: ToolPermissionsConfig,
    controlsEnabled: Boolean,
    categoryGranted: Boolean,
    isGranted: (OptionalToolPermission) -> Boolean,
    onNavigateToPermissions: () -> Unit,
    onParamToggle: (String, String, Boolean) -> Unit,
) {
    val paramPermission = OptionalToolPermissions.permissionForParam(toolName, param.paramName)
    val paramGranted = paramPermission == null || isGranted(paramPermission)
    val showParamNote = categoryGranted && !paramGranted
    val leadingIcon: (@Composable () -> Unit)? =
        if (showParamNote) {
            { Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = WarningAmber) }
        } else {
            null
        }
    val supportingNote: (@Composable () -> Unit)? =
        if (showParamNote) {
            {
                Text(
                    text = stringResource(R.string.settings_mcp_tools_param_missing_permission),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            null
        }
    ListItem(
        headlineContent = { Text(param.displayName) },
        modifier =
            if (showParamNote) {
                Modifier.padding(start = 32.dp).clickable { onNavigateToPermissions() }
            } else {
                Modifier.padding(start = 32.dp)
            },
        leadingContent = leadingIcon,
        supportingContent = supportingNote,
        trailingContent = {
            Switch(
                checked = perms.isParamEnabled(toolName, param.paramName),
                onCheckedChange = { onParamToggle(toolName, param.paramName, it) },
                enabled = controlsEnabled && categoryGranted && paramGranted,
            )
        },
    )
}
