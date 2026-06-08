package com.mailtracker.auth

import com.microsoft.aad.msal4j.ClientCredentialFactory
import com.microsoft.aad.msal4j.ClientCredentialParameters
import com.microsoft.aad.msal4j.ConfidentialClientApplication
import java.io.FileInputStream

/**
 * Acquires app-only (client-credentials) access tokens using a certificate credential, one token
 * per Microsoft resource (Graph, Outlook/EWS, Exchange Admin). MSAL4J keeps an internal token
 * cache, so repeated calls for the same scope reuse a valid token until it expires.
 */
class TokenProvider(
    private val app: ConfidentialClientApplication,
    private val scopes: Map<String, String>,
) {
    /** Acquire a bearer token for the resource key (see [Registration.GRAPH] etc.). */
    fun tokenForResource(resourceKey: String): String {
        val scope = scopes[resourceKey]
            ?: error("No scope configured for resource '$resourceKey'")
        val params = ClientCredentialParameters.builder(setOf(scope)).build()
        return app.acquireToken(params).get().accessToken()
    }

    companion object {
        /**
         * Build a [TokenProvider] from a registration and the PKCS#12 keystore password. The
         * private key is read from [Registration.keystorePath]; it is never persisted by us.
         */
        fun create(reg: Registration, keystorePassword: String): TokenProvider {
            val keystorePath = reg.keystorePath
                ?: error("Registration has no keystore path; re-run `mailtracker register`")
            val credential = FileInputStream(keystorePath).use { stream ->
                ClientCredentialFactory.createFromCertificate(stream, keystorePassword)
            }
            val app = ConfidentialClientApplication
                .builder(reg.clientId, credential)
                .authority(reg.authority)
                .build()
            return TokenProvider(app, reg.scopes)
        }
    }
}
