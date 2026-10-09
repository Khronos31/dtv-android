package dev.khronos31.mirakc.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.khronos31.mirakc.ui.contract.RuntimePhase

/**
 * Coordinated focus-relocation policy for TV containers (cards and bottom action rows).
 * Intercepts child focus requests via Compose's BringIntoViewResponder and expands
 * the target rectangle to include the enclosing container's full bounds, padding, border,
 * and safe bottom margin, preventing the child's default bringIntoView from clipping
 * card borders against the TV viewport bottom.
 *
 * When the container fits within the viewport, the entire container is brought into view.
 * When the container is taller than the viewport, the focused control and the bottom edge
 * are prioritized without losing accessibility to upper content.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.bringContainerIntoViewOnFocus(
    extraBottomMargin: Dp = 20.dp
): Modifier = composed {
    val density = LocalDensity.current
    val extraBottomMarginPx = with(density) { extraBottomMargin.toPx() }
    val configuration = LocalConfiguration.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    // Viewport height is screen height minus TV app padding (~80dp)
    val viewportHeightPx = maxOf(screenHeightPx - with(density) { 80.dp.toPx() }, with(density) { 360.dp.toPx() })
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    val responder = remember(extraBottomMarginPx, viewportHeightPx) {
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect {
                val containerHeight = containerSize.height.toFloat()
                val containerWidth = containerSize.width.toFloat()
                if (containerHeight <= 0f || containerWidth <= 0f) {
                    return localRect.copy(bottom = localRect.bottom + extraBottomMarginPx)
                }

                val bottomEdge = containerHeight + extraBottomMarginPx
                return if (bottomEdge <= viewportHeightPx) {
                    // Entire container fits in viewport: bring full card into view
                    Rect(
                        left = 0f,
                        top = 0f,
                        right = containerWidth,
                        bottom = bottomEdge
                    )
                } else {
                    // Container exceeds viewport: prioritize focused control and container bottom edge
                    val topEdge = maxOf(0f, minOf(localRect.top, bottomEdge - viewportHeightPx))
                    Rect(
                        left = 0f,
                        top = topEdge,
                        right = containerWidth,
                        bottom = bottomEdge
                    )
                }
            }

            override suspend fun bringChildIntoView(localRect: () -> Rect?) {
                // Outer verticalScroll handles the calculated rect relocation
            }
        }
    }

    this
        .onSizeChanged { containerSize = it }
        .bringIntoViewResponder(responder)
}

/**
 * Reusable card surface for Android TV with comfortable living-room spacing and contrast.
 * Automatically ensures full card visibility (including borders and padding) when any child receives focus.
 */
@Composable
fun TvCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = MirakcThemeTokens.Surface,
    borderColor: Color = MirakcThemeTokens.BorderSubtle,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .bringContainerIntoViewOnFocus()
            .background(backgroundColor, RoundedCornerShape(MirakcThemeTokens.CardCornerRadius))
            .border(1.dp, borderColor, RoundedCornerShape(MirakcThemeTokens.CardCornerRadius))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

/**
 * Focusable card that can be reached and highlighted by the TV D-pad to allow
 * navigating down through lower details and lists on the screen without performing any action on click.
 * Automatically ensures full card visibility (including borders and padding) when focused.
 */
@Composable
fun TvFocusableCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = MirakcThemeTokens.Surface,
    borderColor: Color = MirakcThemeTokens.BorderSubtle,
    content: @Composable ColumnScope.() -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .bringContainerIntoViewOnFocus()
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .background(
                color = if (isFocused) MirakcThemeTokens.SurfaceFocused else backgroundColor,
                shape = RoundedCornerShape(MirakcThemeTokens.CardCornerRadius)
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) MirakcThemeTokens.FocusStroke else borderColor,
                shape = RoundedCornerShape(MirakcThemeTokens.CardCornerRadius)
            )
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

/**
 * Message banner rendering host/backend validation messages or update results
 * with clean, neutral styling without assuming the message is an error.
 */
