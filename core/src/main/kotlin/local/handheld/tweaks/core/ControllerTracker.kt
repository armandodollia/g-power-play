package local.handheld.tweaks.core

private const val SOURCE_GAMEPAD = 0x00000401
private const val SOURCE_JOYSTICK = 0x01000010

fun isController(sources: Int, isExternal: Boolean): Boolean =
    isExternal && (sources and SOURCE_GAMEPAD == SOURCE_GAMEPAD || sources and SOURCE_JOYSTICK == SOURCE_JOYSTICK)

fun interface Cancellable {
    fun cancel()
}

/**
 * Tracks attached controllers. Attach is reported immediately; detach only after [debounceMs]
 * with no controller attached, so reseats and back-to-back device removals produce one event.
 */
class ControllerTracker(
    private val debounceMs: Long = 3000,
    private val onChange: (connected: Boolean) -> Unit,
    private val schedule: (delayMs: Long, block: () -> Unit) -> Cancellable,
) {
    private val ids = mutableSetOf<Int>()
    private var pendingDetach: Cancellable? = null

    var connected: Boolean = false
        private set

    fun reset(controllerIds: Set<Int>) {
        pendingDetach?.cancel()
        pendingDetach = null
        ids.clear()
        ids += controllerIds
        connected = ids.isNotEmpty()
    }

    fun added(id: Int, isController: Boolean) {
        if (!isController) return
        ids += id
        pendingDetach?.cancel()
        pendingDetach = null
        if (!connected) {
            connected = true
            onChange(true)
        }
    }

    fun removed(id: Int) {
        if (!ids.remove(id) || ids.isNotEmpty() || !connected) return
        pendingDetach?.cancel()
        pendingDetach = schedule(debounceMs) {
            pendingDetach = null
            if (ids.isEmpty() && connected) {
                connected = false
                onChange(false)
            }
        }
    }
}
