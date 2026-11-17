package local.handheld.tweaks.core

fun interface Shell {
    fun run(cmd: String): ShellResult
}

data class ShellResult(val exit: Int, val out: String)

enum class TweakId { REFRESH_120, DEBLOAT, NEUTER_PROTECTED, CUTOUT_OVERRIDE, IDLE_WHITELIST }

enum class TweakStatus { APPLIED, REAPPLIED, PROTECTED, FAILED }

data class TweakResult(val id: TweakId, val status: TweakStatus, val detail: String)

object Config {
    val debloat: List<String> by lazy {
        val stream = Config::class.java.classLoader.getResourceAsStream("debloat-packages.txt")
            ?: error("debloat-packages.txt missing from resources")
        stream.bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    val protected: List<String> = listOf(
        "com.tracfone.preload.accountservices",
        "com.amazon.appmanager",
        "com.motorola.paks",
        "com.motorola.paks.notification",
    )

    val protectedOps: List<String> = listOf("POST_NOTIFICATION", "RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND")

    val cutoutPkgs: List<String> = listOf("com.nendo.argosy")

    const val CUTOUT_CHANGE = "OVERRIDE_LAYOUT_IN_DISPLAY_CUTOUT_MODE"

    fun idleWhitelist(ownPkg: String): List<String> =
        (listOf(ownPkg, "com.nendo.argosy") + cutoutPkgs).distinct()
}

object Rotation {
    val LANDSCAPE: List<String> = listOf(
        "settings put system accelerometer_rotation 0",
        "settings put system user_rotation 3",
        "wm fixed-to-user-rotation enabled",
    )
    val NORMAL: List<String> = listOf(
        "wm fixed-to-user-rotation default",
        "settings put system accelerometer_rotation 1",
    )

    fun isLandscapeLocked(fixedOut: String, userRotation: String): Boolean =
        fixedOut.trim() == "enabled" && userRotation.trim() == "3"
}
