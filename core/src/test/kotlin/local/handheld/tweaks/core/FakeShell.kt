package local.handheld.tweaks.core

/** Answers by longest matching command prefix; records every command. */
class FakeShell(private val answers: MutableMap<String, ShellResult> = mutableMapOf()) : Shell {
    val ran = mutableListOf<String>()
    val throwOn = mutableSetOf<String>()

    fun on(prefix: String, out: String, exit: Int = 0) = apply { answers[prefix] = ShellResult(exit, out) }

    override fun run(cmd: String): ShellResult {
        ran += cmd
        if (throwOn.any { cmd.startsWith(it) }) throw IllegalStateException("boom: $cmd")
        val key = answers.keys.filter { cmd.startsWith(it) }.maxByOrNull { it.length }
        return key?.let { answers.getValue(it) } ?: ShellResult(0, "")
    }
}
