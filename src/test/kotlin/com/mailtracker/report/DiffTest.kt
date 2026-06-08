package com.mailtracker.report

import com.mailtracker.model.MailRule
import com.mailtracker.model.RuleOrigin
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffTest {

    private fun rule(
        id: String,
        name: String,
        enabled: Boolean = true,
        seq: Int = 1,
        origin: RuleOrigin = RuleOrigin.SERVER,
        action: String = "delete",
    ) = MailRule(
        origin = origin,
        externalId = id,
        name = name,
        sequence = seq,
        enabled = enabled,
        provider = "graph_messagerules",
        actions = buildJsonObject { put(action, true) },
    )

    @Test
    fun `classifies added removed and modified`() {
        val before = listOf(rule("1", "Keep"), rule("2", "ToRemove"), rule("3", "ToChange", enabled = true, seq = 2))
        val after = listOf(
            rule("1", "Keep"),
            rule("3", "ToChange", enabled = false, seq = 5), // enabled flip + sequence move
            rule("4", "Brand New"),
        )

        val diff = Diff.compute(before, after)

        assertEquals(listOf("Brand New"), diff.added.map { it.name })
        assertEquals(listOf("ToRemove"), diff.removed.map { it.name })

        assertEquals(1, diff.modified.size)
        val change = diff.modified.single()
        assertEquals("ToChange", change.name)
        val fields = change.changes.associate { it.field to (it.before to it.after) }
        assertEquals("true" to "false", fields["enabled"])
        assertEquals("2" to "5", fields["sequence"])
        assertTrue(diff.hasChanges)
    }

    @Test
    fun `identical scans report no changes`() {
        val rules = listOf(rule("1", "A"), rule("2", "B"))
        val diff = Diff.compute(rules, rules)
        assertTrue(!diff.hasChanges)
        assertTrue(diff.modified.isEmpty())
    }

    @Test
    fun `detects action edits via canonical json`() {
        val before = listOf(rule("1", "R", action = "delete"))
        val after = listOf(rule("1", "R", action = "forward"))
        val diff = Diff.compute(before, after)
        assertEquals(1, diff.modified.size)
        assertEquals("actions", diff.modified.single().changes.single().field)
    }
}
