package dev.khronos31.mirakc.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.khronos31.mirakc.ui.contract.MirakcUiAction
import dev.khronos31.mirakc.ui.contract.MirakcUiState
import dev.khronos31.mirakc.ui.contract.ScanPhase
import dev.khronos31.mirakc.ui.contract.UpdateSuccessNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Modern Compose for TV presentation for mirakc server setup and operation.
 * Adheres strictly to immutable contract state and dispatches all actions through onAction.
 */
@Composable
fun MirakcTvScreen(state: MirakcUiState, onAction: (MirakcUiAction) -> Unit) {
    var section by remember { mutableStateOf(ScreenSection.STATUS) }
    var inputDialog by remember { mutableStateOf<InputDialog?>(null) }
    var restoreInputFocusTarget by remember { mutableStateOf<InputDialog?>(null) }
    var licensesOpen by remember { mutableStateOf(false) }
    var restoreLicenseFocus by remember { mutableStateOf(false) }
    var inputDraft by remember { mutableStateOf("") }

    val licenseFocusRequester = remember { FocusRequester() }
    val epgButtonFocusRequester = remember { FocusRequester() }
    val channelsButtonFocusRequester = remember { FocusRequester() }
    val statusNavFocusRequester = remember { FocusRequester() }
    val statusMainActionFocusRequester = remember { FocusRequester() }
    val settingsNavFocusRequester = remember { FocusRequester() }
    val aboutNavFocusRequester = remember { FocusRequester() }
    val initialNavFocusRequester = remember { FocusRequester() }
    var initialFocusDone by remember { mutableStateOf(false) }

    var pendingStatusActionFocusRestore by remember { mutableStateOf(false) }
    var statusUserNavigatedAway by remember { mutableStateOf(false) }

    val statusScrollState = rememberScrollState()
    val settingsScrollState = rememberScrollState()
    val aboutScrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    val context = LocalContext.current
    var pendingUpdateCheckBaseline by remember { mutableStateOf<Long?>(null) }
    var hasPendingUpdateCheck by remember { mutableStateOf(false) }
    var currentToast by remember { mutableStateOf<Toast?>(null) }

    // Clean up active Toast if composable leaves composition
    DisposableEffect(Unit) {
        onDispose {
            currentToast?.cancel()
            currentToast = null
        }
    }

    // Typed UpdateSuccessNotice toast notification for explicit CheckUpdates clicks only
    LaunchedEffect(state.updateSuccessNotice) {
        val notice = state.updateSuccessNotice
        if (hasPendingUpdateCheck && notice != null && notice.eventId != pendingUpdateCheckBaseline) {
            hasPendingUpdateCheck = false
            currentToast?.cancel()
            val toast = Toast.makeText(context, notice.text, Toast.LENGTH_SHORT)
            currentToast = toast
            toast.show()
        }
    }

    // Initial focus on first composition only; never steals focus during background polling
    LaunchedEffect(Unit) {
        if (!initialFocusDone) {
            initialNavFocusRequester.requestFocus()
            initialFocusDone = true
        }
    }

    // Restore focus to main server action button when async Start/Stop completes, unless user deliberately navigated away
    LaunchedEffect(state.capabilities.canStartServer, state.capabilities.canStopServer) {
        if (pendingStatusActionFocusRestore && !statusUserNavigatedAway && section == ScreenSection.STATUS) {
            if (state.capabilities.canStartServer || state.capabilities.canStopServer) {
                runCatching { statusMainActionFocusRequester.requestFocus() }
                pendingStatusActionFocusRestore = false
            }
        }
    }

    // Overlay focus and Back navigation handling: Back closes overlays first without trapping users
    BackHandler(enabled = licensesOpen) {
        licensesOpen = false
        restoreLicenseFocus = true
    }
    BackHandler(enabled = inputDialog != null) {
        val target = inputDialog
        inputDialog = null
        restoreInputFocusTarget = target
    }

    // Restore focus to launching control when overlay closes
    LaunchedEffect(licensesOpen, restoreLicenseFocus) {
        if (!licensesOpen && restoreLicenseFocus) {
            licenseFocusRequester.requestFocus()
            restoreLicenseFocus = false
        }
    }
    LaunchedEffect(inputDialog, restoreInputFocusTarget) {
        if (inputDialog == null && restoreInputFocusTarget != null) {
            when (restoreInputFocusTarget) {
                InputDialog.EPG -> epgButtonFocusRequester.requestFocus()
                InputDialog.CHANNELS -> channelsButtonFocusRequester.requestFocus()
                null -> {}
            }
            restoreInputFocusTarget = null
        }
    }

    MirakcTvTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            colors = androidx.tv.material3.SurfaceDefaults.colors(
                containerColor = MirakcThemeTokens.Background
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 24.dp, top = 20.dp, bottom = 20.dp, end = 28.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            event.key in listOf(
                                Key.DirectionDown,
                                Key.DirectionUp,
                                Key.DirectionLeft,
                                Key.DirectionRight,
                                Key.Tab
                            )
                        ) {
                            if (pendingStatusActionFocusRestore) {
                                statusUserNavigatedAway = true
                                pendingStatusActionFocusRestore = false
                            }
                        }
                        false
                    }
            ) {
                // Left Navigation Rail (Deterministic navigation targets for each section)
                NavigationRail(
                    activeSection = section,
                    appVersion = state.about.appVersion,
                    statusFocusRequester = statusNavFocusRequester,
                    settingsFocusRequester = settingsNavFocusRequester,
                    aboutFocusRequester = aboutNavFocusRequester,
                    initialFocusRequester = initialNavFocusRequester,
                    onSelectSection = { targetSection ->
                        if (section == targetSection) {
                            coroutineScope.launch {
                                when (targetSection) {
                                    ScreenSection.STATUS -> statusScrollState.scrollTo(0)
                                    ScreenSection.SETTINGS -> settingsScrollState.scrollTo(0)
                                    ScreenSection.ABOUT -> aboutScrollState.scrollTo(0)
                                }
                            }
                        } else {
                            section = targetSection
                            pendingStatusActionFocusRestore = false
                        }
                    }
                )

                Spacer(Modifier.width(28.dp))

                // Right Main Content Area (Anchored host notice OUTSIDE scrolling content)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(end = 12.dp, bottom = 20.dp)
                ) {
                    // Persistent visible host/validation message banner anchored OUTSIDE scrolling content
                    // Generic state.message remains normal error/available-update banner without toast interference
                    state.message?.let { msg ->
                        TvMessageBanner(
                            message = msg,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 14.dp)
                        )
                    }

                    // Content viewport with isolated per-section scroll states (prevents offset leak across sections)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        when (section) {
                            ScreenSection.STATUS -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(statusScrollState),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    StatusSectionContent(
                                        state = state,
                                        statusScrollState = statusScrollState,
                                        statusNavFocusRequester = statusNavFocusRequester,
                                        mainActionFocusRequester = statusMainActionFocusRequester,
                                        onTriggerStartServer = {
                                            pendingStatusActionFocusRestore = true
                                            statusUserNavigatedAway = false
                                            onAction(MirakcUiAction.StartServer)
                                        },
                                        onTriggerStopServer = {
                                            pendingStatusActionFocusRestore = true
                                            statusUserNavigatedAway = false
                                            onAction(MirakcUiAction.StopServer)
                                        },
                                        onAction = onAction
                                    )
                                    Spacer(Modifier.height(24.dp))
                                }
                            }
                            ScreenSection.SETTINGS -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(settingsScrollState),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    SettingsSectionContent(
                                        state = state,
                                        settingsScrollState = settingsScrollState,
                                        settingsNavFocusRequester = settingsNavFocusRequester,
                                        epgFocusRequester = epgButtonFocusRequester,
                                        channelsFocusRequester = channelsButtonFocusRequester,
                                        onEditEpg = {
                                            inputDraft = state.settings.epgIntervalMinutes.toString()
                                            inputDialog = InputDialog.EPG
                                            restoreInputFocusTarget = InputDialog.EPG
                                        },
                                        onEditChannels = {
                                            inputDraft = state.settings.terrestrialInput
                                            inputDialog = InputDialog.CHANNELS
                                            restoreInputFocusTarget = InputDialog.CHANNELS
                                        }
                                    )
                                    Spacer(Modifier.height(24.dp))
                                }
                            }
                            ScreenSection.ABOUT -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(aboutScrollState),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    AboutSectionContent(
                                        state = state,
                                        aboutScrollState = aboutScrollState,
                                        aboutNavFocusRequester = aboutNavFocusRequester,
                                        licenseFocusRequester = licenseFocusRequester,
                                        onOpenLicenses = {
                                            licensesOpen = true
                                            restoreLicenseFocus = false
                                        },
                                        onCheckUpdates = {
                                            pendingUpdateCheckBaseline = state.updateSuccessNotice?.eventId
                                            hasPendingUpdateCheck = true
                                            onAction(MirakcUiAction.CheckUpdates)
                                        }
                                    )
                                    Spacer(Modifier.height(24.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Dialogs: Input Editor & License Viewer (Adaptive heights within 540dp)
        inputDialog?.let { dialog ->
            InputEditorDialog(
                dialog = dialog,
                value = inputDraft,
                epgMin = state.settings.epgIntervalMinimum,
                epgMax = state.settings.epgIntervalMaximum,
                onValueChange = { inputDraft = it },
                onDismiss = {
                    val target = inputDialog
                    inputDialog = null
                    restoreInputFocusTarget = target
                },
                onSave = {
                    if (state.capabilities.canEditSettings) {
                        when (dialog) {
                            InputDialog.EPG -> onAction(MirakcUiAction.SaveEpgInterval(inputDraft))
                            InputDialog.CHANNELS -> onAction(MirakcUiAction.SaveChannels(inputDraft))
                        }
                    }
                    val target = inputDialog
                    inputDialog = null
                    restoreInputFocusTarget = target
                }
            )
        }

        if (licensesOpen) {
            LicenseDialog(state.about.licenseText) {
                licensesOpen = false
                restoreLicenseFocus = true
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Navigation Rail (Left Column)
// -------------------------------------------------------------------------------------------------

@Composable
private fun NavigationRail(
    activeSection: ScreenSection,
    appVersion: String,
    statusFocusRequester: FocusRequester,
    settingsFocusRequester: FocusRequester,
    aboutFocusRequester: FocusRequester,
    initialFocusRequester: FocusRequester,
    onSelectSection: (ScreenSection) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(210.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // App Title & Brand
        Column(modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "mirakc",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MirakcThemeTokens.OnSurface
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .background(MirakcThemeTokens.PrimaryContainer, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "TV",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MirakcThemeTokens.OnPrimaryContainer,
                        fontSize = 11.sp
                    )
                }
            }
            Text(
                text = "v$appVersion",
                style = MaterialTheme.typography.bodySmall,
                color = MirakcThemeTokens.OnSurfaceMuted,
                fontSize = 12.sp
            )
        }

        TvNavRailItem(
            label = "状態・操作",
            iconText = "📺",
            selected = activeSection == ScreenSection.STATUS,
            onClick = { onSelectSection(ScreenSection.STATUS) },
            modifier = Modifier
                .focusRequester(statusFocusRequester)
                .focusRequester(initialFocusRequester)
        )

        TvNavRailItem(
            label = "詳細設定",
            iconText = "⚙",
            selected = activeSection == ScreenSection.SETTINGS,
            onClick = { onSelectSection(ScreenSection.SETTINGS) },
            modifier = Modifier.focusRequester(settingsFocusRequester)
        )

        TvNavRailItem(
            label = "アプリ情報",
            iconText = "ℹ",
            selected = activeSection == ScreenSection.ABOUT,
            onClick = { onSelectSection(ScreenSection.ABOUT) },
            modifier = Modifier.focusRequester(aboutFocusRequester)
        )
    }
}

/**
 * Top action key handler for uppermost controls in content sections.
 * Up scrolls pane upward until top is visible and stays at scroll 0 without jumping into nav rail.
 * Left exits directly to the section's deterministic nav item when on leftmost control.
 */
private fun handleTopActionKeyEvent(
    event: androidx.compose.ui.input.key.KeyEvent,
    isLeftmost: Boolean,
    scrollState: ScrollState,
    navFocusRequester: FocusRequester
): Boolean {
    if (event.type == KeyEventType.KeyDown) {
        when (event.key) {
            Key.DirectionUp -> {
                if (scrollState.value > 0) {
                    scrollState.dispatchRawDelta(-160f)
                }
                return true // Keep focus; scroll pane upward until top visible; at scroll 0 stay there; never jump into nav
            }
            Key.DirectionLeft -> {
                if (isLeftmost) {
                    navFocusRequester.requestFocus()
                    return true // Deterministic return to own section nav item!
                }
            }
        }
    }
    return false
}

// -------------------------------------------------------------------------------------------------
// Section 1: 状態・操作 (Status Section Content)
// -------------------------------------------------------------------------------------------------

@Composable
private fun StatusSectionContent(
    state: MirakcUiState,
    statusScrollState: ScrollState,
    statusNavFocusRequester: FocusRequester,
    mainActionFocusRequester: FocusRequester,
    onTriggerStartServer: () -> Unit,
    onTriggerStopServer: () -> Unit,
    onAction: (MirakcUiAction) -> Unit
) {
    // 1. Unified Status & Primary Control Card (Keeps primary action & status compact in initial viewport)
    TvCard(
        backgroundColor = MirakcThemeTokens.Surface,
        borderColor = MirakcThemeTokens.BorderSubtle
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PhaseBadge(phase = state.phase, isPrepared = state.prepared != null)

            if (state.phase == dev.khronos31.mirakc.ui.contract.RuntimePhase.RUNNING_SERVER) {
                Box(
                    modifier = Modifier
                        .background(MirakcThemeTokens.StatusRunningBg, RoundedCornerShape(6.dp))
                        .border(1.dp, MirakcThemeTokens.StatusRunning.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "HTTP API: 40772",
                        style = MaterialTheme.typography.bodySmall,
                        color = MirakcThemeTokens.StatusRunning,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Text(
            text = state.statusTitle,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MirakcThemeTokens.OnSurface
        )

        Text(
            text = state.statusDetail,
            style = MaterialTheme.typography.bodyLarge,
            color = MirakcThemeTokens.OnSurfaceSecondary
        )

        Spacer(Modifier.height(2.dp))

        // Primary & contextual action buttons (Check Updates removed per user requirement; kept on About only)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isPrepared = state.prepared != null || state.capabilities.canStartServer || state.capabilities.canStopServer
            when {
                state.capabilities.canCancelScan -> {
                    TvActionButton(
                        text = "スキャンを中止",
                        onClick = { onAction(MirakcUiAction.CancelScan) },
                        isPrimary = true,
                        isDestructive = true,
                        modifier = Modifier.onPreviewKeyEvent { event ->
                            handleTopActionKeyEvent(
                                event = event,
                                isLeftmost = true,
                                scrollState = statusScrollState,
                                navFocusRequester = statusNavFocusRequester
                            )
                        }
                    )
                }
                isPrepared -> {
                    val isServerRunningOrStopping = state.capabilities.canStopServer ||
                        state.phase == dev.khronos31.mirakc.ui.contract.RuntimePhase.RUNNING_SERVER ||
                        state.phase == dev.khronos31.mirakc.ui.contract.RuntimePhase.STOPPING

                    TvActionButton(
                        text = if (isServerRunningOrStopping) "mirakcを停止" else "mirakcを起動",
                        onClick = {
                            if (isServerRunningOrStopping) {
                                onTriggerStopServer()
                            } else {
                                onTriggerStartServer()
                            }
                        },
                        enabled = if (isServerRunningOrStopping) state.capabilities.canStopServer else state.capabilities.canStartServer,
                        isPrimary = true,
                        isDestructive = isServerRunningOrStopping,
                        modifier = Modifier
                            .focusRequester(mainActionFocusRequester)
                            .onPreviewKeyEvent { event ->
                                handleTopActionKeyEvent(
                                    event = event,
                                    isLeftmost = true,
                                    scrollState = statusScrollState,
                                    navFocusRequester = statusNavFocusRequester
                                )
                            }
                    )

                    if (isServerRunningOrStopping || state.phase == dev.khronos31.mirakc.ui.contract.RuntimePhase.STARTING_SERVER) {
                        TvActionButton(
                            text = "チャンネルスキャン (停止が必要)",
                            onClick = { },
                            enabled = false
                        )
                    } else if (state.capabilities.canScan) {
                        TvActionButton(
                            text = "チャンネルを再スキャン",
                            onClick = { onAction(MirakcUiAction.StartScan) },
                            enabled = true,
                            modifier = Modifier.onPreviewKeyEvent { event ->
                                handleTopActionKeyEvent(
                                    event = event,
                                    isLeftmost = false,
                                    scrollState = statusScrollState,
                                    navFocusRequester = statusNavFocusRequester
                                )
                            }
                        )
                    }
                }
                state.capabilities.canScan -> {
                    TvActionButton(
                        text = "チャンネルスキャンを開始",
                        onClick = { onAction(MirakcUiAction.StartScan) },
                        isPrimary = true,
                        modifier = Modifier.onPreviewKeyEvent { event ->
                            handleTopActionKeyEvent(
                                event = event,
                                isLeftmost = true,
                                scrollState = statusScrollState,
                                navFocusRequester = statusNavFocusRequester
                            )
                        }
                    )
                }
                else -> {
                    TvActionButton(
                        text = "チャンネルスキャン",
                        onClick = { },
                        enabled = false,
                        modifier = Modifier.onPreviewKeyEvent { event ->
                            handleTopActionKeyEvent(
                                event = event,
                                isLeftmost = true,
                                scrollState = statusScrollState,
                                navFocusRequester = statusNavFocusRequester
                            )
                        }
                    )
                }
            }

            if (state.capabilities.canRequestUsbPermission) {
                val isOnlyActive = !state.capabilities.canScan &&
                    !state.capabilities.canStartServer &&
                    !state.capabilities.canStopServer &&
                    !state.capabilities.canCancelScan
                TvActionButton(
                    text = "USB権限を要求",
                    onClick = { onAction(MirakcUiAction.RequestUsbPermission) },
                    isPrimary = !state.capabilities.canScan && !state.capabilities.canStartServer,
                    modifier = Modifier.onPreviewKeyEvent { event ->
                        handleTopActionKeyEvent(
                            event = event,
                            isLeftmost = isOnlyActive,
                            scrollState = statusScrollState,
                            navFocusRequester = statusNavFocusRequester
                        )
                    }
                )
            }
        }
    }

    // 2. Scan Progress Card (Immediately under header; in initial 960x540 viewport when scanning)
    state.scan?.let { scan ->
        TvFocusableCard(
            modifier = Modifier.onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                    statusNavFocusRequester.requestFocus()
                    true
                } else false
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "チャンネルスキャン進捗",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MirakcThemeTokens.OnSurface
                )
                val phaseLabel = when (scan.phase) {
                    ScanPhase.WAITING -> "待機中"
                    ScanPhase.RUNNING -> "探索中"
                    ScanPhase.COMPLETE -> "スキャン完了"
                    ScanPhase.EMPTY -> "検出チャンネルなし"
                    ScanPhase.CANCELED -> "スキャン中断"
                    ScanPhase.FAILED -> "スキャン失敗"
                }
                val phaseColor = when (scan.phase) {
                    ScanPhase.COMPLETE -> MirakcThemeTokens.StatusRunning
                    ScanPhase.RUNNING, ScanPhase.WAITING -> MirakcThemeTokens.StatusScanning
                    ScanPhase.FAILED, ScanPhase.CANCELED -> MirakcThemeTokens.StatusError
                    ScanPhase.EMPTY -> MirakcThemeTokens.StatusWaiting
                }
                Text(
                    text = phaseLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = phaseColor
                )
            }

            TvProgressBar(completed = scan.completed, total = scan.total)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "進捗: ${scan.completed} / ${scan.total} チャンネル",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MirakcThemeTokens.OnSurface
                )
                scan.currentChannel?.let { ch ->
                    Text(
                        text = "現在探索中: 物理 ${ch}ch",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MirakcThemeTokens.Primary
                    )
                }
            }

            val foundText = if (scan.foundChannels.isNotEmpty()) {
                "${scan.foundChannels.joinToString(", ")}ch (${scan.foundChannels.size} チャンネル)"
            } else {
                "なし"
            }
            Text(
                text = "検出チャンネル: $foundText",
                style = MaterialTheme.typography.bodyMedium,
                color = MirakcThemeTokens.OnSurfaceSecondary
            )

            if (scan.failedChannels > 0) {
                Text(
                    text = "探索失敗: ${scan.failedChannels} チャンネル",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MirakcThemeTokens.StatusWarning
                )
            }
        }
    }

    // 3. Prepared Channels Card (Shows saved/previous setup)
    state.prepared?.let { prepared ->
        TvFocusableCard(
            modifier = Modifier.onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                    statusNavFocusRequester.requestFocus()
                    true
                } else false
            }
        ) {
            Text(
                text = "設定済み放送チャンネル",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MirakcThemeTokens.OnSurface
            )

            val grChannels = prepared.terrestrialChannels
            val grText = if (grChannels.isNotEmpty()) {
                "${grChannels.joinToString(", ")}ch (${grChannels.size} チャンネル)"
            } else {
                "未登録"
            }
            Text(
                text = "・ 地上デジタル (GR): $grText",
                style = MaterialTheme.typography.bodyLarge,
                color = MirakcThemeTokens.OnSurface
            )

            Text(
                text = "・ 衛星放送: BS ${prepared.satelliteBsChannels} ch / CS ${prepared.satelliteCsChannels} ch",
                style = MaterialTheme.typography.bodyLarge,
                color = MirakcThemeTokens.OnSurface
            )

            // Honest representation of satellite-only prepared state without fabricating terrestrial success
            if (grChannels.isEmpty() && (prepared.satelliteBsChannels > 0 || prepared.satelliteCsChannels > 0)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MirakcThemeTokens.SecondaryContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "※ 地上波チャンネルは検出・登録されていませんが、衛星放送（BS/CS: BS ${prepared.satelliteBsChannels} ch / CS ${prepared.satelliteCsChannels} ch）の設定が利用可能です。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MirakcThemeTokens.OnSecondaryContainer
                    )
                }
            }
        }
    }

    // 4. Connected Tuners & Permissions Card (Automatically brings full card into view on focus via bringContainerIntoViewOnFocus)
    TvFocusableCard(
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                statusNavFocusRequester.requestFocus()
                true
            } else false
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "接続チューナー機器",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MirakcThemeTokens.OnSurface
            )
            Text(
                text = "${state.devices.size} 台検出",
                style = MaterialTheme.typography.bodyMedium,
                color = MirakcThemeTokens.OnSurfaceSecondary
            )
        }

        if (state.devices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MirakcThemeTokens.StatusWarningBg, RoundedCornerShape(8.dp))
                    .border(1.dp, MirakcThemeTokens.StatusWarning.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = "チューナーが検出されていません。USBケーブルの接続を確認してください。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MirakcThemeTokens.StatusWarning
                )
            }
        } else {
            state.devices.forEach { device ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MirakcThemeTokens.SurfaceElevated, RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Text(
                            text = device.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MirakcThemeTokens.OnSurface,
                            softWrap = true
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${device.receiverCount}系統 · " +
                                "地デジ: ${if (device.supportsTerrestrial) "対応" else "非対応"} · " +
                                "BS/CS: ${if (device.supportsSatellite) "対応" else "非対応"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MirakcThemeTokens.OnSurfaceSecondary,
                            softWrap = true
                        )
                    }

                    // Permission indicator badge with dedicated spacing
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .background(
                                if (device.permissionGranted) MirakcThemeTokens.StatusRunningBg else MirakcThemeTokens.StatusWaitingBg,
                                RoundedCornerShape(6.dp)
                            )
                            .border(
                                1.dp,
                                if (device.permissionGranted) MirakcThemeTokens.StatusRunning.copy(alpha = 0.5f) else MirakcThemeTokens.StatusWaiting.copy(alpha = 0.5f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (device.permissionGranted) "USB許可済み" else "USB未許可",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (device.permissionGranted) MirakcThemeTokens.StatusRunning else MirakcThemeTokens.StatusWaiting
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Section 2: 詳細設定 (Settings Section Content)
// -------------------------------------------------------------------------------------------------

@Composable
private fun SettingsSectionContent(
    state: MirakcUiState,
    settingsScrollState: ScrollState,
    settingsNavFocusRequester: FocusRequester,
    epgFocusRequester: FocusRequester,
    channelsFocusRequester: FocusRequester,
    onEditEpg: () -> Unit,
    onEditChannels: () -> Unit
) {
    TvCard {
        Text(
            text = "詳細設定",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MirakcThemeTokens.OnSurface
        )
        Text(
            text = "EPG番組表の更新周期や地上デジタル物理チャンネルを手動で調整できます。",
            style = MaterialTheme.typography.bodyLarge,
            color = MirakcThemeTokens.OnSurfaceSecondary
        )
    }

    TvCard {
        Text(
            text = "番組表 (EPG) 更新間隔",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MirakcThemeTokens.OnSurface
        )
        Text(
            text = "現在の設定: ${state.settings.epgIntervalMinutes} 分間隔",
            style = MaterialTheme.typography.bodyLarge,
            color = MirakcThemeTokens.OnSurface
        )
        Text(
            text = "（設定可能範囲: ${state.settings.epgIntervalMinimum} 〜 ${state.settings.epgIntervalMaximum} 分）",
            style = MaterialTheme.typography.bodyMedium,
            color = MirakcThemeTokens.OnSurfaceSecondary
        )
        TvActionButton(
            text = "更新間隔を変更",
            onClick = onEditEpg,
            enabled = state.capabilities.canEditSettings,
            modifier = Modifier
                .focusRequester(epgFocusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.DirectionUp -> {
                                if (settingsScrollState.value > 0) {
                                    settingsScrollState.dispatchRawDelta(-160f)
                                }
                                true
                            }
                            Key.DirectionLeft -> {
                                settingsNavFocusRequester.requestFocus()
                                true
                            }
                            else -> false
                        }
                    } else false
                }
        )
    }

    TvCard {
        Text(
            text = "地上デジタル物理チャンネル",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MirakcThemeTokens.OnSurface
        )
        val channelsText = state.settings.terrestrialInput.ifEmpty { "未設定" }
        Text(
            text = "現在の設定: $channelsText",
            style = MaterialTheme.typography.bodyLarge,
            color = MirakcThemeTokens.OnSurface
        )
        Text(
            text = "カンマ区切りまたは範囲（例: 16, 21-27）で指定します。通常はチャンネルスキャンで自動登録されます。",
            style = MaterialTheme.typography.bodyMedium,
            color = MirakcThemeTokens.OnSurfaceSecondary
        )
        TvActionButton(
            text = "チャンネルを手動編集",
            onClick = onEditChannels,
            enabled = state.capabilities.canEditSettings,
            modifier = Modifier
                .focusRequester(channelsFocusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                        settingsNavFocusRequester.requestFocus()
                        true
                    } else false
                }
        )
    }
}

