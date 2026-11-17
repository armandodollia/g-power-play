package local.handheld.tweaks.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.input.InputManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.InputDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import local.handheld.tweaks.core.Cancellable
import local.handheld.tweaks.core.ControllerTracker
import local.handheld.tweaks.core.Reconciler
import local.handheld.tweaks.core.Rotation
import local.handheld.tweaks.core.Shell
import local.handheld.tweaks.core.TweakStatus
import local.handheld.tweaks.core.isController
import local.handheld.tweaks.shizuku.ShizukuGateway
import local.handheld.tweaks.shizuku.ShizukuState

class TweakService : Service() {

    companion object {
        const val ACTION_REAPPLY = "local.handheld.tweaks.REAPPLY"
        const val ACTION_TOGGLE_LANDSCAPE = "local.handheld.tweaks.TOGGLE"

        fun start(ctx: Context, action: String? = null) {
            ctx.startForegroundService(Intent(ctx, TweakService::class.java).setAction(action))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val shellLock = Mutex()
    private val reconcileLock = Mutex()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var inputManager: InputManager

    private val tracker = ControllerTracker(
        debounceMs = 3000,
        onChange = { connected ->
            ServiceState.controllerConnected.value = connected
            log("controller ${if (connected) "connected" else "disconnected"}")
            scope.launch { applyRotation(landscape = connected) }
        },
        schedule = { delay, block ->
            val r = Runnable(block)
            handler.postDelayed(r, delay)
            Cancellable { handler.removeCallbacks(r) }
        },
    )

    private val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) {
            tracker.added(id, deviceIsController(id))
        }
        override fun onInputDeviceRemoved(id: Int) = tracker.removed(id)
        override fun onInputDeviceChanged(id: Int) = Unit
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForeground(
            Notifications.ONGOING_ID,
            Notifications.ongoing(this, "Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        ServiceState.running.value = true
        ShizukuGateway.init(this)
        inputManager = getSystemService(InputManager::class.java)
        tracker.reset(currentControllers())
        ServiceState.controllerConnected.value = tracker.connected
        inputManager.registerInputDeviceListener(inputListener, handler)

        log("service started; waiting for Shizuku")
        scope.launch {
            var previous: ShizukuState? = null
            ShizukuGateway.state.collect { s ->
                ServiceState.shizuku.value = s
                refreshNotification()
                // Every time Shizuku becomes usable (first time, restart, Wi-Fi drop, permission granted
                // later) the tweaks are re-checked. Rotation is left alone here: only service start,
                // attach/detach and the tile touch it.
                if (s == ShizukuState.READY && previous != ShizukuState.READY) launch { reconcileWithRetry() }
                previous = s
            }
        }
        scope.launch { ServiceState.controllerConnected.collect { refreshNotification() } }
        scope.launch { applyRotation(landscape = tracker.connected) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REAPPLY -> scope.launch {
                reconcileWithRetry()
                applyRotation(landscape = tracker.connected)
            }
            ACTION_TOGGLE_LANDSCAPE -> scope.launch { toggle() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        inputManager.unregisterInputDeviceListener(inputListener)
        ServiceState.running.value = false
        scope.cancel()
        super.onDestroy()
    }

    private fun deviceIsController(id: Int): Boolean {
        val d = InputDevice.getDevice(id) ?: return false
        return isController(d.sources, d.isExternal)
    }

    private fun currentControllers(): Set<Int> =
        inputManager.inputDeviceIds.filter { deviceIsController(it) }.toSet()

    private fun refreshNotification() {
        val text = when (ServiceState.shizuku.value) {
            ShizukuState.READY ->
                "Controller: ${if (ServiceState.controllerConnected.value) "connected" else "not connected"}"
            ShizukuState.NO_PERMISSION -> "Shizuku permission needed — tap to open"
            ShizukuState.NOT_RUNNING -> "Waiting for Shizuku"
        }
        Notifications.updateOngoing(this, text)
    }

    private suspend fun <T> withShell(block: (Shell) -> T): T {
        val shell = ShizukuGateway.shell()
        return shellLock.withLock { kotlinx.coroutines.withContext(Dispatchers.IO) { block(shell) } }
    }

    /** One reconcile at a time; a run that can't reach Shizuku is retried up to 3 times, 5 s apart. */
    private suspend fun reconcileWithRetry() {
        if (!reconcileLock.tryLock()) return
        try {
            for (attempt in 1..3) {
                val error = reconcile() ?: return
                log("reconcile error (attempt $attempt/3): $error")
                if (attempt == 3) {
                    Notifications.alert(this, "Handheld Tweaks: reconcile failed", error)
                } else {
                    delay(5000)
                }
            }
        } finally {
            reconcileLock.unlock()
        }
    }

    /** Returns null when the run completed (even with FAILED tweaks), or the error that stopped it. */
    private suspend fun reconcile(): String? {
        val results = try {
            withShell { Reconciler(it, packageName).reconcileAll() }
        } catch (e: Exception) {
            return e.message ?: e.javaClass.simpleName
        }
        ServiceState.lastResults.value = results
        results.forEach { log("${it.id} ${it.status} ${it.detail}") }
        val failed = results.filter { it.status == TweakStatus.FAILED }
        if (failed.isNotEmpty()) {
            Notifications.alert(
                this, "Handheld Tweaks: ${failed.size} tweak(s) failed",
                failed.joinToString("\n") { "${it.id}: ${it.detail}" },
            )
        }
        return null
    }

    private suspend fun applyRotation(landscape: Boolean) {
        try {
            val locked = withShell { shell ->
                (if (landscape) Rotation.LANDSCAPE else Rotation.NORMAL).forEach { cmd ->
                    val r = shell.run(cmd)
                    if (r.exit != 0) error("`$cmd` exit ${r.exit}: ${r.out.trim()}")
                }
                readLocked(shell)
            }
            ServiceState.landscapeLocked.value = locked
            log("rotation ${if (landscape) "landscape-locked" else "normal"}")
        } catch (e: Exception) {
            log("rotation error: ${e.message}")
            Notifications.alert(this, "Handheld Tweaks: rotation failed", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun readLocked(shell: Shell) = Rotation.isLandscapeLocked(
        shell.run("wm fixed-to-user-rotation").out,
        shell.run("settings get system user_rotation").out,
    )

    private suspend fun toggle() {
        val locked = try {
            withShell { readLocked(it) }
        } catch (e: Exception) {
            log("toggle error: ${e.message}")
            return
        }
        log("tile toggle")
        applyRotation(landscape = !locked)
    }

    private fun log(line: String) = RunLog.append(this, line)
}
