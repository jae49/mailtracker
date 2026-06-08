package com.mailtracker.auth

import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * Helper for reading a PKCS#12 keystore: computes the certificate thumbprint (used at
 * registration time) and validates that the keystore/password are usable before we persist a
 * registration that references them.
 */
object CertKeystore {

    data class Info(val thumbprintSha1: String, val subject: String, val notAfter: String)

    /** SHA-1 thumbprint (hex, uppercase) of the first certificate in the PKCS#12 file. */
    fun inspect(keystorePath: String, password: String): Info {
        val ks = load(keystorePath, password)
        val alias = firstKeyAlias(ks)
        val cert = ks.getCertificate(alias) as X509Certificate
        val sha1 = MessageDigest.getInstance("SHA-1").digest(cert.encoded)
        return Info(
            thumbprintSha1 = sha1.joinToString("") { "%02X".format(it) },
            subject = cert.subjectX500Principal.name,
            notAfter = cert.notAfter.toInstant().toString(),
        )
    }

    fun load(keystorePath: String, password: String): KeyStore {
        val ks = KeyStore.getInstance("PKCS12")
        FileInputStream(keystorePath).use { ks.load(it, password.toCharArray()) }
        return ks
    }

    private fun firstKeyAlias(ks: KeyStore): String =
        ks.aliases().toList().firstOrNull { ks.isKeyEntry(it) }
            ?: error("PKCS#12 keystore contains no private-key entry")
}
