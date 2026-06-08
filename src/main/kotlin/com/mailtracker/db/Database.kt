package com.mailtracker.db

import java.sql.Connection
import java.sql.DriverManager

/**
 * Owns the single JDBC connection for a CLI invocation. SQLite enforces foreign keys per
 * connection, so we enable the pragma on open and then ensure the schema exists.
 */
class Database private constructor(val connection: Connection) : AutoCloseable {

    val registration = RegistrationDao(connection)
    val users = UserDao(connection)
    val scans = ScanDao(connection)
    val rules = RuleDao(connection)

    override fun close() = connection.close()

    companion object {
        /** Open (creating the file if needed) and migrate the schema. */
        fun open(path: String): Database {
            // Ensure the JDBC driver is registered (explicit for fat-jar / module robustness).
            Class.forName("org.sqlite.JDBC")
            val conn = DriverManager.getConnection("jdbc:sqlite:$path")
            conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
            migrate(conn)
            return Database(conn)
        }

        private fun migrate(conn: Connection) {
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS registration (
                        id             INTEGER PRIMARY KEY CHECK (id = 1),
                        tenant_id      TEXT NOT NULL,
                        client_id      TEXT NOT NULL,
                        cert_thumbprint TEXT,
                        keystore_path  TEXT,
                        authority      TEXT NOT NULL,
                        scopes_json    TEXT NOT NULL,
                        fai_transport  TEXT NOT NULL DEFAULT 'EWS',
                        app_version    TEXT,
                        created_at     TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS users (
                        id           INTEGER PRIMARY KEY AUTOINCREMENT,
                        upn          TEXT NOT NULL UNIQUE,
                        display_name TEXT,
                        created_at   TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS scans (
                        id                 INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_id            INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        scanned_at         TEXT NOT NULL,
                        source_status_json TEXT,
                        notes              TEXT
                    )
                    """.trimIndent(),
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS rules (
                        id              INTEGER PRIMARY KEY AUTOINCREMENT,
                        scan_id         INTEGER NOT NULL REFERENCES scans(id) ON DELETE CASCADE,
                        origin          TEXT NOT NULL,
                        external_id     TEXT,
                        name            TEXT,
                        sequence        INTEGER,
                        enabled         INTEGER,
                        is_hidden       INTEGER NOT NULL DEFAULT 0,
                        provider        TEXT,
                        conditions_json TEXT,
                        actions_json    TEXT,
                        exceptions_json TEXT,
                        raw_blob        BLOB,
                        partial_decode  INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_scans_user_time ON scans(user_id, scanned_at)")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rules_scan ON rules(scan_id)")
            }
        }
    }
}
