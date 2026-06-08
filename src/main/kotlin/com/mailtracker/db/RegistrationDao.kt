package com.mailtracker.db

import com.mailtracker.auth.FaiTransport
import com.mailtracker.auth.Registration
import com.mailtracker.model.CompactJson
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.sql.Connection

class RegistrationDao(private val conn: Connection) {

    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

    fun get(): Registration? {
        conn.prepareStatement("SELECT * FROM registration WHERE id = 1").use { ps ->
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return Registration(
                    tenantId = rs.getString("tenant_id"),
                    clientId = rs.getString("client_id"),
                    certThumbprint = rs.getString("cert_thumbprint"),
                    keystorePath = rs.getString("keystore_path"),
                    authority = rs.getString("authority"),
                    scopes = CompactJson.decodeFromString(mapSerializer, rs.getString("scopes_json")),
                    faiTransport = FaiTransport.valueOf(rs.getString("fai_transport")),
                    appVersion = rs.getString("app_version"),
                    createdAt = rs.getString("created_at"),
                )
            }
        }
    }

    /** Insert or replace the single registration row. */
    fun upsert(reg: Registration) {
        conn.prepareStatement(
            """
            INSERT INTO registration
                (id, tenant_id, client_id, cert_thumbprint, keystore_path, authority,
                 scopes_json, fai_transport, app_version, created_at)
            VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                tenant_id = excluded.tenant_id,
                client_id = excluded.client_id,
                cert_thumbprint = excluded.cert_thumbprint,
                keystore_path = excluded.keystore_path,
                authority = excluded.authority,
                scopes_json = excluded.scopes_json,
                fai_transport = excluded.fai_transport,
                app_version = excluded.app_version,
                created_at = excluded.created_at
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, reg.tenantId)
            ps.setString(2, reg.clientId)
            ps.setString(3, reg.certThumbprint)
            ps.setString(4, reg.keystorePath)
            ps.setString(5, reg.authority)
            ps.setString(6, CompactJson.encodeToString(mapSerializer, reg.scopes))
            ps.setString(7, reg.faiTransport.name)
            ps.setString(8, reg.appVersion)
            ps.setString(9, reg.createdAt)
            ps.executeUpdate()
        }
    }
}
