@file:Suppress("FunctionNaming", "MagicNumber")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R

/**
 * A tappable "?" that explains one setting.
 *
 * The settings on these screens ask for a binding address, a device slug, a bearer token, a tunnel
 * token, extra cloudflared arguments — terms that mean nothing unless you already know the answer,
 * and getting them wrong either exposes the device or leaves it unreachable. The explanation lives
 * one tap away rather than as permanent supporting text, because a paragraph under every field is
 * how a settings screen becomes unreadable.
 */
@Composable
fun HelpHint(
    help: HelpText,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }

    IconButton(
        onClick = { open = true },
        // IconButton is 48dp; only the glyph shrinks, so the target stays reachable.
        modifier = modifier.size(32.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
            contentDescription = stringResource(R.string.help_what_is_this, help.title),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(help.title) },
            text = { Text(help.body) },
            confirmButton = {
                TextButton(onClick = { open = false }) {
                    Text(stringResource(R.string.help_dismiss))
                }
            },
        )
    }
}

/**
 * A field's label with its explanation beside it.
 *
 * For inputs whose own trailing icon is already spoken for, or whose label would otherwise be the
 * only place a user could look for an explanation.
 */
@Composable
fun LabelWithHelp(
    label: String,
    help: HelpText,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TileLabel(label)
        HelpHint(help)
    }
}
