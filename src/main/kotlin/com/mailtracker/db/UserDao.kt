package com.mailtracker.db

import java.sql.Connection
import java.sql.Statement

data class UserRow(val id: Long, val upn: String, val displayName: String?, val createdAt: String)

class UserDao(private val conn: Connection) {

    fun findByUpn(upn: String): UserRow? {
        conn.prepareStatement("SELECT * FROM users WHERE upn = ?").use { ps ->
            ps.setString(1, upn)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return UserRow(rs.getLong("id"), rs.getString("upn"), rs.getString("display_name"), rs.getString("created_at"))
            }
        }
    }

    /** Insert the user if absent (case-insensitive UPNs are normalised to lowercase). */
    fun getOrCreate(upn: String, displayName: String?, nowIso: String): UserRow {
        val normalized = upn.trim().lowercase()
        findByUpn(normalized)?.let { return it }
        conn.prepareStatement(
            "INSERT INTO users (upn, display_name, created_at) VALUES (?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS,
        ).use { ps ->
            ps.setString(1, normalized)
            ps.setString(2, displayName)
            ps.setString(3, nowIso)
            ps.executeUpdate()
            ps.generatedKeys.use { keys ->
                keys.next()
                return UserRow(keys.getLong(1), normalized, displayName, nowIso)
            }
        }
    }

    /** Delete the user; scans and rules cascade. Returns true if a row was removed. */
    fun delete(upn: String): Boolean {
        conn.prepareStatement("DELETE FROM users WHERE upn = ?").use { ps ->
            ps.setString(1, upn.trim().lowercase())
            return ps.executeUpdate() > 0
        }
    }
}