@Composable
fun TvMessageBanner(
    message: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MirakcThemeTokens.SurfaceElevated, RoundedCornerShape(8.dp))
            .border(1.dp, MirakcThemeTokens.FocusStroke.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(MirakcThemeTokens.PrimaryContainer, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "INFO",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MirakcThemeTokens.OnPrimaryContainer,
                    fontSize = 11.sp
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MirakcThemeTokens.OnSurface
            )
        }
    }
}


/**
 * Action button tailored for TV remote control.
 * Has crisp focus outline, 1.03x focus scale, and distinct primary/destructive variants.
 * Strictly guarantees that disabled controls never dispatch actions.
 */
@Composable
fun TvActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isPrimary: Boolean = false,
    isDestructive: Boolean = false
) {
    val containerColor = when {
        isPrimary -> MirakcThemeTokens.PrimaryContainer
        isDestructive -> MirakcThemeTokens.StatusErrorBg
        else -> MirakcThemeTokens.SurfaceElevated
    }
    val contentColor = when {
        isPrimary -> MirakcThemeTokens.OnPrimaryContainer
        isDestructive -> MirakcThemeTokens.StatusError
        else -> MirakcThemeTokens.OnSurface
    }
    val focusedContainerColor = when {
        isPrimary -> MirakcThemeTokens.Primary
        isDestructive -> MirakcThemeTokens.StatusError
        else -> MirakcThemeTokens.SurfaceFocused
    }
    val focusedContentColor = when {
        isPrimary -> MirakcThemeTokens.OnPrimary
        isDestructive -> Color.White
        else -> Color.White
    }
    val focusedBorderColor = if (isDestructive) Color.White else MirakcThemeTokens.FocusStroke

    // Keep pointer input alive across frequent state updates without capturing stale callbacks.
    val currentOnClick by rememberUpdatedState(onClick)
    val currentEnabled by rememberUpdatedState(enabled)

    Button(
        onClick = {
            if (enabled) {
                onClick()
            }
        },
        enabled = enabled,
        // TV Material handles remote Enter; add touch without changing TV focus/appearance.
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(onTap = {
                if (currentEnabled) currentOnClick()
            })
        },
        shape = ButtonDefaults.shape(
            shape = RoundedCornerShape(MirakcThemeTokens.ButtonCornerRadius),
            focusedShape = RoundedCornerShape(MirakcThemeTokens.ButtonCornerRadius)
        ),
        colors = ButtonDefaults.colors(
            containerColor = containerColor,
            contentColor = contentColor,
            focusedContainerColor = focusedContainerColor,
            focusedContentColor = focusedContentColor,
            disabledContainerColor = MirakcThemeTokens.Surface.copy(alpha = 0.5f),
            disabledContentColor = MirakcThemeTokens.OnSurfaceMuted
        ),
        scale = ButtonDefaults.scale(scale = 1f, focusedScale = 1.03f),
        border = ButtonDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, MirakcThemeTokens.BorderSubtle),
                shape = RoundedCornerShape(MirakcThemeTokens.ButtonCornerRadius)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.5.dp, focusedBorderColor),
                shape = RoundedCornerShape(MirakcThemeTokens.ButtonCornerRadius)
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
 * Focusable, non-clickable row used for About versions, repository info, and details.
 * Traversable via TV D-pad with clear focus indicator, but never executes any action on click.
 */
@Composable
fun TvFocusableInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .background(
                color = if (isFocused) MirakcThemeTokens.SurfaceFocused else MirakcThemeTokens.SurfaceElevated,
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) MirakcThemeTokens.FocusStroke else MirakcThemeTokens.BorderSubtle,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = if (isFocused) MirakcThemeTokens.Primary else MirakcThemeTokens.OnSurfaceSecondary,
            modifier = Modifier.width(190.dp)
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MirakcThemeTokens.OnSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Left navigation rail item with active indicator and sharp D-pad focus highlight.
 */
