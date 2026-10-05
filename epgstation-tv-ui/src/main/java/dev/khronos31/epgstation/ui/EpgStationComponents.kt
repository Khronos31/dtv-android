package dev.khronos31.epgstation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import dev.khronos31.epgstation.ui.contract.EpgStationBaseUrlPolicy
import dev.khronos31.epgstation.ui.contract.RecordingVolumeUi
import java.util.Locale

/**
 * Reusable card surface for Android TV with comfortable living-room spacing and contrast.
 * Compact padding and spacing ensure the layout fits comfortably on 1080p displays.
 */
@Composable
fun TvCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = EpgStationThemeTokens.Surface,
    borderColor: Color = EpgStationThemeTokens.BorderSubtle,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor, RoundedCornerShape(EpgStationThemeTokens.CardCornerRadius))
            .border(1.dp, borderColor, RoundedCornerShape(EpgStationThemeTokens.CardCornerRadius))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

/**
 * Focusable card tailored for TV remote navigation.
 * Renders high-contrast focus highlight when focused, and maintains a single stable focus node.
 * Preserves focus across selection state changes without duplicate focusable nodes.
 */
@Composable
fun TvFocusableCard(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isSelected: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    val baseBg = if (isSelected) EpgStationThemeTokens.PrimaryContainer.copy(alpha = 0.4f) else EpgStationThemeTokens.SurfaceElevated
    val bg = when {
        isFocused -> EpgStationThemeTokens.SurfaceFocused
        else -> baseBg
    }
    val borderCol = when {
        isFocused -> EpgStationThemeTokens.FocusStroke
        isSelected -> EpgStationThemeTokens.Primary.copy(alpha = 0.8f)
        else -> EpgStationThemeTokens.BorderSubtle
    }
    val borderWidth = if (isFocused) 2.dp else if (isSelected) 1.5.dp else 1.dp

    val cardModifier = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(EpgStationThemeTokens.CardCornerRadius))
        .onFocusChanged { isFocused = it.isFocused }
        .clickable(
            enabled = enabled,
            onClick = {
                if (enabled && !isSelected && onClick != null) {
                    onClick()
                }
            }
        )
        .background(bg, RoundedCornerShape(EpgStationThemeTokens.CardCornerRadius))
        .border(borderWidth, borderCol, RoundedCornerShape(EpgStationThemeTokens.CardCornerRadius))
        .padding(horizontal = 14.dp, vertical = 10.dp)

    Column(
        modifier = cardModifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content
    )
}

/**
 * Action button tailored for TV remote control.
 * Has crisp focus outline, 1.03x focus scale, and distinct primary/destructive variants.
 * Strictly guarantees that disabled controls never dispatch actions.
 * When [isBusy] is true, the button maintains focusability and focus border
 * so focus never drops on TV remotes, while preventing duplicate dispatches.
 */
@Composable
fun TvActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isBusy: Boolean = false,
    isPrimary: Boolean = false,
    isDestructive: Boolean = false
) {
    val containerColor = when {
        isBusy -> EpgStationThemeTokens.SurfaceElevated.copy(alpha = 0.7f)
        isPrimary -> EpgStationThemeTokens.PrimaryContainer
        isDestructive -> EpgStationThemeTokens.StatusErrorBg
        else -> EpgStationThemeTokens.SurfaceElevated
    }
    val contentColor = when {
        isBusy -> EpgStationThemeTokens.OnSurfaceMuted
        isPrimary -> EpgStationThemeTokens.OnPrimaryContainer
        isDestructive -> EpgStationThemeTokens.StatusError
        else -> EpgStationThemeTokens.OnSurface
    }
    val focusedContainerColor = when {
        isBusy -> EpgStationThemeTokens.SurfaceFocused
        isPrimary -> EpgStationThemeTokens.Primary
        isDestructive -> EpgStationThemeTokens.StatusError
        else -> EpgStationThemeTokens.SurfaceFocused
    }
    val focusedContentColor = when {
        isBusy -> EpgStationThemeTokens.OnSurface
        isPrimary -> EpgStationThemeTokens.OnPrimary
        isDestructive -> Color.White
        else -> Color.White
    }
    val focusedBorderColor = if (isDestructive) Color.White else EpgStationThemeTokens.FocusStroke

    Button(
        onClick = {
            if (enabled && !isBusy) {
                onClick()
            }
        },
        enabled = enabled,
        modifier = modifier,
        shape = ButtonDefaults.shape(
            shape = RoundedCornerShape(EpgStationThemeTokens.ButtonCornerRadius),
            focusedShape = RoundedCornerShape(EpgStationThemeTokens.ButtonCornerRadius)
        ),
        colors = ButtonDefaults.colors(
            containerColor = containerColor,
            contentColor = contentColor,
            focusedContainerColor = focusedContainerColor,
            focusedContentColor = focusedContentColor,
            disabledContainerColor = EpgStationThemeTokens.Surface.copy(alpha = 0.5f),
            disabledContentColor = EpgStationThemeTokens.OnSurfaceMuted
        ),
        scale = ButtonDefaults.scale(scale = 1f, focusedScale = 1.03f),
        border = ButtonDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, EpgStationThemeTokens.BorderSubtle),
                shape = RoundedCornerShape(EpgStationThemeTokens.ButtonCornerRadius)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.5.dp, focusedBorderColor),
                shape = RoundedCornerShape(EpgStationThemeTokens.ButtonCornerRadius)
            )
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isPrimary) FontWeight.Bold else FontWeight.Medium
        )
    }
}

