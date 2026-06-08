package com.mailtracker

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.findOrSetObject
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.mailtracker.cli.AppConfig
import com.mailtracker.cli.CompareCommand
import com.mailtracker.cli.DeleteCommand
import com.mailtracker.cli.ListCommand
import com.mailtracker.cli.RegisterCommand
import com.mailtracker.cli.ScanCommand

class Mailtracker : CliktCommand(name = "mailtracker") {

    override fun help(context: Context) =
        "Track and compare Office 365 mailbox (inbox) rules per user for a single tenant."

    private val db by option("--db")
        .default("mailtracker.db")
        .help("Path to the SQLite database (single tenant per file)")

    private val appConfig by findOrSetObject { AppConfig() }

    override fun run() {
        appConfig.dbPath = db
    }
}

fun main(args: Array<String>) = Mailtracker()
    .subcommands(
        RegisterCommand(),
        ScanCommand(),
        DeleteCommand(),
        CompareCommand(),
        ListCommand(),
    )
    .main(args)
