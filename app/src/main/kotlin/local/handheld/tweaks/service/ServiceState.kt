package local.handheld.tweaks.service

import kotlinx.coroutines.flow.MutableStateFlow
import local.handheld.tweaks.core.TweakResult
import local.handheld.tweaks.shizuku.ShizukuState

/** Shared state between the service, the tile and the status screen (same process). */
object ServiceState {
    val lastResults = MutableStateFlow<List<TweakResult>>(emptyList())
    val controllerConnected = MutableStateFlow(false)
    val landscapeLocked = MutableStateFlow(false)
    val shizuku = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val running = MutableStateFlow(false)
}
