package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Ordering is the whole point of consolidating the alerts into one panel: the row a user reads
 * first has to be the one that matters most.
 */
@DisplayName("sortAlerts")
class AttentionPanelTest {
    private fun alert(
        id: String,
        severity: AlertSeverity,
    ) = DashboardAlert(id = id, icon = Icons.Default.Warning, title = id, severity = severity)

    @Test
    fun `most severe first`() {
        val sorted =
            sortAlerts(
                listOf(
                    alert("info", AlertSeverity.INFO),
                    alert("critical", AlertSeverity.CRITICAL),
                    alert("warning", AlertSeverity.WARNING),
                ),
            )

        assertEquals(listOf("critical", "warning", "info"), sorted.map { it.id })
    }

    @Test
    fun `equal severities keep the caller's order`() {
        // The caller's order encodes its own urgency ranking, and a panel that reshuffles on every
        // recomposition moves rows out from under the user's finger.
        val sorted =
            sortAlerts(
                listOf(
                    alert("first", AlertSeverity.WARNING),
                    alert("second", AlertSeverity.WARNING),
                    alert("third", AlertSeverity.WARNING),
                ),
            )

        assertEquals(listOf("first", "second", "third"), sorted.map { it.id })
    }

    @Test
    fun `an empty list stays empty`() {
        assertEquals(emptyList<DashboardAlert>(), sortAlerts(emptyList()))
    }
}
