package com.mailtracker.cli

import com.mailtracker.auth.FaiTransport
import com.mailtracker.auth.Registration
import com.mailtracker.auth.TokenProvider
import com.mailtracker.db.Database
import com.mailtracker.source.EwsFaiSource
import com.mailtracker.source.ExchangeAdminRuleSource
import com.mailtracker.source.FaiBlobSource
import com.mailtracker.source.GraphMessageRuleSource
import com.mailtracker.source.GraphUserConfigFaiSource
import com.mailtracker.source.MailboxScanner
import com.github.ajalt.clikt.core.CliktError

/** Holds global CLI configuration shared from the root command to subcommands. */
class AppConfig {
    var dbPath: String = "mailtracker.db"
}

const val APP_VERSION = "0.1.0"

/** Shared wiring used by the scan/list/compare/delete commands. */
object Runtime {

    fun requireRegistration(db: Database): Registration =
        db.registration.get()
            ?: throw CliktError("No registration found. Run `mailtracker register` first.")

    /** Build a scanner from a saved registration, prompting for the keystore password. */
    fun buildScanner(reg: Registration): MailboxScanner {
        val password = Prompt.password("Keystore password", "MAILTRACKER_KEYSTORE_PASS")
        return buildScanner(reg, password)
    }

    fun buildScanner(reg: Registration, keystorePassword: String): MailboxScanner {
        val tokens = TokenProvider.create(reg, keystorePassword)
        val ruleSources = listOf(
            GraphMessageRuleSource(tokens),
            ExchangeAdminRuleSource(tokens, reg.tenantId),
        )
        val fai: FaiBlobSource = when (reg.faiTransport) {
            FaiTransport.EWS -> EwsFaiSource(tokens)
            FaiTransport.GRAPH_USERCONFIG -> GraphUserConfigFaiSource(tokens)
        }
        return MailboxScanner(ruleSources, fai)
    }
}
