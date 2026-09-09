@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * @param dynamicColor Opt-in, and off by default. Material You derives a scheme from the user's
 *   wallpaper, which gave the app no identity of its own: the same screen was a different colour on
 *   every device, and the accent rarely matched the launcher icon. The fixed scheme in `Color.kt` is
 *   the design; this parameter exists so a caller can still ask for the system's.
 */
@Composable
fun AndroidRemoteControlMcpTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor -> {
                val context = LocalContext.current
                if (darkTheme) {
                    dynamicDarkColorScheme(context)
                } else {
                    dynamicLightColorScheme(context)
                }
            }

            darkTheme -> {
                DarkColorScheme
            }

            else -> {
                LightColorScheme
            }
        }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
