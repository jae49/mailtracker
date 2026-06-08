package com.mailtracker.db

import com.mailtracker.model.CompactJson
import com.mailtracker.model.ScanHeader
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.sql.Connection
import java.sql.Statement

class ScanDao(private val conn: Connection) {

    private val statusSerializer = MapSerializer(String.serializer(), Boolean.serializer())

    fun insert(userId: Long, scannedAt: String, sourceStatus: Map<String, Boolean>, notes: String?): Long {
        conn.prepareStatement(
            "INSERT INTO scans (user_id, scanned_at, source_status_json, notes) VALUES (?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS,
        ).use { ps ->
            ps.setLong(1, userId)
            ps.setString(2, scannedAt)
            ps.setString(3, CompactJson.encodeToString(statusSerializer, sourceStatus))
            ps.setString(4, notes)
            ps.executeUpdate()
            ps.generatedKeys.use { keys ->
                keys.next()
                return keys.getLong(1)
            }
        }
    }

    /** Most recent scans first. [limit] caps the result (e.g. 2 for compare). */
    fun headersForUser(userId: Long, upn: String, limit: Int? = null): List<ScanHeader> {
        val sql = buildString {
            append(
                """
                SELECT s.id, s.scanned_at, s.source_status_json, s.notes,
                       (SELECT COUNT(*) FROM rules r WHERE r.scan_id = s.id) AS rule_count
                FROM scans s WHERE s.user_id = ?
                ORDER BY s.scanned_at DESC, s.id DESC
                """.trimIndent(),
            )
            if (limit != null) append(" LIMIT $limit")
        }
        conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, userId)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<ScanHeader>()
                while (rs.next()) {
                    val statusJson = rs.getString("source_status_json")
                    out += ScanHeader(
                        id = rs.getLong("id"),
                        userUpn = upn,
                        scannedAt = rs.getString("scanned_at"),
                        sourceStatus = statusJson?.let { CompactJson.decodeFromString(statusSerializer, it) } ?: emptyMap(),
                        notes = rs.getString("notes"),
                        ruleCount = rs.getInt("rule_count"),
                    )
                }
                return out
            }
        }
    }
}
