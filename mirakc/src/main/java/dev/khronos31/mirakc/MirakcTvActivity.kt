package dev.khronos31.mirakc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.khronos31.mirakc.ui.MirakcTvScreen
import dev.khronos31.mirakc.ui.contract.AboutUi
import dev.khronos31.mirakc.ui.contract.MirakcUiAction
import dev.khronos31.mirakc.ui.contract.MirakcUiState
import dev.khronos31.mirakc.ui.contract.RuntimePhase
import dev.khronos31.mirakc.ui.contract.SettingsUi
import dev.khronos31.mirakc.ui.contract.UiCapabilities

/** Thin Compose host; all runtime ownership stays in MirakcService. */
class MirakcTvActivity : ComponentActivity() {
    private var controller: MirakcUiController? = null
    private var detachObserver: AutoCloseable? = null
    private var uiState by mutableStateOf(connectingState())
    private var activeConnection: ServiceConnection? = null
    private var bindingGeneration = 0L
    private var started = false
    private var bound = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MirakcTvScreen(
                state = uiState,
                onAction = ::dispatch
            )
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        uiState = connectingState()
        val generation = ++bindingGeneration
        val serviceIntent = Intent(this, MirakcService::class.java).setAction(MirakcService.ACTION_HOST)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (!started || generation != bindingGeneration) return
                val service = binder as? MirakcService.LocalBinder ?: return
                detachObserver?.close()
                val attachedController = service.controller()
                controller = attachedController
                detachObserver = attachedController.attach { snapshot ->
                    runOnUiThread {
                        if (started && generation == bindingGeneration && controller === attachedController) {
                            uiState = snapshot
                        }
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!started || generation != bindingGeneration) return
                detachObserver?.close()
                detachObserver = null
                controller = null
                uiState = connectingState()
            }
        }
        activeConnection = connection
        bound = bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            activeConnection = null
            uiState = connectingState()
        }
    }

    override fun onStop() {
        started = false
        bindingGeneration++
        detachObserver?.close()
        detachObserver = null
        controller = null
        uiState = connectingState()
        if (bound) activeConnection?.let { unbindService(it) }
        bound = false
        activeConnection = null
        super.onStop()
    }

    private fun dispatch(action: MirakcUiAction) {
        if (started) controller?.dispatch(action)
    }

    private fun connectingState() = MirakcUiState(
        phase = RuntimePhase.PREPARING,
        statusTitle = "mirakcに接続しています",
        statusDetail = "サービスの状態を確認しています",
        devices = emptyList(),
        scan = null,
        prepared = null,
        capabilities = UiCapabilities(),
        settings = SettingsUi("", EpgUpdateIntervalSettings.DEFAULT_MINUTES),
        about = AboutUi("", emptyList(), emptyList(), "", "")
    )
}
