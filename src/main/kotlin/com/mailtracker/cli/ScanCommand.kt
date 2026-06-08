package com.mailtracker.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.mailtracker.db.Database
import java.time.Instant

/** op 2: scan a user's mail rules and store a timestamped snapshot. */
class ScanCommand : CliktCommand(name = "scan") {

    override fun help(context: Context) =
        "Retrieve all server-side and client/hidden mail rules for USER and store a snapshot."

    private val config by requireObject<AppConfig>()
    private val user by argument("user").help("Mailbox UPN / email address to scan")

    override fun run() {
        Database.open(config.dbPath).use { db ->
            val reg = Runtime.requireRegistration(db)
            val scanner = Runtime.buildScanner(reg)

            echo("Scanning $user ...")
            val result = scanner.scan(user)
            val now = Instant.now().toString()

            val u = db.users.getOrCreate(user, null, now)
            val scanId = db.scans.insert(u.id, now, result.sourceStatus, notes(result.errors.size))
            db.rules.insertAll(scanId, result.rules)

            echo("Stored scan #$scanId for ${u.upn}: ${result.rules.size} rules @ $now")
            result.sourceStatus.forEach { (k, v) -> echo("  $k: ${if (v) "ok" else "FAILED"}") }
            result.errors.forEach { (k, v) -> echo("  ! $k: $v") }
        }
    }

    private fun notes(errorCount: Int): String? =
        if (errorCount > 0) "$errorCount source(s) failed" else null
}
