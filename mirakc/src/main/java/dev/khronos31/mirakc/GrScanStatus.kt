package dev.khronos31.mirakc

internal data class GrScanStatus(
    val state: State,
    val completed: Int,
    val total: Int,
    val currentChannel: Int?,
    val foundChannels: List<Int>,
    val failedChannels: Int,
    val scanId: Long,
    val errorCode: String?
) {
    enum class State { QUEUED, RUNNING, COMPLETE, EMPTY, FAILED, INTERRUPTED }

    val isRunning: Boolean get() = state == State.QUEUED || state == State.RUNNING
    val isIndeterminate: Boolean
        get() = state == State.QUEUED ||
            (state == State.RUNNING && completed == 0 && currentChannel == null)
    val isApplicable: Boolean
        get() = state == State.COMPLETE && failedChannels == 0 && foundChannels.isNotEmpty()

    fun asInterrupted(errorCode: String): GrScanStatus = copy(
        state = State.INTERRUPTED,
        currentChannel = null,
        errorCode = errorCode
    )

    fun summary(): String = when (state) {
        State.QUEUED -> "スキャン待機中"
        State.RUNNING -> "探索中: $completed/$total${currentChannel?.let { "（物理ch $it）" } ?: ""}・検出 ${foundChannels.size}ch"
        State.COMPLETE -> "検出: ${TerrestrialChannelSettings.inputText(foundChannels.map { TerrestrialChannel(it, "GR-$it") })}"
        State.EMPTY -> "受信可能なchがありません。既存設定は維持します"
        State.FAILED -> "探索失敗。部分結果は適用していません"
        State.INTERRUPTED -> "探索を中断しました。既存設定は維持します"
    }

    fun toFileContents(): String = buildString {
        append(state.name.lowercase()).append('\n')
        append(completed).append('\n')
        append(total).append('\n')
        append(currentChannel ?: "").append('\n')
        append(foundChannels.joinToString(",")).append('\n')
        append(failedChannels).append('\n')
        append(scanId).append('\n')
        append(errorCode ?: "NONE").append('\n')
    }

    companion object {
        private const val EXPECTED_TOTAL = 50

        fun queued(scanId: Long): GrScanStatus = GrScanStatus(
            State.QUEUED, 0, EXPECTED_TOTAL, null, emptyList(), 0, scanId, null
        )

        fun failed(scanId: Long, errorCode: String): GrScanStatus = GrScanStatus(
            State.FAILED, 0, EXPECTED_TOTAL, null, emptyList(), 0, scanId, errorCode
        )

        fun interrupted(scanId: Long): GrScanStatus = GrScanStatus(
            State.INTERRUPTED, 0, EXPECTED_TOTAL, null, emptyList(), 0, scanId, "CANCELED"
        )

        /** Retain the last same-request native progress when a running scan is canceled. */
        fun canceledFromNative(
            scanId: Long,
            stoppedStatus: GrScanStatus?,
            statusBeforeStop: GrScanStatus?
        ): GrScanStatus {
            val progress = sequenceOf(stoppedStatus, statusBeforeStop)
                .filterNotNull()
                .firstOrNull {
                    it.scanId == scanId && (it.isRunning || it.state == State.INTERRUPTED)
                }
            return progress?.asInterrupted("CANCELED") ?: interrupted(scanId)
        }

        /** Bounded line format written atomically by the pinned mirakc process. */
        fun parse(contents: String): GrScanStatus? {
            val fields = contents.trimEnd().split('\n')
            if (fields.size != 8) return null
            val state = when (fields[0]) {
                "queued" -> State.QUEUED
                "running" -> State.RUNNING
                "complete" -> State.COMPLETE
                "empty" -> State.EMPTY
                "failed" -> State.FAILED
                "interrupted" -> State.INTERRUPTED
                else -> return null
            }
            val completed = fields[1].toIntOrNull()?.takeIf { it in 0..EXPECTED_TOTAL } ?: return null
            val total = fields[2].toIntOrNull()?.takeIf { it == EXPECTED_TOTAL } ?: return null
            val current = fields[3].takeIf { it.isNotEmpty() }?.toIntOrNull()?.takeIf { it in 13..62 }
            if (fields[3].isNotEmpty() && current == null) return null
            val found = if (fields[4].isEmpty()) emptyList() else {
                fields[4].split(',').map { it.toIntOrNull()?.takeIf { channel -> channel in 13..62 } ?: return null }
                    .distinct().sorted()
            }
            val failed = fields[5].toIntOrNull()?.takeIf { it in 0..EXPECTED_TOTAL } ?: return null
            val scanId = fields[6].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val errorCode = when {
                fields[7] == "NONE" -> null
                fields[7].matches(Regex("[A-Z0-9_]{1,40}")) -> fields[7]
                else -> return null
            }
            // A complete scan can replace the user's channel list only when
            // mirakc reports all 50 channels scanned without errors and with
            // at least one detected channel. Empty results use State.EMPTY.
            if (state == State.COMPLETE &&
                (completed != total || failed != 0 || found.isEmpty() || errorCode != null)
            ) return null
            return GrScanStatus(state, completed, total, current, found, failed, scanId, errorCode)
        }
    }
}
