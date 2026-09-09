@file:Suppress("FunctionNaming", "MagicNumber")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/*
 * The settings equivalent of the dashboard's panels, so the two halves of the app look like one
 * product. A flat list of Material `ListItem`s gave every entry the same weight and no shape; these
 * group related entries into the same outlined panel the server screen uses.
 */

/** A titled group of settings rows, drawn as one dashboard panel. */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    help: HelpText? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (help == null) {
            TileLabel(title, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        } else {
            LabelWithHelp(title, help, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        }
        DashboardPanel {
            Column {
                content()
            }
        }
    }
}

/**
 * One navigable settings entry.
 *
 * [showDivider] is the caller's, not the row's: only the caller knows whether another row follows,
 * and a trailing rule under the last entry is the detail that makes a grouped panel look unfinished.
 */
@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
) {
    Column(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    // 48dp is the minimum touch target; the padding alone does not guarantee it
                    // once the subtitle wraps to one line on a narrow screen.
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            InlineGap(14)
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            InlineGap(8)
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 48.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/**
 * A paragraph of section context, sitting between a [SettingsSection] title and its panel.
 *
 * Kept out of the panel: it explains the group rather than being one of its rows, and putting it
 * inside made the first row look like a continuation of the sentence above it.
 */
@Composable
fun SectionIntro(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
    )
}

/**
 * A setting that is on or off, as a full-width row.
 *
 * The whole row toggles, not just the switch: a 32dp target at the far edge of a phone screen is
 * the hardest thing on these screens to hit, and every screen had reimplemented this pairing
 * slightly differently.
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    showDivider: Boolean = false,
    help: HelpText? = null,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .toggleable(
                            value = checked,
                            enabled = enabled,
                            role = Role.Switch,
                            onValueChange = onCheckedChange,
                        ).padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // The label toggles the setting; the "?" must not. Keeping them as separate targets in
            // one row is the point — a user who does not understand a setting taps to find out,
            // and would otherwise have flipped it instead.
            if (help != null) {
                HelpHint(help)
            }
            InlineGap(4)
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                modifier = Modifier.padding(end = 14.dp),
            )
        }
        if (showDivider) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/**
 * The compact write/delete permission pair shown under a storage location.
 *
 * Four copies of this existed, two of them character-for-character identical.
 */
@Composable
@Suppress("LongParameterList")
fun PermissionTogglePair(
    firstLabel: String,
    firstChecked: Boolean,
    onFirstChange: (Boolean) -> Unit,
    secondLabel: String,
    secondChecked: Boolean,
    onSecondChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    firstHelp: HelpText? = null,
    secondHelp: HelpText? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CompactToggle(firstLabel, firstChecked, onFirstChange, firstHelp)
        CompactToggle(secondLabel, secondChecked, onSecondChange, secondHelp)
    }
}

@Composable
private fun CompactToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    help: HelpText?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier =
                Modifier
                    .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                    .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            InlineGap(6)
            Switch(checked = checked, onCheckedChange = null)
        }
        if (help != null) {
            HelpHint(help)
        }
    }
}
