package dev.khronos31.epgstation.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import dev.khronos31.epgstation.ui.contract.EpgStationAboutUi
import dev.khronos31.epgstation.ui.contract.EpgStationUiAction
import dev.khronos31.epgstation.ui.contract.EpgStationUiState
import dev.khronos31.epgstation.ui.contract.LicenseDocumentUi
import dev.khronos31.epgstation.ui.contract.RecordingVolumeUi
import dev.khronos31.epgstation.ui.contract.StorageAccessUi
import dev.khronos31.epgstation.ui.contract.RepositoryLinkUi
import dev.khronos31.epgstation.ui.contract.UpdatePromptUi
import kotlinx.coroutines.delay

/**
 * Modern Compose for TV launcher and configuration screen for EPGStation Server.
 * Adheres strictly to immutable contract state and dispatches all user actions through onAction.
 */
@Composable
fun EpgStationTvScreen(state: EpgStationUiState, onAction: (EpgStationUiAction) -> Unit) {
    var urlDialogOpen by remember { mutableStateOf(false) }
    var urlDraft by remember { mutableStateOf(state.baseUrl) }
    var baselineSaveRevision by remember { mutableStateOf(state.baseUrlSaveRevision) }
    var pendingUpdateCheck by remember { mutableStateOf(false) }
    var updateBaseline by remember { mutableStateOf<Long?>(null) }
    var currentToast by remember { mutableStateOf<Toast?>(null) }

    val editUrlFocusRequester = remember { FocusRequester() }
    val updateCheckFocusRequester = remember { FocusRequester() }
    val aboutButtonFocusRequester = remember { FocusRequester() }
    var restoreFocusRequester by remember { mutableStateOf<FocusRequester?>(null) }
    var initialFocusDone by remember { mutableStateOf(false) }
    var wasAboutVisible by remember { mutableStateOf(false) }
    var lastOpenedLicenseId by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()
    val context = LocalContext.current

    // Remember QR bitmap efficiently; do not re-create on unrelated recompositions
    val qrBitmap = remember(state.qrImage) {
        state.qrImage?.let { qr ->
            Bitmap.createBitmap(qr.copyPixels(), qr.width, qr.height, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }

    // Clean up active Toast if composable leaves composition
    DisposableEffect(Unit) {
        onDispose {
            currentToast?.cancel()
            currentToast = null
        }
    }

    // baseUrlSaveRevision increment closes the dialog even when saving the same normalized URL
    LaunchedEffect(state.baseUrlSaveRevision) {
        if (urlDialogOpen && state.baseUrlSaveRevision > baselineSaveRevision) {
            urlDialogOpen = false
            baselineSaveRevision = state.baseUrlSaveRevision
            restoreFocusRequester = editUrlFocusRequester
        }
    }

    // Typed UpdateSuccessNotice toast notification for explicit CheckUpdates clicks only
    LaunchedEffect(state.updateSuccessNotice) {
        val notice = state.updateSuccessNotice
        if (pendingUpdateCheck && notice != null && notice.eventId != updateBaseline) {
            pendingUpdateCheck = false
            currentToast?.cancel()
            val toast = Toast.makeText(context, notice.text, Toast.LENGTH_SHORT)
            currentToast = toast
            toast.show()
        }
    }

    // Initial focus on first composition only; never steals focus during background polling
    LaunchedEffect(Unit) {
        if (!initialFocusDone) {
            editUrlFocusRequester.requestFocus()
            initialFocusDone = true
        }
    }

    // Restore focus to launching control when dialog closes
    LaunchedEffect(urlDialogOpen, state.updatePrompt) {
        if (!urlDialogOpen && state.updatePrompt == null && restoreFocusRequester != null) {
            runCatching { restoreFocusRequester?.requestFocus() }
            restoreFocusRequester = null
        }
    }

    // Restore focus to "アプリ情報" button when returning from About screen to Overview
    LaunchedEffect(state.aboutVisible) {
        if (!state.aboutVisible && wasAboutVisible) {
            runCatching { aboutButtonFocusRequester.requestFocus() }
        }
        wasAboutVisible = state.aboutVisible
    }

    // Back handling: License viewer closes; About screen closes; URL dialog cancels edit; Update permission dialog strictly cancels update
    BackHandler(enabled = state.selectedLicenseId != null || state.aboutVisible || urlDialogOpen || state.updatePrompt != null) {
        when {
            state.selectedLicenseId != null -> {
                onAction(EpgStationUiAction.CloseLicense)
            }
            state.aboutVisible -> {
                lastOpenedLicenseId = null
                onAction(EpgStationUiAction.CloseAbout)
            }
            urlDialogOpen -> {
                urlDialogOpen = false
                onAction(EpgStationUiAction.CancelBaseUrlEdit)
                restoreFocusRequester = editUrlFocusRequester
            }
            state.updatePrompt != null -> {
                onAction(EpgStationUiAction.CancelUpdatePrompt)
                restoreFocusRequester = updateCheckFocusRequester
            }
        }
    }

    val selectedLicenseId = state.selectedLicenseId
    if (selectedLicenseId != null) {
        val activeLicense = state.about?.licenses?.firstOrNull { it.id == selectedLicenseId }
        LicenseViewerScreen(
            license = activeLicense,
            licenseId = selectedLicenseId,
            onClose = { onAction(EpgStationUiAction.CloseLicense) }
        )
    } else if (state.aboutVisible) {
        AboutScreen(
            state = state,
            onOpenLicense = { id ->
                lastOpenedLicenseId = id
                onAction(EpgStationUiAction.OpenLicense(id))
            },
            onCloseAbout = {
                lastOpenedLicenseId = null
                onAction(EpgStationUiAction.CloseAbout)
            },
            restoreLicenseId = lastOpenedLicenseId
        )
    } else {
        EpgStationTvTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            colors = androidx.tv.material3.SurfaceDefaults.colors(
                containerColor = EpgStationThemeTokens.Background
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 32.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header: Brand & Summary
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "EPGStation",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = EpgStationThemeTokens.OnSurface
                        )
                        Spacer(Modifier.width(10.dp))
                        TvBadge(
                            text = "Server",
                            backgroundColor = EpgStationThemeTokens.PrimaryContainer,
                            textColor = EpgStationThemeTokens.OnPrimaryContainer
                        )
                    }
                    Text(
                        text = "ブラウザーで番組表・予約・設定を開きます",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EpgStationThemeTokens.OnSurfaceSecondary
                    )
                }

                // Two-column responsive living-room layout
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Left Column: Web UI LAN Access & Mirakurun/mirakc Base URL
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Card 1: Web UI & LAN Access
                        WebAccessCard(listenUrls = state.listenUrls, qrBitmap = qrBitmap)

                        // Card 2: Mirakurun / mirakc Connection Base URL
                        BaseUrlCard(
                            baseUrl = state.baseUrl,
                            validationError = state.baseUrlValidationError,
                            focusRequester = editUrlFocusRequester,
                            onEdit = {
                                urlDraft = state.baseUrl
                                baselineSaveRevision = state.baseUrlSaveRevision
                                restoreFocusRequester = editUrlFocusRequester
                                urlDialogOpen = true
                            },
                            onScrollUp = {
                                if (scrollState.value > 0) {
                                    scrollState.dispatchRawDelta(-160f)
                                }
                            }
                        )
                    }

                    // Right Column: Recording Storage & App Updates
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Card 3: Recording Storage Selection
                        StorageCard(
                            volumes = state.volumes,
                            onSelect = { volumeId ->
                                onAction(EpgStationUiAction.SelectStorage(volumeId))
                            }
                        )

                        // Card 4: App Updates & About
                        UpdateCard(
                            updateBusy = state.updateBusy,
                            updateFocusRequester = updateCheckFocusRequester,
                            aboutFocusRequester = aboutButtonFocusRequester,
                            onCheckUpdates = {
                                if (!state.updateBusy) {
                                    updateBaseline = state.updateSuccessNotice?.eventId
                                    pendingUpdateCheck = true
                                    restoreFocusRequester = updateCheckFocusRequester
                                    onAction(EpgStationUiAction.CheckUpdates)
                                }
                            },
                            onOpenAbout = {
                                onAction(EpgStationUiAction.OpenAbout)
                            }
                        )
                    }
                }
            }
        }

        // Overlay Dialogs: URL Editor
        if (urlDialogOpen) {
            UrlEditorDialog(
                url = urlDraft,
                validationError = state.baseUrlValidationError,
                onValueChange = { urlDraft = it },
                onSave = {
                    onAction(EpgStationUiAction.SaveBaseUrl(urlDraft))
                },
                onDismiss = {
                    urlDialogOpen = false
                    onAction(EpgStationUiAction.CancelBaseUrlEdit)
                    restoreFocusRequester = editUrlFocusRequester
                }
            )
        }

        // Overlay Dialogs: Update Prompts
        state.updatePrompt?.let { prompt ->
            when (prompt) {
                is UpdatePromptUi.Available -> {
                    ConfirmDialog(
                        title = "アップデートが利用可能です",
                        message = "新しいバージョン (${prompt.versionText}) が見つかりました。ダウンロードしてインストールしますか？",
                        confirmText = "ダウンロード",
                        dismissText = "キャンセル",
                        onConfirm = { onAction(EpgStationUiAction.ConfirmUpdateDownload) },
                        onDismiss = {
                            onAction(EpgStationUiAction.CancelUpdatePrompt)
                            restoreFocusRequester = updateCheckFocusRequester
                        }
                    )
                }
                is UpdatePromptUi.Failure -> {
                    InfoDialog(
                        title = "更新確認に失敗しました",
                        message = prompt.message,
                        buttonText = "閉じる",
                        onDismiss = {
                            onAction(EpgStationUiAction.CancelUpdatePrompt)
                            restoreFocusRequester = updateCheckFocusRequester
                        }
                    )
                }
                is UpdatePromptUi.UnknownSourcesPermission -> {
                    // Critical requirement: Back key strictly cancels without opening settings
                    ConfirmDialog(
                        title = "インストールの許可が必要です",
                        message = "不明なアプリのインストールを許可してから、もう一度更新を実行してください。",
                        confirmText = "設定を開く",
                        dismissText = "キャンセル",
                        onConfirm = { onAction(EpgStationUiAction.OpenUnknownSourcesSettings) },
                        onDismiss = {
                            onAction(EpgStationUiAction.CancelUpdatePrompt)
                            restoreFocusRequester = updateCheckFocusRequester
                        }
                    )
                }
                is UpdatePromptUi.DownloadFailure -> {
                    InfoDialog(
                        title = "ダウンロードに失敗しました",
                        message = prompt.message,
                        buttonText = "閉じる",
                        onDismiss = {
                            onAction(EpgStationUiAction.CancelUpdatePrompt)
                            restoreFocusRequester = updateCheckFocusRequester
                        }
                    )
                }
            }
        }
    }
    }
}

