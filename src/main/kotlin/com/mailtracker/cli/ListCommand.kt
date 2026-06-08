package com.mailtracker.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.mailtracker.db.Database
import com.mailtracker.model.Scan
import com.mailtracker.report.Render

/** op 5: verbose listing of every rule from the latest scan. */
class ListCommand : CliktCommand(name = "list") {

    override fun help(context: Context) = "List every mail rule from USER's most recent scan."

    private val config by requireObject<AppConfig>()
    private val user by argument("user").help("Mailbox UPN / email address")
    private val json by option("--json").flag().help("Emit JSON instead of human-readable output")

    override fun run() {
        Database.open(config.dbPath).use { db ->
            val u = db.users.findByUpn(user) ?: throw CliktError("No such user: $user")
            val header = db.scans.headersForUser(u.id, u.upn, limit = 1).firstOrNull()
                ?: throw CliktError("No scans recorded for $user. Run `mailtracker scan $user`.")
            val rules = db.rules.listForScan(header.id)
            val scan = Scan(header.copy(ruleCount = rules.size), rules)
            echo(if (json) Render.scanJson(scan) else Render.scanHuman(scan))
        }
    }
}
