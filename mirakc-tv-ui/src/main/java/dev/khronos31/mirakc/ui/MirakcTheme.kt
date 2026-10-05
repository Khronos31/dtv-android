package dev.khronos31.mirakc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * Design tokens and colors for the mirakc Android TV interface.
 * Designed for 10-foot living room legibility, high contrast, and unmistakable D-pad focus.
 */
object MirakcThemeTokens {
    // Deep TV dark background and surfaces
    val Background = Color(0xFF0E131C)
    val Surface = Color(0xFF161D29)
    val SurfaceElevated = Color(0xFF1F293A)
    val SurfaceFocused = Color(0xFF2B384E)

    // Primary accents (luminous cyan-blue)
    val Primary = Color(0xFF4DA6FF)
    val OnPrimary = Color(0xFF001E3C)
    val PrimaryContainer = Color(0xFF16375A)
    val OnPrimaryContainer = Color(0xFFCCE5FF)

    // Secondary accents (cool slate)
    val Secondary = Color(0xFF8FA5C2)
    val OnSecondary = Color(0xFF0A1826)
    val SecondaryContainer = Color(0xFF243346)
    val OnSecondaryContainer = Color(0xFFD6E4F5)

    // D-pad focus stroke and glow
    val FocusStroke = Color(0xFF5AB6FF)
    val BorderSubtle = Color(0xFF283447)

    // High-legibility text colors
    val OnSurface = Color(0xFFF1F5F9)
    val OnSurfaceSecondary = Color(0xFFA0B0C4)
    val OnSurfaceMuted = Color(0xFF6B7D95)

    // Semantic status colors
    val StatusRunning = Color(0xFF48BB78)     // Green for active server / granted USB
    val StatusRunningBg = Color(0xFF12301F)
    val StatusWaiting = Color(0xFFECC94B)     // Yellow/Amber for permission / waiting
    val StatusWaitingBg = Color(0xFF382F12)
    val StatusWarning = Color(0xFFECC94B)     // Amber for warnings
    val StatusWarningBg = Color(0xFF382F12)
    val StatusScanning = Color(0xFF4299E1)    // Blue for scan in-flight
    val StatusScanningBg = Color(0xFF10283E)
    val StatusError = Color(0xFFF56565)       // Red for error / failure / cancel
    val StatusErrorBg = Color(0xFF3D1515)
    val StatusIdle = Color(0xFF718096)        // Slate for idle / stopped
    val StatusIdleBg = Color(0xFF202731)

    // Dimensions
    val CardCornerRadius = 12.dp
    val ButtonCornerRadius = 8.dp
}

@Composable
fun MirakcTvTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = MirakcThemeTokens.Primary,
        onPrimary = MirakcThemeTokens.OnPrimary,
        primaryContainer = MirakcThemeTokens.PrimaryContainer,
        onPrimaryContainer = MirakcThemeTokens.OnPrimaryContainer,
        secondary = MirakcThemeTokens.Secondary,
        onSecondary = MirakcThemeTokens.OnSecondary,
        secondaryContainer = MirakcThemeTokens.SecondaryContainer,
        onSecondaryContainer = MirakcThemeTokens.OnSecondaryContainer,
        background = MirakcThemeTokens.Background,
        onBackground = MirakcThemeTokens.OnSurface,
        surface = MirakcThemeTokens.Surface,
        onSurface = MirakcThemeTokens.OnSurface,
        surfaceVariant = MirakcThemeTokens.SurfaceElevated,
        onSurfaceVariant = MirakcThemeTokens.OnSurfaceSecondary,
        border = MirakcThemeTokens.BorderSubtle,
        borderVariant = MirakcThemeTokens.FocusStroke,
        error = MirakcThemeTokens.StatusError,
        onError = Color.White,
        errorContainer = MirakcThemeTokens.StatusErrorBg,
        onErrorContainer = Color(0xFFFFD2D2)
    )

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
