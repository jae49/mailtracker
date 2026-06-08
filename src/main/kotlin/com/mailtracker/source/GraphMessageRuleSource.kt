package com.mailtracker.source

import com.mailtracker.auth.Registration
import com.mailtracker.auth.TokenProvider
import com.mailtracker.model.MailRule
import com.mailtracker.model.RuleOrigin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Server-side inbox rules via Microsoft Graph:
 *   GET /v1.0/users/{upn}/mailFolders/inbox/messageRules
 * Requires the application permission MailboxSettings.Read.
 */
class GraphMessageRuleSource(
    private val tokens: TokenProvider,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : RuleSource {

    override val name = "graph_messagerules"

    override fun fetch(upn: String): List<MailRule> {
        val token = tokens.tokenForResource(Registration.GRAPH)
        val url = "https://graph.microsoft.com/v1.0/users/${Http.urlEncode(upn)}/mailFolders/inbox/messageRules"
        val root = json.parseToJsonElement(Http.get(url, token)).jsonObject
        val values = root["value"]?.jsonArray ?: return emptyList()
        return values.map { el ->
            val o = el.jsonObject
            MailRule(
                origin = RuleOrigin.SERVER,
                externalId = o["id"]?.jsonPrimitive?.contentOrNull,
                name = o["displayName"]?.jsonPrimitive?.contentOrNull,
                sequence = o["sequence"]?.jsonPrimitive?.intOrNull,
                enabled = o["isEnabled"]?.jsonPrimitive?.booleanOrNull,
                isHidden = o["isReadOnly"]?.jsonPrimitive?.booleanOrNull == true,
                provider = name,
                conditions = o["conditions"],
                actions = o["actions"],
                exceptions = o["exceptions"],
            )
        }
    }
}
