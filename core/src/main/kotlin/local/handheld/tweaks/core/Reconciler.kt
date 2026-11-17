package local.handheld.tweaks.core

/**
 * Checks each tweak and reapplies only what drifted. One tweak failing never stops the others.
 * Never force-stops, kills, or clears any package.
 */
class Reconciler(private val shell: Shell, private val ownPkg: String) {

    private class Failed(msg: String) : Exception(msg)

    fun reconcileAll(): List<TweakResult> {
        val installed: Set<String>? = runCatching { parsePackageList(read("pm list packages --user 0")) }.getOrNull()
        return TweakId.entries.map { id ->
            try {
                when (id) {
                    TweakId.REFRESH_120 -> refresh()
                    TweakId.DEBLOAT -> debloat(installed ?: throw Failed("could not list packages"))
                    TweakId.NEUTER_PROTECTED -> neuter(installed ?: throw Failed("could not list packages"))
                    TweakId.CUTOUT_OVERRIDE -> cutout(installed ?: throw Failed("could not list packages"))
                    TweakId.IDLE_WHITELIST -> idle(installed ?: throw Failed("could not list packages"))
                }
            } catch (e: Exception) {
                TweakResult(id, TweakStatus.FAILED, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun read(cmd: String): String = shell.run(cmd).out

    private fun apply(cmd: String) {
        val r = shell.run(cmd)
        if (r.exit != 0) throw Failed("`$cmd` exit ${r.exit}: ${r.out.trim()}")
    }

    private fun applied(id: TweakId, changed: List<String>) =
        if (changed.isEmpty()) TweakResult(id, TweakStatus.APPLIED, "ok")
        else TweakResult(id, TweakStatus.REAPPLIED, changed.joinToString(", "))

    private fun refresh(): TweakResult {
        val keys = listOf("min_refresh_rate", "peak_refresh_rate")
        val drifted = keys.any { read("settings get system $it").trim() != "120.0" }
        if (!drifted) return applied(TweakId.REFRESH_120, emptyList())
        keys.forEach { apply("settings put system $it 120.0") }
        return applied(TweakId.REFRESH_120, listOf("refresh rate set to 120"))
    }

    private fun debloat(installed: Set<String>): TweakResult {
        val removed = mutableListOf<String>()
        val protected = mutableListOf<String>()
        Config.debloat.filter { it in installed && it !in Config.protected }.forEach { pkg ->
            val r = shell.run("pm uninstall -k --user 0 $pkg")
            when {
                r.out.contains("Success") -> removed += pkg
                r.out.contains("not installed for") -> Unit
                r.out.contains("non-disable") -> protected += pkg
                else -> throw Failed("`pm uninstall $pkg` exit ${r.exit}: ${r.out.trim()}")
            }
        }
        if (protected.isNotEmpty()) {
            return TweakResult(TweakId.DEBLOAT, TweakStatus.PROTECTED, "protected: ${protected.joinToString()}" +
                if (removed.isEmpty()) "" else "; removed: ${removed.joinToString()}")
        }
        return applied(TweakId.DEBLOAT, removed.map { "removed $it" })
    }

    private fun neuter(installed: Set<String>): TweakResult {
        val changed = mutableListOf<String>()
        Config.protected.filter { it in installed }.forEach { pkg ->
            val modes = parseAppOpModes(read("appops get $pkg"))
            val missing = Config.protectedOps.filter { modes[it] != "ignore" }
            if (missing.isEmpty()) return@forEach
            shell.run("pm revoke $pkg android.permission.POST_NOTIFICATIONS") // may fail if not requested; fine
            missing.forEach { apply("appops set $pkg $it ignore") }
            changed += "$pkg (${missing.joinToString()})"
        }
        return applied(TweakId.NEUTER_PROTECTED, changed)
    }

    private fun cutout(installed: Set<String>): TweakResult {
        val compat = read("dumpsys platform_compat")
        val changed = Config.cutoutPkgs
            .filter { it in installed && !parseCompatEnabled(compat, Config.CUTOUT_CHANGE, it) }
            .onEach { apply("am compat enable --no-kill ${Config.CUTOUT_CHANGE} $it") }
        return applied(TweakId.CUTOUT_OVERRIDE, changed)
    }

    private fun idle(installed: Set<String>): TweakResult {
        val listed = parseIdleWhitelist(read("dumpsys deviceidle whitelist"))
        val changed = Config.idleWhitelist(ownPkg)
            .filter { it in installed && it !in listed }
            .onEach { apply("dumpsys deviceidle whitelist +$it") }
        return applied(TweakId.IDLE_WHITELIST, changed)
    }
}