// -------------------------------------------------------------------------------------------------
// Section Cards
// -------------------------------------------------------------------------------------------------

@Composable
private fun WebAccessCard(
    listenUrls: List<String>,
    qrBitmap: androidx.compose.ui.graphics.ImageBitmap?,
    modifier: Modifier = Modifier
) {
    val isLoopbackOnly = remember(listenUrls) {
        listenUrls.isNotEmpty() && listenUrls.all { url ->
            url.contains("127.0.0.1") || url.contains("localhost") || url.contains("::1")
        }
    }

    TvCard(modifier = modifier) {
        Text(
            text = "ブラウザー接続 (Web UI)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = EpgStationThemeTokens.OnSurface
        )
        Text(
            text = "スマホやPCのブラウザーから番組表・予約を開きます。",
            style = MaterialTheme.typography.bodySmall,
            color = EpgStationThemeTokens.OnSurfaceSecondary,
            maxLines = 1
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // QR code with clean white quiet zone and crisp pixel rendering (116dp image + 6dp padding)
            if (qrBitmap != null) {
                Box(
                    modifier = Modifier
                        .background(Color.White, RoundedCornerShape(6.dp))
                        .padding(6.dp)
                ) {
                    Image(
                        bitmap = qrBitmap,
                        contentDescription = "接続URLのQRコード",
                        modifier = Modifier.size(116.dp),
                        filterQuality = FilterQuality.None
                    )
                }
            }

            // Real host-provided URLs formatted on a readable single line
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "アクセスURL:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = EpgStationThemeTokens.OnSurfaceSecondary
                )
                if (listenUrls.isEmpty()) {
                    Text(
                        text = "リッスンURLを取得できませんでした",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EpgStationThemeTokens.OnSurfaceMuted
                    )
                } else {
                    listenUrls.forEach { url ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(EpgStationThemeTokens.SurfaceElevated, RoundedCornerShape(6.dp))
                                .border(1.dp, EpgStationThemeTokens.BorderSubtle, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = url,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.5.sp
                                ),
                                maxLines = 1,
                                softWrap = false,
                                color = EpgStationThemeTokens.Primary
                            )
                        }
                    }
                }

                if (isLoopbackOnly) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(EpgStationThemeTokens.StatusWarningBg, RoundedCornerShape(6.dp))
                            .border(1.dp, EpgStationThemeTokens.StatusWarning.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .padding(6.dp)
                    ) {
                        Text(
                            text = "※ ローカル専用アドレスです。同じ機器上のブラウザーからのみアクセス可能です。",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = EpgStationThemeTokens.StatusWarning
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BaseUrlCard(
    baseUrl: String,
    validationError: String?,
    focusRequester: FocusRequester,
    onEdit: () -> Unit,
    onScrollUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val localizedError = remember(validationError) {
        formatBaseUrlValidationError(validationError)
    }

    TvCard(modifier = modifier) {
        Text(
            text = "Mirakurun / mirakc 接続先",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = EpgStationThemeTokens.OnSurface
        )
        Text(
            text = "番組情報取得とチューナー受信のベースURLです。",
            style = MaterialTheme.typography.bodySmall,
            color = EpgStationThemeTokens.OnSurfaceSecondary,
            maxLines = 1
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(EpgStationThemeTokens.SurfaceElevated, RoundedCornerShape(6.dp))
                .border(1.dp, EpgStationThemeTokens.BorderSubtle, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = baseUrl.ifEmpty { "未設定" },
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                ),
                color = EpgStationThemeTokens.OnSurface
            )
        }

        if (localizedError != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(EpgStationThemeTokens.StatusErrorBg, RoundedCornerShape(6.dp))
                    .border(1.dp, EpgStationThemeTokens.StatusError.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = localizedError,
                    style = MaterialTheme.typography.bodySmall,
                    color = EpgStationThemeTokens.StatusError,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        TvActionButton(
            text = "接続先URLを変更",
            onClick = onEdit,
            isPrimary = true,
            modifier = Modifier
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                        onScrollUp()
                        true
                    } else false
                }
        )
    }
}

@Composable
private fun StorageCard(
    volumes: List<RecordingVolumeUi>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    TvCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "録画ストレージ",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = EpgStationThemeTokens.OnSurface
            )
            Text(
                text = "${volumes.size} 箇所検出",
                style = MaterialTheme.typography.bodySmall,
                color = EpgStationThemeTokens.OnSurfaceSecondary
            )
        }

        Text(
            text = "番組録画ファイルの保存先ストレージを選択します。",
            style = MaterialTheme.typography.bodySmall,
            color = EpgStationThemeTokens.OnSurfaceSecondary,
            maxLines = 1
        )

        // Volume entries
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            volumes.forEach { volume ->
                key(volume.id) {
                    val displayTitle = remember(volume.id, volume.removable, volume.title) {
                        formatVolumeTitle(volume)
                    }
                    val capacityJapanese = remember(volume.freeBytes, volume.totalBytes) {
                        formatStorageCapacityJapanese(volume.freeBytes, volume.totalBytes)
                    }

                    val accessNote = when (volume.storageAccess) {
                        StorageAccessUi.NeedsAllFilesAccess ->
                            "タップで、すべてのファイルへのアクセスを許可します。他のアプリの許可もそのまま残ります。"
                        StorageAccessUi.NeedsProcessRestart ->
                            "許可は付いています。タップでアプリを起動し直し、このストレージを使えるようにします。"
                        StorageAccessUi.None -> null
                    }
                    TvFocusableCard(
                        onClick = { onSelect(volume.id) },
                        enabled = volume.available || volume.storageAccess != StorageAccessUi.None,
                        isSelected = volume.selected
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = displayTitle,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = EpgStationThemeTokens.OnSurface
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (volume.removable) {
                                    TvBadge(
                                        text = "USB",
                                        backgroundColor = EpgStationThemeTokens.SecondaryContainer,
                                        textColor = EpgStationThemeTokens.OnSecondaryContainer
                                    )
                                }
                                if (volume.selected) {
                                    TvBadge(
                                        text = "✓ 録画先",
                                        backgroundColor = EpgStationThemeTokens.StatusSuccessBg,
                                        textColor = EpgStationThemeTokens.StatusSuccess
                                    )
                                } else when (volume.storageAccess) {
                                    StorageAccessUi.NeedsAllFilesAccess -> TvBadge(
                                        text = "許可が必要",
                                        backgroundColor = EpgStationThemeTokens.StatusWarningBg,
                                        textColor = EpgStationThemeTokens.StatusWarning
                                    )
                                    StorageAccessUi.NeedsProcessRestart -> TvBadge(
                                        text = "再起動が必要",
                                        backgroundColor = EpgStationThemeTokens.StatusWarningBg,
                                        textColor = EpgStationThemeTokens.StatusWarning
                                    )
                                    StorageAccessUi.None -> if (!volume.available) {
                                        TvBadge(
                                            text = "利用不可",
                                            backgroundColor = EpgStationThemeTokens.StatusErrorBg,
                                            textColor = EpgStationThemeTokens.StatusError
                                        )
                                    }
                                }
                            }
                        }

                        Text(
                            text = "パス: ${volume.recordedPath}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp),
                            color = EpgStationThemeTokens.OnSurfaceSecondary,
                            maxLines = 2
                        )

                        Text(
                            text = "容量: $capacityJapanese",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                            color = if (volume.available) EpgStationThemeTokens.Primary else EpgStationThemeTokens.OnSurfaceMuted,
                            fontWeight = FontWeight.Medium
                        )
                        if (accessNote != null) {
                            Text(
                                text = accessNote,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                color = EpgStationThemeTokens.StatusWarning,
                                maxLines = 3
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateCard(
    updateBusy: Boolean,
    updateFocusRequester: FocusRequester,
    aboutFocusRequester: FocusRequester,
    onCheckUpdates: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier
) {
    TvCard(modifier = modifier) {
        Text(
            text = "アプリ更新",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = EpgStationThemeTokens.OnSurface
        )
        Text(
            text = "GitHubリリースの最新バージョン確認やアプリ情報を表示します。",
            style = MaterialTheme.typography.bodySmall,
            color = EpgStationThemeTokens.OnSurfaceSecondary,
            maxLines = 1
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TvActionButton(
                text = if (updateBusy) "更新を確認中…" else "アップデートを確認",
                onClick = onCheckUpdates,
                enabled = true,
                isBusy = updateBusy,
                modifier = Modifier
                    .weight(1.3f)
                    .focusRequester(updateFocusRequester)
            )
            TvActionButton(
                text = "アプリ情報",
                onClick = onOpenAbout,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(aboutFocusRequester)
            )
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Dialogs
// -------------------------------------------------------------------------------------------------

@Composable
private fun UrlEditorDialog(
    url: String,
    validationError: String?,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
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
                .width(540.dp)
                .background(EpgStationThemeTokens.Surface, RoundedCornerShape(14.dp))
                .border(2.dp, EpgStationThemeTokens.FocusStroke, RoundedCornerShape(14.dp))
                .padding(22.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(
                containerColor = EpgStationThemeTokens.Surface
            )
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "接続先URLの変更",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = EpgStationThemeTokens.OnSurface
                )

                Text(
                    text = "Mirakurun または mirakc のベースURLを指定してください（例: http://127.0.0.1:40772/）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EpgStationThemeTokens.OnSurfaceSecondary
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isInputFocused) EpgStationThemeTokens.SurfaceFocused else EpgStationThemeTokens.SurfaceElevated,
                            RoundedCornerShape(8.dp)
                        )
                        .border(
                            width = if (isInputFocused) 2.dp else 1.dp,
                            color = if (isInputFocused) EpgStationThemeTokens.FocusStroke else EpgStationThemeTokens.BorderSubtle,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    BasicTextField(
                        value = url,
                        onValueChange = onValueChange,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { onSave() }),
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
                            fontFamily = FontFamily.Monospace,
                            color = EpgStationThemeTokens.OnSurface
                        ),
                        cursorBrush = SolidColor(EpgStationThemeTokens.Primary)
                    )
                }

                val localizedError = remember(validationError) {
                    formatBaseUrlValidationError(validationError)
                }

                if (localizedError != null) {
                    Text(
                        text = localizedError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = EpgStationThemeTokens.StatusError,
                        fontWeight = FontWeight.Medium
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
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val confirmFocusRequester = remember { FocusRequester() }
    val dismissFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        confirmFocusRequester.requestFocus()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .width(500.dp)
                .background(EpgStationThemeTokens.Surface, RoundedCornerShape(14.dp))
                .border(2.dp, EpgStationThemeTokens.FocusStroke, RoundedCornerShape(14.dp))
                .padding(22.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(
                containerColor = EpgStationThemeTokens.Surface
            )
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = EpgStationThemeTokens.OnSurface
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = EpgStationThemeTokens.OnSurfaceSecondary
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    TvActionButton(
                        text = confirmText,
                        onClick = onConfirm,
                        isPrimary = true,
                        modifier = Modifier
                            .focusRequester(confirmFocusRequester)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                                    dismissFocusRequester.requestFocus()
                                    true
                                } else false
                            }
                    )
                    TvActionButton(
                        text = dismissText,
                        onClick = onDismiss,
                        modifier = Modifier
                            .focusRequester(dismissFocusRequester)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                                    confirmFocusRequester.requestFocus()
                                    true
                                } else false
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoDialog(
    title: String,
    message: String,
    buttonText: String,
    onDismiss: () -> Unit
) {
    val buttonFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        buttonFocusRequester.requestFocus()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .width(480.dp)
                .background(EpgStationThemeTokens.Surface, RoundedCornerShape(14.dp))
                .border(2.dp, EpgStationThemeTokens.FocusStroke, RoundedCornerShape(14.dp))
                .padding(22.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(
                containerColor = EpgStationThemeTokens.Surface
            )
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = EpgStationThemeTokens.OnSurface
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = EpgStationThemeTokens.OnSurfaceSecondary
                )
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TvActionButton(
                        text = buttonText,
                        onClick = onDismiss,
                        isPrimary = true,
                        modifier = Modifier.focusRequester(buttonFocusRequester)
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// About and License Screens
// -------------------------------------------------------------------------------------------------

/**
 * Dedicated About screen presenting app and EPGStation versions, focusable repository links,
 * and list of open source license documents.
 */
@Composable
private fun AboutScreen(
    state: EpgStationUiState,
    onOpenLicense: (String) -> Unit,
    onCloseAbout: () -> Unit,
    restoreLicenseId: String?
) {
    val aboutScrollState = rememberScrollState()
    val firstRowFocusRequester = remember { FocusRequester() }
    val backButtonFocusRequester = remember { FocusRequester() }
    val licenseFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val about = state.about
    var initialContentFocusSet by remember { mutableStateOf(false) }

    LaunchedEffect(about != null, state.selectedLicenseId, restoreLicenseId) {
        if (state.selectedLicenseId != null) {
            return@LaunchedEffect
        }

        if (restoreLicenseId != null) {
            val target = licenseFocusRequesters.getOrPut(restoreLicenseId) { FocusRequester() }
            val result = runCatching { target.requestFocus() }
            if (result.isFailure) {
                delay(16)
                runCatching { target.requestFocus() }
            }
        } else if (about != null) {
            if (!initialContentFocusSet) {
                initialContentFocusSet = true
                val result = runCatching { firstRowFocusRequester.requestFocus() }
                if (result.isFailure) {
                    delay(16)
                    runCatching { firstRowFocusRequester.requestFocus() }
                }
            }
        } else {
            val result = runCatching { backButtonFocusRequester.requestFocus() }
            if (result.isFailure) {
                delay(16)
                runCatching { backButtonFocusRequester.requestFocus() }
            }
        }
    }

    EpgStationTvTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            colors = androidx.tv.material3.SurfaceDefaults.colors(
                containerColor = EpgStationThemeTokens.Background
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(aboutScrollState)
                    .padding(horizontal = 32.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header (Not focusable)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "EPGStation",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = EpgStationThemeTokens.OnSurface
                        )
                        Spacer(Modifier.width(10.dp))
                        TvBadge(
                            text = "アプリ情報",
                            backgroundColor = EpgStationThemeTokens.PrimaryContainer,
                            textColor = EpgStationThemeTokens.OnPrimaryContainer
                        )
                    }
                    Text(
                        text = "バージョン構成およびオープンソースライセンス",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EpgStationThemeTokens.OnSurfaceSecondary
                    )
                }

                if (state.aboutLoading && about == null) {
                    TvCard {
                        Text(
                            text = "アプリ情報を読み込み中…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = EpgStationThemeTokens.OnSurfaceSecondary
                        )
                    }
                } else if (about == null) {
                    TvCard {
                        Text(
                            text = "アプリ情報を取得できませんでした",
                            style = MaterialTheme.typography.bodyLarge,
                            color = EpgStationThemeTokens.StatusError
                        )
                    }
                } else {
                    // Section 1: Version Information & Repositories
                    TvCard {
                        Text(
                            text = "バージョン構成",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = EpgStationThemeTokens.OnSurface
                        )
                        Text(
                            text = "インストール済みアプリとEPGStationコアのバージョン情報です。",
                            style = MaterialTheme.typography.bodySmall,
                            color = EpgStationThemeTokens.OnSurfaceSecondary
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // First focusable row in About screen:
                        // DirectionUp scrolls up to reveal the header without losing focus or escaping
                        TvFocusableInfoRow(
                            label = "アプリバージョン",
                            value = about.appVersion,
                            modifier = Modifier.focusRequester(firstRowFocusRequester),
                            onDirectionUp = {
                                if (aboutScrollState.value > 0) {
                                    aboutScrollState.dispatchRawDelta(-160f)
                                }
                            }
                        )

                        TvFocusableInfoRow(
                            label = "EPGStation",
                            value = about.epgStationVersion
                        )

                        about.repositories.forEach { repo ->
                            TvFocusableInfoRow(
                                label = repo.label,
                                value = repo.url.removeSuffix(".git")
                            )
                        }
                    }

                    // Section 2: Open Source Licenses
                    TvCard {
                        Text(
                            text = "オープンソースライセンス",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = EpgStationThemeTokens.OnSurface
                        )
                        Text(
                            text = "同梱コンポーネントおよびランタイムのライセンス文書です。",
                            style = MaterialTheme.typography.bodySmall,
                            color = EpgStationThemeTokens.OnSurfaceSecondary
                        )
                    }

                    if (state.aboutLoading) {
                        Text(
                            text = "ライセンス本文を読み込み中…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = EpgStationThemeTokens.OnSurfaceMuted
                        )
                    }

                    if (about.missingLicenseDocuments.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(EpgStationThemeTokens.StatusWarningBg, RoundedCornerShape(8.dp))
                                .border(1.dp, EpgStationThemeTokens.StatusWarning.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "一部のライセンス文書を読み込めませんでした: ${about.missingLicenseDocuments.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = EpgStationThemeTokens.StatusWarning
                            )
                        }
                    }

                    if (about.licenseCoverageNote.isNotBlank()) {
                        Text(
                            text = about.licenseCoverageNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = EpgStationThemeTokens.OnSurfaceSecondary
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        about.licenses.forEach { license ->
                            key(license.id) {
                                val requester = licenseFocusRequesters.getOrPut(license.id) { FocusRequester() }
                                TvFocusableCard(
                                    onClick = { onOpenLicense(license.id) },
                                    modifier = Modifier.focusRequester(requester)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = license.title,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            color = EpgStationThemeTokens.OnSurface
                                        )
                                        TvBadge(
                                            text = "ライセンスを表示",
                                            backgroundColor = EpgStationThemeTokens.SecondaryContainer,
                                            textColor = EpgStationThemeTokens.OnSecondaryContainer
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Action row at bottom: "概要に戻る" button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    TvActionButton(
                        text = "概要に戻る",
                        onClick = onCloseAbout,
                        modifier = Modifier.focusRequester(backButtonFocusRequester)
                    )
                }

                // Safe bottom padding so the last item is never clipped
                Spacer(Modifier.height(36.dp))
            }
        }
    }
}

/**
 * Full-screen license viewer rendering chunked text through LazyColumn to avoid TV Skia freeze.
 */
@Composable
private fun LicenseViewerScreen(
    license: LicenseDocumentUi?,
    licenseId: String,
    onClose: () -> Unit
) {
    val lazyListState = rememberLazyListState()
    val viewportFocusRequester = remember { FocusRequester() }
    val backButtonFocusRequester = remember { FocusRequester() }
    var isViewportFocused by remember { mutableStateOf(false) }

    val chunks = remember(license?.body) {
        license?.body?.let { chunkLicenseText(it, linesPerChunk = 40) } ?: emptyList()
    }

    LaunchedEffect(licenseId) {
        viewportFocusRequester.requestFocus()
    }

    EpgStationTvTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            colors = androidx.tv.material3.SurfaceDefaults.colors(
                containerColor = EpgStationThemeTokens.Background
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header: Title & Back button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = license?.title ?: licenseId,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = EpgStationThemeTokens.OnSurface
                        )
                        Text(
                            text = "オープンソースライセンス全文",
                            style = MaterialTheme.typography.bodyMedium,
                            color = EpgStationThemeTokens.OnSurfaceSecondary
                        )
                    }
                    TvActionButton(
                        text = "ライセンス一覧に戻る",
                        onClick = onClose,
                        modifier = Modifier
                            .focusRequester(backButtonFocusRequester)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    when (event.key) {
                                        Key.DirectionDown -> {
                                            viewportFocusRequester.requestFocus()
                                            true
                                        }
                                        Key.DirectionUp -> {
                                            // Keep focus at top, do not escape
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

                // Scrollable License Text Viewport
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(
                            if (isViewportFocused) EpgStationThemeTokens.SurfaceFocused else EpgStationThemeTokens.SurfaceElevated,
                            RoundedCornerShape(8.dp)
                        )
                        .border(
                            width = if (isViewportFocused) 2.dp else 1.dp,
                            color = if (isViewportFocused) EpgStationThemeTokens.FocusStroke else EpgStationThemeTokens.BorderSubtle,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(14.dp)
                        .focusRequester(viewportFocusRequester)
                        .onFocusChanged { isViewportFocused = it.isFocused }
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionDown -> {
                                        if (lazyListState.canScrollForward) {
                                            lazyListState.dispatchRawDelta(240f)
                                        }
                                        true
                                    }
                                    Key.DirectionUp -> {
                                        if (lazyListState.canScrollBackward) {
                                            lazyListState.dispatchRawDelta(-240f)
                                        } else {
                                            backButtonFocusRequester.requestFocus()
                                        }
                                        true
                                    }
                                    Key.PageDown -> {
                                        lazyListState.dispatchRawDelta(800f)
                                        true
                                    }
                                    Key.PageUp -> {
                                        lazyListState.dispatchRawDelta(-800f)
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
                    if (license == null) {
                        Text(
                            text = "ライセンス文書が見つかりませんでした (ID: $licenseId)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = EpgStationThemeTokens.StatusError
                        )
                    } else if (chunks.isEmpty()) {
                        Text(
                            text = "ライセンス本文が空です",
                            style = MaterialTheme.typography.bodyMedium,
                            color = EpgStationThemeTokens.OnSurfaceMuted
                        )
                    } else {
                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(chunks.size) { index ->
                                Text(
                                    text = chunks[index],
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp
                                    ),
                                    color = if (isViewportFocused) EpgStationThemeTokens.OnSurface else EpgStationThemeTokens.OnSurfaceSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