// -------------------------------------------------------------------------------------------------
// Section 3: アプリ情報 (About Section Content)
// -------------------------------------------------------------------------------------------------

@Composable
private fun AboutSectionContent(
    state: MirakcUiState,
    aboutScrollState: ScrollState,
    aboutNavFocusRequester: FocusRequester,
    licenseFocusRequester: FocusRequester,
    onOpenLicenses: () -> Unit,
    onCheckUpdates: () -> Unit
) {
    TvCard {
        Text(
            text = "アプリ情報",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MirakcThemeTokens.OnSurface
        )
        Text(
            text = "バージョン構成およびライセンス一覧です。",
            style = MaterialTheme.typography.bodyLarge,
            color = MirakcThemeTokens.OnSurfaceSecondary
        )
    }

    // Focusable, non-clickable info rows
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TvFocusableInfoRow(
            label = "アプリバージョン",
            value = state.about.appVersion,
            modifier = Modifier.onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionUp -> {
                            if (aboutScrollState.value > 0) {
                                aboutScrollState.dispatchRawDelta(-160f)
                            }
                            true
                        }
                        Key.DirectionLeft -> {
                            aboutNavFocusRequester.requestFocus()
                            true
                        }
                        else -> false
                    }
                } else false
            }
        )

        state.about.engineVersions.forEach { engine ->
            TvFocusableInfoRow(
                label = engine.label,
                value = engine.value,
                modifier = Modifier.onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                        aboutNavFocusRequester.requestFocus()
                        true
                    } else false
                }
            )
        }

        state.about.driverVersions.forEach { driver ->
            TvFocusableInfoRow(
                label = driver.label,
                value = driver.value,
                modifier = Modifier.onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                        aboutNavFocusRequester.requestFocus()
                        true
                    } else false
                }
            )
        }

        // Repository ends without .git; focusable, non-clickable
        TvFocusableInfoRow(
            label = "GitHubリポジトリ",
            value = state.about.repositoryUrl.removeSuffix(".git"),
            modifier = Modifier.onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                    aboutNavFocusRequester.requestFocus()
                    true
                } else false
            }
        )
    }

    // Action buttons in About section (Coordinated bring-into-view keeps buttons and safe bottom margin visible)
    Row(
        modifier = Modifier.bringContainerIntoViewOnFocus(extraBottomMargin = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TvActionButton(
            text = "オープンソースライセンスを表示",
            onClick = onOpenLicenses,
            modifier = Modifier
                .focusRequester(licenseFocusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                        aboutNavFocusRequester.requestFocus()
                        true
                    } else false
                }
        )

        TvActionButton(
            text = "アップデートを確認",
            onClick = onCheckUpdates,
            enabled = state.capabilities.canCheckUpdates
        )
    }
}

