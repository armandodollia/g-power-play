package local.handheld.tweaks.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import local.handheld.tweaks.BuildConfig
import local.handheld.tweaks.IShellService
import local.handheld.tweaks.core.Shell
import local.handheld.tweaks.core.ShellResult
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

enum class ShizukuState { NOT_RUNNING, NO_PERMISSION, READY }

object ShizukuGateway {
    private const val REQUEST_CODE = 4210
    private val _state = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val state: StateFlow<ShizukuState> = _state

    private lateinit var appContext: Context
    private var service: IShellService? = null
    private val bindLock = Mutex()
    private var initialized = false

    @Synchronized
    fun init(ctx: Context) {
        if (initialized) return
        initialized = true
        appContext = ctx.applicationContext
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener {
            synchronized(this) { service = null }
            _state.value = ShizukuState.NOT_RUNNING
        }
        Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
        refresh()
    }

    /** Re-reads Shizuku's state; call when the UI resumes, since some permission changes send no callback. */
    fun refresh() {
        _state.value = when {
            !Shizuku.pingBinder() -> ShizukuState.NOT_RUNNING
            Shizuku.isPreV11() -> ShizukuState.NOT_RUNNING
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuState.NO_PERMISSION
            else -> ShizukuState.READY
        }
    }

    fun requestPermission() {
        if (Shizuku.pingBinder()) Shizuku.requestPermission(REQUEST_CODE)
    }

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(appContext, ShellUserService::class.java))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            // A new value per install makes Shizuku replace the previous install's (dead) service
            // instead of trying to reuse it, which left the first bind after an update hanging.
            .version(installStamp())
    }

    private fun installStamp(): Int =
        (appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime / 1000 % Int.MAX_VALUE).toInt()

    /** Suspends until Shizuku is running and permitted, then returns a shell running as uid 2000. */
    suspend fun shell(): Shell {
        state.first { it == ShizukuState.READY }
        val svc = bindLock.withLock { service ?: bind().also { service = it } }
        return Shell { cmd ->
            val raw = try {
                svc.exec(cmd)
            } catch (e: Exception) {
                service = null
                throw e
            }
            val nl = raw.indexOf('\n')
            ShellResult(raw.substring(0, nl).toIntOrNull() ?: -1, raw.substring(nl + 1))
        }
    }

    /** Binds the shell user service; gives up after 10 s so a bind that never connects can't hang every caller. */
    private suspend fun bind(): IShellService {
        var conn: ServiceConnection? = null
        try {
            return withTimeout(10_000) {
                suspendCancellableCoroutine { cont ->
                    val c = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                            if (cont.isActive) cont.resume(IShellService.Stub.asInterface(binder))
                        }
                        override fun onServiceDisconnected(name: ComponentName) {
                            synchronized(this@ShizukuGateway) { service = null }
                        }
                    }
                    conn = c
                    Shizuku.bindUserService(args, c)
                }
            }
        } catch (e: Exception) {
            conn?.let { runCatching { Shizuku.unbindUserService(args, it, true) } }
            throw IllegalStateException("Shizuku shell service did not connect: ${e.message}", e)
        }
    }
}
