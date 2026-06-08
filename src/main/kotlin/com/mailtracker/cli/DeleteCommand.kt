package com.mailtracker.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.mailtracker.db.Database

/** op 3: delete a user and all of its recorded scans (cascades to rules). */
class DeleteCommand : CliktCommand(name = "delete") {

    override fun help(context: Context) = "Delete USER and every stored scan/rule for that user."

    private val config by requireObject<AppConfig>()
    private val user by argument("user").help("Mailbox UPN / email address to delete")
    private val yes by option("--yes", "-y").flag().help("Skip the confirmation prompt")

    override fun run() {
        Database.open(config.dbPath).use { db ->
            if (!yes && !Prompt.confirm("Delete '$user' and all its scans?")) {
                echo("Aborted.")
                return
            }
            val removed = db.users.delete(user)
            echo(if (removed) "Deleted $user and all its scans." else "No such user: $user")
        }
    }
}
