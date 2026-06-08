package com.mailtracker.report

import com.mailtracker.model.CompactJson
import com.mailtracker.model.MailRule
import com.mailtracker.model.MailtrackerJson
import com.mailtracker.model.Scan
import com.mailtracker.model.ScanHeader
import kotlinx.serialization.json.JsonElement

/** Human-readable and JSON renderers for `list` and `compare` output. */
object Render {

    // ---- list ----

    fun scanJson(scan: Scan): String = MailtrackerJson.encodeToString(Scan.serializer(), scan)

    fun scanHuman(scan: Scan): String = buildString {
        appendHeader(scan.header)
        appendLine()
        if (scan.rules.isEmpty()) {
            appendLine("(no rules captured)")
            return@buildString
        }
        scan.rules.forEachIndexed { i, r ->
            appendLine("─".repeat(60))
            appendRule(i + 1, r)
        }
    }

    private fun StringBuilder.appendHeader(h: ScanHeader) {
        appendLine("Mailbox : ${h.userUpn}")
        appendLine("Scan    : #${h.id} @ ${h.scannedAt}  (${h.ruleCount} rules)")
        val status = h.sourceStatus.entries.joinToString(", ") { "${it.key}=${if (it.value) "ok" else "FAILED"}" }
        if (status.isNotEmpty()) appendLine("Sources : $status")
        h.notes?.let { appendLine("Notes   : $it") }
    }

    private fun StringBuilder.appendRule(n: Int, r: MailRule) {
        val flags = buildList {
            add(r.origin.name.lowercase())
            if (r.isHidden) add("HIDDEN")
            if (r.enabled == false) add("disabled")
            if (r.partialDecode) add("partial-decode")
        }.joinToString(", ")
        appendLine("$n. ${r.name ?: "(unnamed)"}  [$flags]")
        r.sequence?.let { appendLine("   sequence : $it") }
        r.externalId?.let { appendLine("   id       : $it") }
        r.provider?.let { appendLine("   provider : $it") }
        appendField("   conditions", r.conditions)
        appendField("   actions", r.actions)
        appendField("   exceptions", r.exceptions)
        if (r.rawBlobBase64 != null) appendLine("   rawBlob  : ${r.rawBlobBase64.length} b64 chars retained")
    }

    private fun StringBuilder.appendField(label: String, e: JsonElement?) {
        if (e == null) return
        appendLine("$label : ${CompactJson.encodeToString(JsonElement.serializer(), e)}")
    }

    // ---- compare ----

    fun diffJson(upn: String, before: ScanHeader, after: ScanHeader, diff: RuleDiff): String {
        val payload = MailtrackerJson.encodeToString(
            DiffReport.serializer(),
            DiffReport(upn, before, after, diff),
        )
        return payload
    }

    fun diffHuman(upn: String, before: ScanHeader, after: ScanHeader, diff: RuleDiff): String = buildString {
        appendLine("Mailbox  : $upn")
        appendLine("Comparing: #${before.id} (${before.scannedAt})  ->  #${after.id} (${after.scannedAt})")
        appendLine()
        if (!diff.hasChanges) {
            appendLine("No changes between the two most recent scans.")
            return@buildString
        }
        if (diff.added.isNotEmpty()) {
            appendLine("ADDED (${diff.added.size}):")
            diff.added.forEach { appendLine("  + ${it.name ?: "(unnamed)"}  [${it.origin.name.lowercase()}${if (it.isHidden) ", HIDDEN" else ""}]") }
            appendLine()
        }
        if (diff.removed.isNotEmpty()) {
            appendLine("REMOVED (${diff.removed.size}):")
            diff.removed.forEach { appendLine("  - ${it.name ?: "(unnamed)"}  [${it.origin.name.lowercase()}]") }
            appendLine()
        }
        if (diff.modified.isNotEmpty()) {
            appendLine("MODIFIED (${diff.modified.size}):")
            diff.modified.forEach { change ->
                appendLine("  ~ ${change.name ?: "(unnamed)"}")
                change.changes.forEach { fc ->
                    appendLine("      ${fc.field}: ${fc.before ?: "∅"} -> ${fc.after ?: "∅"}")
                }
            }
        }
    }
}
