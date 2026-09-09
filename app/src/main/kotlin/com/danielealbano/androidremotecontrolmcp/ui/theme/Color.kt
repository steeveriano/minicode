@file:Suppress("MagicNumber")

package com.danielealbano.androidremotecontrolmcp.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * A deliberate palette rather than the wallpaper's.
 *
 * The app previously ran on dynamic colour, so its identity was whatever Material You derived from
 * the user's wallpaper — which is why it looked like nothing in particular. This palette is fixed
 * and anchored on the launcher icon's accent, so the icon, the running indicator and the primary
 * action are all the same green, and the app reads as one thing.
 *
 * Dark is the reference design: this is a console for a device that is doing work, and the accent
 * only carries at that intensity against a near-black ground. The light scheme keeps the same
 * hues and darkens the accent to hold contrast on a pale surface.
 */

/** The launcher icon's accent. Used for the running state and the primary action. */
val AccentGreen = Color(0xFF00E676)

/** Same hue, darkened enough to sit on a light surface without vibrating. */
val AccentGreenDeep = Color(0xFF00894A)

// ── Dark (reference) ─────────────────────────────────────────────────────────
val PrimaryDark = AccentGreen
val OnPrimaryDark = Color(0xFF00210F)
val PrimaryContainerDark = Color(0xFF0C3D25)
val OnPrimaryContainerDark = Color(0xFF8CFFC0)

val SecondaryDark = Color(0xFF9FC7B0)
val OnSecondaryDark = Color(0xFF0B2318)
val SecondaryContainerDark = Color(0xFF1B3A2A)
val OnSecondaryContainerDark = Color(0xFFC4E8D3)

val TertiaryDark = Color(0xFF7FD3E8)
val OnTertiaryDark = Color(0xFF00212B)
val TertiaryContainerDark = Color(0xFF0D3A46)
val OnTertiaryContainerDark = Color(0xFFB8ECFA)

val ErrorDark = Color(0xFFFF6B6B)
val OnErrorDark = Color(0xFF3A0000)
val ErrorContainerDark = Color(0xFF5C1414)
val OnErrorContainerDark = Color(0xFFFFDAD6)

/** Near-black with a faint green cast, so the accent reads as belonging to the surface. */
val SurfaceDark = Color(0xFF0A0F0C)
val OnSurfaceDark = Color(0xFFE4EDE7)
val SurfaceVariantDark = Color(0xFF161E1A)
val OnSurfaceVariantDark = Color(0xFF93A69B)
val OutlineDark = Color(0xFF2B3A31)
val OutlineVariantDark = Color(0xFF1E2A23)

// ── Light ────────────────────────────────────────────────────────────────────
val PrimaryLight = AccentGreenDeep
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFB6F5D2)
val OnPrimaryContainerLight = Color(0xFF002313)

val SecondaryLight = Color(0xFF3E6552)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFC4E8D3)
val OnSecondaryContainerLight = Color(0xFF002012)

val TertiaryLight = Color(0xFF00697F)
val OnTertiaryLight = Color(0xFFFFFFFF)
val TertiaryContainerLight = Color(0xFFB8ECFA)
val OnTertiaryContainerLight = Color(0xFF001F27)

val ErrorLight = Color(0xFFB3261E)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFF9DEDC)
val OnErrorContainerLight = Color(0xFF410E0B)

val SurfaceLight = Color(0xFFF6F9F7)
val OnSurfaceLight = Color(0xFF0A0F0C)
val SurfaceVariantLight = Color(0xFFE3EAE5)
val OnSurfaceVariantLight = Color(0xFF44534A)
val OutlineLight = Color(0xFF748076)
val OutlineVariantLight = Color(0xFFC7D2CA)

/** Amber used for advisory warnings (yellow triangle). ARGB 0xFFF9A825. */
val WarningAmber = Color(0xFFF9A825)

val LightColorScheme =
    lightColorScheme(
        primary = PrimaryLight,
        onPrimary = OnPrimaryLight,
        primaryContainer = PrimaryContainerLight,
        onPrimaryContainer = OnPrimaryContainerLight,
        secondary = SecondaryLight,
        onSecondary = OnSecondaryLight,
        secondaryContainer = SecondaryContainerLight,
        onSecondaryContainer = OnSecondaryContainerLight,
        tertiary = TertiaryLight,
        onTertiary = OnTertiaryLight,
        tertiaryContainer = TertiaryContainerLight,
        onTertiaryContainer = OnTertiaryContainerLight,
        error = ErrorLight,
        onError = OnErrorLight,
        errorContainer = ErrorContainerLight,
        onErrorContainer = OnErrorContainerLight,
        background = SurfaceLight,
        onBackground = OnSurfaceLight,
        surface = SurfaceLight,
        onSurface = OnSurfaceLight,
        surfaceVariant = SurfaceVariantLight,
        onSurfaceVariant = OnSurfaceVariantLight,
        outline = OutlineLight,
        outlineVariant = OutlineVariantLight,
    )

val DarkColorScheme =
    darkColorScheme(
        primary = PrimaryDark,
        onPrimary = OnPrimaryDark,
        primaryContainer = PrimaryContainerDark,
        onPrimaryContainer = OnPrimaryContainerDark,
        secondary = SecondaryDark,
        onSecondary = OnSecondaryDark,
        secondaryContainer = SecondaryContainerDark,
        onSecondaryContainer = OnSecondaryContainerDark,
        tertiary = TertiaryDark,
        onTertiary = OnTertiaryDark,
        tertiaryContainer = TertiaryContainerDark,
        onTertiaryContainer = OnTertiaryContainerDark,
        error = ErrorDark,
        onError = OnErrorDark,
        errorContainer = ErrorContainerDark,
        onErrorContainer = OnErrorContainerDark,
        // Scaffold paints `background`; without it the screen keeps Material's default dark grey
        // behind these near-black panels and the whole design reads as mismatched.
        background = SurfaceDark,
        onBackground = OnSurfaceDark,
        surface = SurfaceDark,
        onSurface = OnSurfaceDark,
        surfaceVariant = SurfaceVariantDark,
        onSurfaceVariant = OnSurfaceVariantDark,
        outline = OutlineDark,
        outlineVariant = OutlineVariantDark,
    )
