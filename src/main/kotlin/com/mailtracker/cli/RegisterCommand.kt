package com.mailtracker.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.mailtracker.auth.CertKeystore
import com.mailtracker.auth.FaiTransport
import com.mailtracker.auth.Registration
import com.mailtracker.auth.TokenProvider
import com.mailtracker.db.Database
import java.io.File
import java.time.Instant

/**
 * op 1: guided manual registration wizard. Prints the exact Entra/Exchange admin steps, captures
 * the resulting credentials, validates them live, and persists the single-tenant registration row.
 */
class RegisterCommand : CliktCommand(name = "register") {

    override fun help(context: Context) =
        "Interactively register the Entra app + certificate and create the tenant database."

    private val config by requireObject<AppConfig>()
    private val force by option("--force").flag()
        .help("Allow registering a different tenant into an existing database")

    override fun run() {
        echo(INSTRUCTIONS)

        val tenantId = Prompt.line("Tenant ID (GUID)").lowercase()
        if (tenantId.isBlank()) throw CliktError("Tenant ID is required.")
        val clientId = Prompt.line("Application (client) ID")
        if (clientId.isBlank()) throw CliktError("Client ID is required.")
        val keystorePath = Prompt.line("PKCS#12 keystore path (.pfx/.p12)")
        if (!File(keystorePath).isFile) throw CliktError("Keystore not found: $keystorePath")
        val password = Prompt.password("Keystore password", "MAILTRACKER_KEYSTORE_PASS")

        val cert = try {
            CertKeystore.inspect(keystorePath, password)
        } catch (e: Exception) {
            throw CliktError("Could not read keystore: ${e.message}")
        }
        echo("Certificate: subject=${cert.subject}")
        echo("             thumbprint=${cert.thumbprintSha1}  notAfter=${cert.notAfter}")

        val transport = when (Prompt.lineOrDefault("FAI transport for client rules (ews|graph)", "ews").lowercase()) {
            "graph", "graph_userconfig" -> FaiTransport.GRAPH_USERCONFIG
            else -> FaiTransport.EWS
        }

        val reg = Registration(
            tenantId = tenantId,
            clientId = clientId,
            certThumbprint = cert.thumbprintSha1,
            keystorePath = File(keystorePath).absolutePath,
            authority = Registration.authorityFor(tenantId),
            scopes = Registration.DEFAULT_SCOPES,
            faiTransport = transport,
            appVersion = APP_VERSION,
            createdAt = Instant.now().toString(),
        )

        Database.open(config.dbPath).use { db ->
            val existing = db.registration.get()
            if (existing != null && existing.tenantId != tenantId && !force) {
                throw CliktError(
                    "This database is already registered to tenant ${existing.tenantId}. " +
                        "Use a new --db file, or pass --force to overwrite.",
                )
            }

            validate(reg, password)

            db.registration.upsert(reg)
            echo("Registration saved to ${config.dbPath}.")
        }
    }

    /** Acquire a token per resource, then optionally probe a real mailbox end-to-end. */
    private fun validate(reg: Registration, password: String) {
        echo("Validating credentials ...")
        val tokens = try {
            TokenProvider.create(reg, password)
        } catch (e: Exception) {
            throw CliktError("Failed to initialise auth: ${e.message}")
        }
        for (resource in reg.scopes.keys) {
            val outcome = runCatching { tokens.tokenForResource(resource) }
            echo("  token[$resource]: ${if (outcome.isSuccess) "ok" else "FAILED — ${outcome.exceptionOrNull()?.message}"}")
        }

        val probe = Prompt.line("Optional: a mailbox UPN to probe each source now (blank to skip)")
        if (probe.isNotBlank()) {
            val result = Runtime.buildScanner(reg, password).scan(probe)
            result.sourceStatus.forEach { (k, v) -> echo("  probe[$k]: ${if (v) "ok" else "FAILED"}") }
            result.errors.forEach { (k, v) -> echo("    ! $k: $v") }
        }
    }

    companion object {
        private val INSTRUCTIONS = """
            |mailtracker registration — perform these one-time steps in the Microsoft admin portals:
            |
            |  1. Entra admin center → App registrations → New registration. Note the
            |     Directory (tenant) ID and Application (client) ID.
            |  2. Certificates & secrets → upload your certificate's PUBLIC key (.cer). Keep the
            |     matching PKCS#12 (.pfx/.p12) private key on this machine for mailtracker.
            |  3. API permissions → add APPLICATION permissions and Grant admin consent:
            |        • Microsoft Graph: MailboxSettings.Read   (server-side rules)
            |        • Microsoft Graph: MailboxConfigItem.Read (future FAI transport)
            |        • Office 365 Exchange Online: Exchange.ManageAsApp (Exchange Admin API /
            |          Get-InboxRule; preview Admin API may list it as Exchange.ManageAsAppV2)
            |        • Office 365 Exchange Online: full_access_as_app (EWS FAI transport, today)
            |  4. Assign an ENTRA DIRECTORY ROLE to the app (Roles & admins → Roles &
            |     administrators → Add assignments): Exchange Recipient Administrator, or
            |     Exchange Administrator if that proves too low for Get-InboxRule. This is
            |     SEPARATE from Exchange.ManageAsApp above — that permission lets the app reach
            |     the Exchange management endpoint; the directory role lets it run the cmdlet
            |     (a custom Exchange management role does NOT work for Get-InboxRule).
            |  5. (Recommended) scope mailbox access via RBAC for Applications, and add the app to
            |     the EWS AppID allow list while EWS is in use.
            |
            |Then answer the prompts below.
            |
        """.trimMargin()
    }
}
