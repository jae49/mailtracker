package com.mailtracker.db

import com.mailtracker.model.MailRule
import com.mailtracker.model.RuleOrigin
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatabaseTest {

    private fun rule(name: String, origin: RuleOrigin = RuleOrigin.SERVER) = MailRule(
        origin = origin,
        externalId = "ext-$name",
        name = name,
        sequence = 1,
        enabled = true,
        provider = "test",
        conditions = buildJsonObject { put("subjectContains", "invoice") },
        actions = buildJsonObject { put("delete", true) },
    )

    @Test
    fun `cascade delete removes scans and rules`(@TempDir dir: Path) {
        val dbPath = dir.resolve("t.db").toString()
        Database.open(dbPath).use { db ->
            val user = db.users.getOrCreate("Alice@Example.com", "Alice", "2026-06-08T00:00:00Z")
            assertEquals("alice@example.com", user.upn) // normalised lowercase

            val scanId = db.scans.insert(user.id, "2026-06-08T01:00:00Z", mapOf("graph" to true, "ews" to false), null)
            db.rules.insertAll(scanId, listOf(rule("R1"), rule("R2", RuleOrigin.CLIENT)))

            assertEquals(2, db.rules.listForScan(scanId).size)
            assertEquals(1, db.scans.headersForUser(user.id, user.upn).size)
            assertEquals(2, db.scans.headersForUser(user.id, user.upn).first().ruleCount)

            // Deleting the user must cascade to scans and rules.
            assertTrue(db.users.delete(user.upn))

            assertNull(db.users.findByUpn(user.upn))
            assertTrue(db.scans.headersForUser(user.id, user.upn).isEmpty())
            assertTrue(db.rules.listForScan(scanId).isEmpty())
        }
    }

    @Test
    fun `round-trips rule fields and source status`(@TempDir dir: Path) {
        val dbPath = dir.resolve("t2.db").toString()
        Database.open(dbPath).use { db ->
            val user = db.users.getOrCreate("bob@example.com", null, "2026-06-08T00:00:00Z")
            val scanId = db.scans.insert(user.id, "2026-06-08T02:00:00Z", mapOf("graph" to true), "note")
            db.rules.insertAll(scanId, listOf(rule("Keep").copy(isHidden = true, partialDecode = true)))

            val back = db.rules.listForScan(scanId).single()
            assertEquals("Keep", back.name)
            assertEquals(RuleOrigin.SERVER, back.origin)
            assertTrue(back.isHidden)
            assertTrue(back.partialDecode)
            assertNotNull(back.conditions)

            val header = db.scans.headersForUser(user.id, user.upn).single()
            assertEquals(mapOf("graph" to true), header.sourceStatus)
            assertEquals("note", header.notes)
        }
    }
}