/**
 * Chip indicating status with color coding and natural Japanese labels.
 */
@Composable
fun TvBadge(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = EpgStationThemeTokens.OnPrimaryContainer,
    backgroundColor: Color = EpgStationThemeTokens.PrimaryContainer
) {
    Box(
        modifier = modifier
            .background(backgroundColor, RoundedCornerShape(4.dp))
            .border(1.dp, textColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = textColor,
            fontSize = 11.sp
        )
    }
}

/**
 * Formats storage capacity in Japanese directly from typed freeBytes and totalBytes.
 * Strictly avoids any parsing or manipulation of host detail strings.
 */
fun formatStorageCapacityJapanese(freeBytes: Long?, totalBytes: Long?): String {
    if (freeBytes == null || totalBytes == null || totalBytes <= 0L) {
        return "容量情報なし"
    }
    val freeStr = formatByteSize(freeBytes)
    val totalStr = formatByteSize(totalBytes)
    val freePercentage = ((freeBytes.toDouble() / totalBytes.toDouble()) * 100).toInt()
    return "空き $freeStr / 全体 $totalStr (${freePercentage}% 空き)"
}

/**
 * Formats a byte quantity into human-readable Japanese binary units (TB, GB, MB, KB).
 */
fun formatByteSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val tb = 1024L * 1024L * 1024L * 1024L
    val gb = 1024L * 1024L * 1024L
    val mb = 1024L * 1024L
    return when {
        bytes >= tb -> String.format(Locale.JAPAN, "%.1f TB", bytes.toDouble() / tb)
        bytes >= gb -> String.format(Locale.JAPAN, "%.1f GB", bytes.toDouble() / gb)
        bytes >= mb -> String.format(Locale.JAPAN, "%.1f MB", bytes.toDouble() / mb)
        else -> String.format(Locale.JAPAN, "%d KB", bytes / 1024L)
    }
}

/**
 * Formats the volume title in natural Japanese.
 * Converts internal storage representations into "内部ストレージ" based on typed id/removable,
 * while preserving custom titles of external/removable storage.
 */
fun formatVolumeTitle(volume: RecordingVolumeUi): String {
    return if (volume.id == "internal" || !volume.removable) {
        "内部ストレージ"
    } else {
        volume.title
    }
}

/**
 * Maps contract URL validation error to a natural Japanese guidance message.
 * Strictly avoids guessing server state or save success from strings.
 */
fun formatBaseUrlValidationError(error: String?): String? {
    if (error == null) return null
    return if (error == EpgStationBaseUrlPolicy.ERROR || error.contains("absolute http", ignoreCase = true)) {
        "http:// または https:// から始まる絶対URLを入力してください"
    } else {
        error
    }
}

/**
 * Focusable, non-clickable row used for About version information and repository URLs.
 * Navigable via TV D-pad with clear focus indicator, but never executes any action on click.
 */
@Composable
fun TvFocusableInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onDirectionUp: (() -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .then(
                if (onDirectionUp != null) {
                    Modifier.onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                            onDirectionUp()
                            true
                        } else false
                    }
                } else Modifier
            )
            .focusable()
            .background(
                color = if (isFocused) EpgStationThemeTokens.SurfaceFocused else EpgStationThemeTokens.SurfaceElevated,
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) EpgStationThemeTokens.FocusStroke else EpgStationThemeTokens.BorderSubtle,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = if (isFocused) EpgStationThemeTokens.Primary else EpgStationThemeTokens.OnSurfaceSecondary,
            modifier = Modifier.width(160.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = if (value.startsWith("http")) FontFamily.Monospace else FontFamily.Default
            ),
            color = EpgStationThemeTokens.OnSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Chunks a large text into line groups to prevent Skia/Compose text measurement overhead.
 * Crucial for large notice files like Android NDK NOTICE (~806KB).
 */
fun chunkLicenseText(text: String, linesPerChunk: Int = 40): List<String> {
    val lines = text.lines()
    if (lines.isEmpty()) return emptyList()
    val chunks = ArrayList<String>(lines.size / linesPerChunk + 1)
    val sb = java.lang.StringBuilder()
    var count = 0
    for (line in lines) {
        sb.append(line).append('\n')
        count++
        if (count >= linesPerChunk) {
            chunks.add(sb.toString().trimEnd('\n'))
            sb.setLength(0)
            count = 0
        }
    }
    if (sb.isNotEmpty()) {
        chunks.add(sb.toString().trimEnd('\n'))
    }
    return chunks
}


