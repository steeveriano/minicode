@file:Suppress("FunctionNaming", "MagicNumber")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** How loudly an alert should present itself, and the order alerts are shown in. */
enum class AlertSeverity {
    /** The device is exposed, or the thing the user asked for cannot work at all. */
    CRITICAL,

    /** Something will not behave as expected until it is handled. */
    WARNING,

    /** A suggestion. Nothing is broken. */
    INFO,
}

/** One row of [AttentionPanel]. */
data class DashboardAlert(
    val id: String,
    val icon: ImageVector,
    val title: String,
    val severity: AlertSeverity,
    val actions: List<AlertAction> = emptyList(),
)

data class AlertAction(
    val label: String,
    val onClick: () -> Unit,
)

/**
 * Every outstanding warning, in one panel, most serious first.
 *
 * These were five full-height cards stacked above the content: on a phone they pushed the server
 * state and the connection details off the first screen entirely, so the screen opened on a column
 * of problems and buried the thing it is for. Nothing is hidden here — the same alerts, as compact
 * rows — but they stop competing with the dashboard for the fold.
 */
@Composable
fun AttentionPanel(
    heading: String,
    alerts: List<DashboardAlert>,
    modifier: Modifier = Modifier,
) {
    if (alerts.isEmpty()) return

    DashboardPanel(modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            TileLabel(heading)
            Spacer(Modifier.height(6.dp))
            sortAlerts(alerts).forEachIndexed { index, alert ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                AlertRow(alert)
            }
        }
    }
}

/**
 * Most serious first, and stable within a severity.
 *
 * [List.sortedBy] is stable, so alerts of equal severity keep the caller's order; that order
 * encodes what the caller considers more urgent, and re-ordering it on every recomposition would
 * make the panel move under the user's finger.
 */
internal fun sortAlerts(alerts: List<DashboardAlert>): List<DashboardAlert> = alerts.sortedBy { it.severity.ordinal }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlertRow(alert: DashboardAlert) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = alert.icon,
                contentDescription = null,
                tint = severityTint(alert.severity),
                modifier = Modifier.size(18.dp),
            )
            InlineGap()
            Text(
                text = alert.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        if (alert.actions.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            ) {
                alert.actions.forEach { action ->
                    TextButton(onClick = action.onClick) { Text(action.label) }
                }
            }
        }
    }
}

@Composable
private fun severityTint(severity: AlertSeverity): Color =
    when (severity) {
        AlertSeverity.CRITICAL -> MaterialTheme.colorScheme.error
        AlertSeverity.WARNING -> com.danielealbano.androidremotecontrolmcp.ui.theme.WarningAmber
        AlertSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
