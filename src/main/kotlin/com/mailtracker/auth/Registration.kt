package com.mailtracker.auth

import kotlinx.serialization.Serializable

/** Which transport currently fetches the client-rule FAI blob. */
enum class FaiTransport { EWS, GRAPH_USERCONFIG }

/**
 * Single-tenant registration record persisted in the SQLite `registration` table.
 *
 * The private key is **never** stored here — only a reference to the on-disk keystore plus the
 * certificate thumbprint. The keystore password is supplied at runtime (prompt or env var).
 */
@Serializable
data class Registration(
    val tenantId: String,
    val clientId: String,
    val certThumbprint: String?,
    val keystorePath: String?,
    val authority: String,
    /** Resource → `.default` scope, e.g. "graph" -> "https://graph.microsoft.com/.default". */
    val scopes: Map<String, String>,
    val faiTransport: FaiTransport = FaiTransport.EWS,
    val appVersion: String? = null,
    val createdAt: String,
) {
    companion object {
        const val GRAPH = "graph"
        const val EWS = "ews"
        const val EXCHANGE_ADMIN = "exchange_admin"

        fun authorityFor(tenantId: String) = "https://login.microsoftonline.com/$tenantId"

        val DEFAULT_SCOPES = mapOf(
            GRAPH to "https://graph.microsoft.com/.default",
            EWS to "https://outlook.office365.com/.default",
            EXCHANGE_ADMIN to "https://outlook.office365.com/.default",
        )
    }
}
