package com.mailtracker.source

import com.mailtracker.auth.Registration
import com.mailtracker.auth.TokenProvider
import com.mailtracker.model.MailRule
import com.mailtracker.model.RuleOrigin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Server-side rules via the Exchange Online Admin API, which runs the `Get-InboxRule` cmdlet:
 *   POST https://outlook.office365.com/adminapi/beta/{tenantId}/InvokeCommand
 * Requires the app to hold an Exchange RBAC role granting Get-InboxRule. Used to cross-check and
 * enrich the Graph results. The full cmdlet object is retained under `conditions` so nothing the
 * cmdlet returned is dropped.
 */
class ExchangeAdminRuleSource(
    private val tokens: TokenProvider,
    private val tenantId: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : RuleSource {

    override val name = "exchange_admin"

    override fun fetch(upn: String): List<MailRule> {
        val token = tokens.tokenForResource(Registration.EXCHANGE_ADMIN)
        val url = "https://outlook.office365.com/adminapi/beta/$tenantId/InvokeCommand"
        val payload = buildJsonObject {
            putJsonObject("CmdletInput") {
                put("CmdletName", "Get-InboxRule")
                putJsonObject("Parameters") {
                    put("Mailbox", upn)
                }
            }
        }
        val body = Http.post(
            url = url,
            bearer = token,
            body = json.encodeToString(JsonElement.serializer(), payload),
            contentType = "application/json",
        )
        val root = json.parseToJsonElement(body).jsonObject
        val values = root["value"]?.jsonArray ?: return emptyList()
        return values.map { el ->
            val o = el.jsonObject
            MailRule(
                origin = RuleOrigin.SERVER,
                externalId = (o["Identity"] ?: o["RuleIdentity"])?.jsonPrimitive?.contentOrNull,
                name = o["Name"]?.jsonPrimitive?.contentOrNull,
                sequence = o["Priority"]?.jsonPrimitive?.intOrNull,
                enabled = o["Enabled"]?.jsonPrimitive?.booleanOrNull,
                provider = name,
                // Retain the full cmdlet object verbatim — Get-InboxRule spreads conditions across
                // many top-level properties that we don't individually model.
                conditions = el,
            )
        }
    }
}
