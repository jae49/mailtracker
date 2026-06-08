package com.mailtracker.report

import com.mailtracker.model.CompactJson
import com.mailtracker.model.MailRule
import com.mailtracker.model.ScanHeader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Top-level `compare --json` document. */
@Serializable
data class DiffReport(
    val mailbox: String,
    val before: ScanHeader,
    val after: ScanHeader,
    val diff: RuleDiff,
)

@Serializable
data class FieldChange(val field: String, val before: String?, val after: String?)

@Serializable
data class RuleChange(
    val identity: String,
    val name: String?,
    val changes: List<FieldChange>,
)

@Serializable
data class RuleDiff(
    val added: List<MailRule>,
    val removed: List<MailRule>,
    val modified: List<RuleChange>,
) {
    val hasChanges: Boolean get() = added.isNotEmpty() || removed.isNotEmpty() || modified.isNotEmpty()
}

/**
 * Computes the difference between two scans' rule sets. Rules are matched across scans by their
 * [MailRule.identity] (provider id, falling back to origin+name); matched rules are compared
 * field by field to surface enabled flips, sequence moves, and condition/action edits.
 */
object Diff {

    fun compute(before: List<MailRule>, after: List<MailRule>): RuleDiff {
        val beforeByKey = before.associateBy { it.identity }
        val afterByKey = after.associateBy { it.identity }

        val added = after.filter { it.identity !in beforeByKey }
        val removed = before.filter { it.identity !in afterByKey }

        val modified = mutableListOf<RuleChange>()
        for ((key, b) in beforeByKey) {
            val a = afterByKey[key] ?: continue
            val changes = fieldChanges(b, a)
            if (changes.isNotEmpty()) {
                modified += RuleChange(identity = key, name = a.name ?: b.name, changes = changes)
            }
        }
        return RuleDiff(added = added, removed = removed, modified = modified)
    }

    private fun fieldChanges(b: MailRule, a: MailRule): List<FieldChange> {
        val out = mutableListOf<FieldChange>()
        fun cmp(field: String, before: String?, after: String?) {
            if (before != after) out += FieldChange(field, before, after)
        }
        cmp("name", b.name, a.name)
        cmp("enabled", b.enabled?.toString(), a.enabled?.toString())
        cmp("sequence", b.sequence?.toString(), a.sequence?.toString())
        cmp("isHidden", b.isHidden.toString(), a.isHidden.toString())
        cmp("origin", b.origin.name, a.origin.name)
        cmp("provider", b.provider, a.provider)
        cmp("conditions", canon(b.conditions), canon(a.conditions))
        cmp("actions", canon(b.actions), canon(a.actions))
        cmp("exceptions", canon(b.exceptions), canon(a.exceptions))
        return out
    }

    private fun canon(e: JsonElement?): String? =
        e?.let { CompactJson.encodeToString(JsonElement.serializer(), it) }
}
