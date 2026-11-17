package local.handheld.tweaks.tile

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import local.handheld.tweaks.MainActivity
import local.handheld.tweaks.service.ServiceState
import local.handheld.tweaks.service.TweakService
import local.handheld.tweaks.shizuku.ShizukuGateway
import local.handheld.tweaks.shizuku.ShizukuState

class LandscapeTileService : TileService() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        ShizukuGateway.init(this)
        ShizukuGateway.refresh()
        job?.cancel()
        job = scope.launch {
            combine(
                ShizukuGateway.state, ServiceState.landscapeLocked, ServiceState.controllerConnected,
            ) { s, locked, controller -> Triple(s, locked, controller) }
                .collect { (s, locked, controller) -> render(s, locked, controller) }
        }
    }

    override fun onStopListening() {
        job?.cancel()
        job = null
        super.onStopListening()
    }

    private fun render(s: ShizukuState, locked: Boolean, controller: Boolean) {
        val tile = qsTile ?: return
        tile.label = "Landscape lock"
        tile.state = when {
            // Not UNAVAILABLE: SystemUI drops taps on unavailable tiles, and a tap must open the app.
            s != ShizukuState.READY -> Tile.STATE_INACTIVE
            locked -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = when {
            s != ShizukuState.READY -> "Shizuku needed"
            controller -> "Controller connected"
            else -> null
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (ShizukuGateway.state.value != ShizukuState.READY) {
            val pi = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pi)
            return
        }
        TweakService.start(this, TweakService.ACTION_TOGGLE_LANDSCAPE)
    }
}
