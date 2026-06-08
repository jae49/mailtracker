package com.mailtracker.source

import com.mailtracker.auth.Registration
import com.mailtracker.auth.TokenProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Future FAI transport: the Microsoft Graph **userConfiguration** API (permission
 * `MailboxConfigItem.Read`), the supported replacement for EWS GetUserConfiguration. It reads a
 * named FAI item's binary data, which [com.mailtracker.parse.ExtendedRuleParser] then decodes.
 *
 * As of mid-2026 this API is beta and the rules config-item name must be supplied explicitly
 * (the API cannot enumerate items). Until that is pinned down for a tenant, fetching throws a
 * clear error so a scan records this source as unavailable rather than silently empty — flip
 * `fai_transport` to EWS in the registration to use the working path.
 */
class GraphUserConfigFaiSource(
    private val tokens: TokenProvider,
    private val configItemName: String? = null,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : FaiBlobSource {

    override val name = "graph_userconfig"

    override fun fetchRuleFai(upn: String): List<RawFaiRule> {
        val itemName = configItemName ?: throw UnsupportedOperationException(
            "Graph userConfiguration FAI transport needs the rules config-item name (beta API cannot " +
                "enumerate items). Use fai_transport=EWS until this is wired for the tenant.",
        )
        val token = tokens.tokenForResource(Registration.GRAPH)
        val url = "https://graph.microsoft.com/beta/users/${Http.urlEncode(upn)}" +
            "/mailFolders/inbox/userConfigurations/${Http.urlEncode(itemName)}"
        val root = json.parseToJsonElement(Http.get(url, token)).jsonObject
        val binary = root["binaryData"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        return listOf(
            RawFaiRule(
                name = itemName,
                provider = name,
                state = null,
                conditionBlobB64 = binary,
                actionBlobB64 = null,
                messageClass = "UserConfiguration",
            ),
        )
    }
}
