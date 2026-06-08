package com.mailtracker.db

import com.mailtracker.model.CompactJson
import com.mailtracker.model.MailRule
import com.mailtracker.model.RuleOrigin
import kotlinx.serialization.json.JsonElement
import java.sql.Connection
import java.util.Base64

class RuleDao(private val conn: Connection) {

    fun insertAll(scanId: Long, rules: List<MailRule>) {
        val sql = """
            INSERT INTO rules
                (scan_id, origin, external_id, name, sequence, enabled, is_hidden, provider,
                 conditions_json, actions_json, exceptions_json, raw_blob, partial_decode)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            for (r in rules) {
                ps.setLong(1, scanId)
                ps.setString(2, r.origin.name)
                ps.setString(3, r.externalId)
                ps.setString(4, r.name)
                if (r.sequence != null) ps.setInt(5, r.sequence) else ps.setNull(5, java.sql.Types.INTEGER)
                if (r.enabled != null) ps.setInt(6, if (r.enabled) 1 else 0) else ps.setNull(6, java.sql.Types.INTEGER)
                ps.setInt(7, if (r.isHidden) 1 else 0)
                ps.setString(8, r.provider)
                ps.setString(9, r.conditions?.let { CompactJson.encodeToString(JsonElement.serializer(), it) })
                ps.setString(10, r.actions?.let { CompactJson.encodeToString(JsonElement.serializer(), it) })
                ps.setString(11, r.exceptions?.let { CompactJson.encodeToString(JsonElement.serializer(), it) })
                ps.setBytes(12, r.rawBlobBase64?.let { Base64.getDecoder().decode(it) })
                ps.setInt(13, if (r.partialDecode) 1 else 0)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    fun listForScan(scanId: Long): List<MailRule> {
        conn.prepareStatement("SELECT * FROM rules WHERE scan_id = ? ORDER BY sequence, id").use { ps ->
            ps.setLong(1, scanId)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<MailRule>()
                while (rs.next()) {
                    val seq = rs.getInt("sequence").takeUnless { rs.wasNull() }
                    val enabledInt = rs.getInt("enabled").takeUnless { rs.wasNull() }
                    val blob: ByteArray? = rs.getBytes("raw_blob")
                    out += MailRule(
                        origin = RuleOrigin.valueOf(rs.getString("origin")),
                        externalId = rs.getString("external_id"),
                        name = rs.getString("name"),
                        sequence = seq,
                        enabled = enabledInt?.let { it != 0 },
                        isHidden = rs.getInt("is_hidden") != 0,
                        provider = rs.getString("provider"),
                        conditions = rs.getString("conditions_json")?.let { CompactJson.decodeFromString(JsonElement.serializer(), it) },
                        actions = rs.getString("actions_json")?.let { CompactJson.decodeFromString(JsonElement.serializer(), it) },
                        exceptions = rs.getString("exceptions_json")?.let { CompactJson.decodeFromString(JsonElement.serializer(), it) },
                        rawBlobBase64 = blob?.let { Base64.getEncoder().encodeToString(it) },
                        partialDecode = rs.getInt("partial_decode") != 0,
                    )
                }
                return out
            }
        }
    }
}
