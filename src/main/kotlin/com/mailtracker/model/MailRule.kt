package com.mailtracker.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Where a rule was discovered / where it runs.
 *  - SERVER: structured server-side inbox rule (Graph messageRules / Get-InboxRule).
 *  - CLIENT: client-only rule found only in the FAI rule blob (runs in the Outlook client).
 *  - HIDDEN: a rule present in the FAI blob that the structured sources do not surface
 *    (often used to hide malicious rules).
 */
enum class RuleOrigin { SERVER, CLIENT, HIDDEN }

/**
 * Unified representation of a single mail rule, merged across all sources.
 *
 * Conditions / actions / exceptions are kept as opaque [JsonElement]s so we can faithfully
 * round-trip whatever Graph / Exchange / the FAI parser produced without modelling every
 * predicate. [rawBlobBase64] preserves the original MS-OXORULE bytes for client/hidden rules
 * so nothing is lost even when [partialDecode] is true.
 */
@Serializable
data class MailRule(
    val origin: RuleOrigin,
    val externalId: String? = null,
    val name: String? = null,
    val sequence: Int? = null,
    val enabled: Boolean? = null,
    val isHidden: Boolean = false,
    val provider: String? = null,
    val conditions: JsonElement? = null,
    val actions: JsonElement? = null,
    val exceptions: JsonElement? = null,
    val rawBlobBase64: String? = null,
    /** True when the FAI blob could only be partially decoded (raw bytes still retained). */
    val partialDecode: Boolean = false,
) {
    /**
     * Stable key used to match the "same" rule across two scans and to de-duplicate a rule that
     * appears in more than one source. Prefer the provider-assigned id; fall back to origin+name.
     */
    val identity: String
        get() = externalId?.let { "id:$it" } ?: "name:${origin.name}:${name ?: "(unnamed)"}"
}
