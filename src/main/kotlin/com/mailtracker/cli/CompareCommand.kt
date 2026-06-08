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
import com.mailtracker.report.Diff
import com.mailtracker.report.Render

/** op 4: compare the latest scan against the previous one and report changed rules. */
class CompareCommand : CliktCommand(name = "compare") {

    override fun help(context: Context) =
        "Show mail rules that changed between USER's two most recent scans."

    private val config by requireObject<AppConfig>()
    private val user by argument("user").help("Mailbox UPN / email address")
    private val json by option("--json").flag().help("Emit JSON instead of human-readable output")

    override fun run() {
        Database.open(config.dbPath).use { db ->
            val u = db.users.findByUpn(user) ?: throw CliktError("No such user: $user")
            val headers = db.scans.headersForUser(u.id, u.upn, limit = 2)
            if (headers.size < 2) {
                throw CliktError(
                    "Need at least 2 scans to compare (have ${headers.size}). Run `mailtracker scan $user` again.",
                )
            }
            val after = headers[0]
            val before = headers[1]
            val diff = Diff.compute(
                before = db.rules.listForScan(before.id),
                after = db.rules.listForScan(after.id),
            )
            echo(
                if (json) Render.diffJson(u.upn, before, after, diff)
                else Render.diffHuman(u.upn, before, after, diff),
            )
        }
    }
}
