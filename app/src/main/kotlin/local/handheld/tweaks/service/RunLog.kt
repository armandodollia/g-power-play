package local.handheld.tweaks.service

import android.content.Context
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object RunLog {
    private const val MAX_LINES = 500
    private val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

    private fun file(ctx: Context) = File(ctx.filesDir, "run.log")

    @Synchronized
    fun append(ctx: Context, line: String) {
        val f = file(ctx)
        val lines = (if (f.exists()) f.readLines() else emptyList()) + "${LocalDateTime.now().format(fmt)} $line"
        f.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
    }

    @Synchronized
    fun read(ctx: Context): List<String> = file(ctx).let { if (it.exists()) it.readLines() else emptyList() }
}
