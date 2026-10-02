package dev.khronos31.epgstation.server

/** Interprets service starts so Activity recreation cannot restart a running Node process. */
internal object EpgStationStartPolicy {
    const val ACTION_RESTART = "dev.khronos31.epgstation.server.action.RESTART"

    fun handle(action: String?, restartNode: () -> Unit): Boolean {
        if (action != ACTION_RESTART) return false
        restartNode()
        return true
    }
}
