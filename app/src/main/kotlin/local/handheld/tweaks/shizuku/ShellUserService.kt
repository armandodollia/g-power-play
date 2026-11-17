package local.handheld.tweaks.shizuku

import local.handheld.tweaks.IShellService
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Runs inside Shizuku's process as the shell user (uid 2000). */
class ShellUserService : IShellService.Stub() {
    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(cmd: String): String {
        val p = ProcessBuilder("/system/bin/sh", "-c", cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().use { out.append(it.readText()) } }.apply { start() }
        val finished = p.waitFor(20, TimeUnit.SECONDS)
        if (!finished) p.destroyForcibly()
        reader.join(1000)
        val exit = if (finished) p.exitValue() else 124
        return "$exit\n$out"
    }
}
