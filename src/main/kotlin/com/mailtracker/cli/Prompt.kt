package com.mailtracker.cli

/** Small stdin/console helpers for the interactive registration wizard and confirmations. */
object Prompt {

    fun line(label: String): String {
        print("$label: ")
        System.out.flush()
        return readlnOrNull()?.trim().orEmpty()
    }

    fun lineOrDefault(label: String, default: String): String {
        val v = line("$label [$default]")
        return v.ifBlank { default }
    }

    /** Read a secret without echo when a console is available; otherwise fall back to env/stdin. */
    fun password(label: String, envVar: String): String {
        System.getenv(envVar)?.takeIf { it.isNotBlank() }?.let { return it }
        val console = System.console()
        return if (console != null) String(console.readPassword("$label: ")) else line(label)
    }

    fun confirm(label: String): Boolean {
        print("$label [y/N]: ")
        System.out.flush()
        return readlnOrNull()?.trim()?.lowercase() in setOf("y", "yes")
    }
}