@Composable
fun TvNavRailItem(
    label: String,
    iconText: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .background(
                color = when {
                    isFocused -> MirakcThemeTokens.SurfaceFocused
                    selected -> MirakcThemeTokens.SurfaceElevated
                    else -> Color.Transparent
                }
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = when {
                    isFocused -> MirakcThemeTokens.FocusStroke
                    selected -> MirakcThemeTokens.BorderSubtle
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Vertical active indicator pill
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(18.dp)
                .background(
                    color = if (selected) MirakcThemeTokens.Primary else Color.Transparent,
                    shape = RoundedCornerShape(2.dp)
                )
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = iconText,
            style = MaterialTheme.typography.titleMedium,
            color = when {
                isFocused -> MirakcThemeTokens.FocusStroke
                selected -> MirakcThemeTokens.Primary
                else -> MirakcThemeTokens.OnSurfaceSecondary
            }
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = when {
                isFocused -> Color.White
                selected -> MirakcThemeTokens.OnSurface
                else -> MirakcThemeTokens.OnSurfaceSecondary
            }
        )
    }
}

/**
 * High-contrast TV progress bar with rounded ends and gradient fill.
 */
@Composable
fun TvProgressBar(
    completed: Int,
    total: Int,
    modifier: Modifier = Modifier
) {
    val fraction = if (total > 0) (completed.toFloat() / total).coerceIn(0f, 1f) else 0f
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .background(MirakcThemeTokens.SurfaceElevated, RoundedCornerShape(5.dp))
            .border(1.dp, MirakcThemeTokens.BorderSubtle, RoundedCornerShape(5.dp))
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(10.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            listOf(Color(0xFF2B82E4), Color(0xFF5AB6FF))
                        ),
                        shape = RoundedCornerShape(5.dp)
                    )
            )
        }
    }
}

/**
 * Chip indicating current runtime phase with color coding and natural Japanese labels.
 */
@Composable
fun PhaseBadge(phase: RuntimePhase, isPrepared: Boolean, modifier: Modifier = Modifier) {
    val (bgColor, textColor, label) = when (phase) {
        RuntimePhase.IDLE -> if (isPrepared) {
            Triple(Color(0xFF13363B), Color(0xFF4DD0E1), "準備完了 (待機中)")
        } else {
            Triple(MirakcThemeTokens.StatusIdleBg, MirakcThemeTokens.StatusIdle, "初期状態 (未設定)")
        }
        RuntimePhase.WAITING_FOR_PERMISSION -> Triple(
            MirakcThemeTokens.StatusWaitingBg,
            MirakcThemeTokens.StatusWaiting,
            "USB権限待機中"
        )
        RuntimePhase.PREPARING -> Triple(Color(0xFF2C194D), Color(0xFFBA68C8), "チューナー準備中")
        RuntimePhase.STARTING_SCAN -> Triple(
            MirakcThemeTokens.StatusScanningBg,
            MirakcThemeTokens.StatusScanning,
            "スキャン準備中"
        )
        RuntimePhase.SCANNING -> Triple(
            MirakcThemeTokens.StatusScanningBg,
            MirakcThemeTokens.StatusScanning,
            "スキャン実行中"
        )
        RuntimePhase.FINISHING_SCAN -> Triple(
            MirakcThemeTokens.StatusScanningBg,
            MirakcThemeTokens.StatusScanning,
            "スキャン完了処理中"
        )
        RuntimePhase.STARTING_SERVER -> Triple(Color(0xFF1B3D2B), Color(0xFF81C784), "サーバー起動中")
        RuntimePhase.RUNNING_SERVER -> Triple(
            MirakcThemeTokens.StatusRunningBg,
            MirakcThemeTokens.StatusRunning,
            "mirakc 稼働中"
        )
        RuntimePhase.STOPPING -> Triple(
            MirakcThemeTokens.StatusIdleBg,
            MirakcThemeTokens.StatusIdle,
            "サーバー停止中"
        )
        RuntimePhase.ERROR -> Triple(
            MirakcThemeTokens.StatusErrorBg,
            MirakcThemeTokens.StatusError,
            "エラー発生"
        )
    }

    Box(
        modifier = modifier
            .background(bgColor, RoundedCornerShape(12.dp))
            .border(1.dp, textColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = "● $label",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
            fontSize = 12.sp
        )
    }
}
