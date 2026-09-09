@file:Suppress("FunctionNaming", "MagicNumber")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Shared building blocks for the dashboard.
 *
 * The look is deliberately flat and outlined rather than elevated: stacked Material cards with
 * shadows read as a settings list, and the screen's job is to report state at a glance. Numbers are
 * monospaced so a row of tiles keeps its columns as values change, which is the difference between
 * a dashboard and a list of labels.
 */

/** Radius shared by every panel on the dashboard, so the surfaces read as one system. */
internal val PanelShape = RoundedCornerShape(18.dp)

/** Uppercase, widely tracked, small: the label style that lets the value carry the tile. */
@Composable
internal fun TileLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        style =
            MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Medium,
            ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier,
    )
}

/** An outlined panel. Every block on the dashboard sits in one of these. */
@Composable
internal fun DashboardPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = PanelShape,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        content()
    }
}

/**
 * A status dot with a halo.
 *
 * The halo is the same colour at low alpha rather than a shadow, so the indicator stays legible
 * against the near-black surface and does not depend on elevation the flat design does not use.
 */
@Composable
internal fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Int = 10,
) {
    Box(
        modifier = modifier.size((size * 2.4).dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size((size * 2.4).dp)
                .clip(RoundedCornerShape(50))
                .background(color.copy(alpha = 0.16f)),
        )
        Box(
            Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(50))
                .background(color),
        )
    }
}

/**
 * One cell of the overview row: a value in monospace over a small label.
 *
 * [valueColor] carries the meaning — the value itself stays short enough to read at a glance, so a
 * tile never becomes a sentence.
 */
@Composable
internal fun RowScope.MetricTile(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 12.dp)
                // One announcement per tile; the two texts on their own read as fragments.
                .clearAndSetSemantics { contentDescription = "$label: $value" },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = value,
            style =
                MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                ),
            color = valueColor,
            maxLines = 1,
        )
        TileLabel(label)
    }
}

/** The overview strip: a heading and a row of tiles inside one panel. */
@Composable
internal fun MetricRow(
    heading: String,
    modifier: Modifier = Modifier,
    tiles: @Composable RowScope.() -> Unit,
) {
    DashboardPanel(modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            TileLabel(heading)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tiles()
            }
        }
    }
}

/** Fixed-width spacer used between inline elements, so callers do not repeat the number. */
@Composable
internal fun InlineGap(width: Int = 10) {
    Spacer(Modifier.width(width.dp))
}