// -------------------------------------------------------------------------------------------------
// Overlays: InputEditorDialog & LicenseDialog (Adaptive height within 540dp TV viewport)
// -------------------------------------------------------------------------------------------------

@Composable
private fun InputEditorDialog(
    dialog: InputDialog,
    value: String,
    epgMin: Int,
    epgMax: Int,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val saveFocusRequester = remember { FocusRequester() }
    val cancelFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        inputFocusRequester.requestFocus()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .width(520.dp)
                .background(MirakcThemeTokens.Surface, RoundedCornerShape(14.dp))
                .border(2.dp, MirakcThemeTokens.FocusStroke, RoundedCornerShape(14.dp))
                .padding(22.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(
                containerColor = MirakcThemeTokens.Surface
            )
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = if (dialog == InputDialog.EPG) "番組表の更新間隔（分）" else "地上デジタル物理チャンネル",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MirakcThemeTokens.OnSurface
                )

                Text(
                    text = if (dialog == InputDialog.EPG) {
                        "${epgMin}〜${epgMax}分の整数値を入力してください。"
                    } else {
                        "カンマ区切りまたは範囲（例: 16, 21-27）で入力してください。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MirakcThemeTokens.OnSurfaceSecondary
                )

                // Input Box with high-contrast TV focus border
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isInputFocused) MirakcThemeTokens.SurfaceFocused else MirakcThemeTokens.SurfaceElevated,
                            RoundedCornerShape(8.dp)
                        )
                        .border(
                            width = if (isInputFocused) 2.dp else 1.dp,
                            color = if (isInputFocused) MirakcThemeTokens.FocusStroke else MirakcThemeTokens.BorderSubtle,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (dialog == InputDialog.EPG) KeyboardType.Number else KeyboardType.Text,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { onSave() }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(inputFocusRequester)
                            .onFocusChanged { isInputFocused = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    when (event.key) {
                                        Key.DirectionDown -> {
                                            saveFocusRequester.requestFocus()
                                            true
                                        }
                                        Key.DirectionRight -> {
                                            if (dialog == InputDialog.EPG) {
                                                saveFocusRequester.requestFocus()
                                                true
                                            } else false
                                        }
                                        Key.Tab -> {
                                            saveFocusRequester.requestFocus()
                                            true
                                        }
                                        Key.Enter, Key.NumPadEnter -> {
                                            onSave()
                                            true
                                        }
                                        else -> false
                                    }
                                } else false
                            },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MirakcThemeTokens.OnSurface
                        ),
                        cursorBrush = SolidColor(MirakcThemeTokens.Primary)
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    TvActionButton(
                        text = "保存",
                        onClick = onSave,
                        isPrimary = true,
                        modifier = Modifier
                            .focusRequester(saveFocusRequester)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    when (event.key) {
                                        Key.DirectionUp -> {
                                            inputFocusRequester.requestFocus()
                                            true
                                        }
                                        Key.DirectionRight -> {
                                            cancelFocusRequester.requestFocus()
                                            true
                                        }
                                        else -> false
                                    }
                                } else false
                            }
                    )
                    TvActionButton(
                        text = "キャンセル",
                        onClick = onDismiss,
                        modifier = Modifier
                            .focusRequester(cancelFocusRequester)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    when (event.key) {
                                        Key.DirectionUp -> {
                                            inputFocusRequester.requestFocus()
                                            true
                                        }
                                        Key.DirectionLeft -> {
                                            saveFocusRequester.requestFocus()
                                            true
                                        }
                                        else -> false
                                    }
                                } else false
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun LicenseDialog(text: String, onClose: () -> Unit) {
    val scrollState = rememberScrollState()
    val licenseViewportFocusRequester = remember { FocusRequester() }
    val closeButtonFocusRequester = remember { FocusRequester() }
    var isViewportFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        licenseViewportFocusRequester.requestFocus()
    }

    Dialog(onDismissRequest = onClose) {
        Surface(
            modifier = Modifier
                .width(640.dp)
                .heightIn(max = 440.dp)
                .background(MirakcThemeTokens.Surface, RoundedCornerShape(14.dp))
                .border(2.dp, MirakcThemeTokens.FocusStroke, RoundedCornerShape(14.dp))
                .padding(20.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(
                containerColor = MirakcThemeTokens.Surface
            )
        ) {
            Column(
                modifier = Modifier.fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "オープンソースライセンス",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MirakcThemeTokens.OnSurface
                )

                // Scrollable container for license text with explicit up/down key handling and clear focus
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(
                            if (isViewportFocused) MirakcThemeTokens.SurfaceFocused else MirakcThemeTokens.SurfaceElevated,
                            RoundedCornerShape(8.dp)
                        )
                        .border(
                            width = if (isViewportFocused) 2.dp else 1.dp,
                            color = if (isViewportFocused) MirakcThemeTokens.FocusStroke else MirakcThemeTokens.BorderSubtle,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp)
                        .verticalScroll(scrollState)
                        .focusRequester(licenseViewportFocusRequester)
                        .onFocusChanged { isViewportFocused = it.isFocused }
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                val scrollStepPx = 160f
                                when (event.key) {
                                    Key.DirectionDown -> {
                                        if (scrollState.value < scrollState.maxValue) {
                                            scrollState.dispatchRawDelta(scrollStepPx)
                                        } else {
                                            closeButtonFocusRequester.requestFocus()
                                        }
                                        true
                                    }
                                    Key.DirectionUp -> {
                                        if (scrollState.value > 0) {
                                            scrollState.dispatchRawDelta(-scrollStepPx)
                                        }
                                        true
                                    }
                                    Key.DirectionRight, Key.Tab -> {
                                        closeButtonFocusRequester.requestFocus()
                                        true
                                    }
                                    Key.Back, Key.Escape -> {
                                        onClose()
                                        true
                                    }
                                    else -> false
                                }
                            } else false
                        }
                ) {
                    Text(
                        text = text,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = if (isViewportFocused) MirakcThemeTokens.OnSurface else MirakcThemeTokens.OnSurfaceSecondary
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TvActionButton(
                        text = "閉じる",
                        onClick = onClose,
                        isPrimary = true,
                        modifier = Modifier
                            .focusRequester(closeButtonFocusRequester)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    when (event.key) {
                                        Key.DirectionUp, Key.DirectionLeft -> {
                                            licenseViewportFocusRequester.requestFocus()
                                            true
                                        }
                                        Key.Back, Key.Escape -> {
                                            onClose()
                                            true
                                        }
                                        else -> false
                                    }
                                } else false
                            }
                    )
                }
            }
        }
    }
}

private enum class ScreenSection { STATUS, SETTINGS, ABOUT }
private enum class InputDialog { EPG, CHANNELS }
