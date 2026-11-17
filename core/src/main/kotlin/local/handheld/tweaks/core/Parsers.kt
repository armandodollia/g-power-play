package local.handheld.tweaks.core

fun parsePackageList(out: String): Set<String> =
    out.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("package:") }
        .map { it.removePrefix("package:") }
        .toSet()

private val appOpLine = Regex("""^(?:Uid mode:\s*)?([A-Z][A-Z0-9_]+):\s*([a-z_]+)""")

/** op -> mode. `appops get` lists uid modes then package modes; if either says ignore, ignore wins. */
fun parseAppOpModes(out: String): Map<String, String> {
    val modes = mutableMapOf<String, String>()
    out.lineSequence().forEach { raw ->
        val m = appOpLine.find(raw.trim()) ?: return@forEach
        val (op, mode) = m.destructured
        if (modes[op] != "ignore") modes[op] = mode
    }
    return modes
}

fun parseCompatEnabled(out: String, change: String, pkg: String): Boolean {
    val line = out.lineSequence().firstOrNull { it.contains("name=$change;") } ?: return false
    val overrides = Regex("""packageOverrides=\{([^}]*)\}""").find(line)?.groupValues?.get(1) ?: return false
    return overrides.split(",").map { it.trim() }.any { it == "$pkg=true" }
}

fun parseIdleWhitelist(out: String): Set<String> =
    out.lineSequence()
        .map { it.trim().split(",") }
        .filter { it.size >= 2 && it[1].isNotBlank() }
        .map { it[1] }
        .toSet()
