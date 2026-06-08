package com.mailtracker.source

import com.mailtracker.model.MailRule
import com.mailtracker.model.RuleOrigin
import com.mailtracker.parse.ExtendedRuleParser

/**
 * Runs every configured source for a mailbox, parses the FAI blobs into rules, and merges the
 * results into one de-duplicated rule set. Each source's success/failure is reported so a partial
 * outage (e.g. EWS blocked) is visible in the scan rather than silently dropping rules.
 */
class MailboxScanner(
    private val ruleSources: List<RuleSource>,
    private val faiSource: FaiBlobSource,
) {
    data class Result(
        val rules: List<MailRule>,
        val sourceStatus: Map<String, Boolean>,
        val errors: Map<String, String>,
    )

    fun scan(upn: String): Result {
        val status = LinkedHashMap<String, Boolean>()
        val errors = LinkedHashMap<String, String>()
        val collected = mutableListOf<MailRule>()

        for (src in ruleSources) {
            try {
                collected += src.fetch(upn)
                status[src.name] = true
            } catch (e: Exception) {
                status[src.name] = false
                errors[src.name] = describe(e)
            }
        }

        try {
            collected += faiSource.fetchRuleFai(upn).map { ExtendedRuleParser.parse(it) }
            status[faiSource.name] = true
        } catch (e: Exception) {
            status[faiSource.name] = false
            errors[faiSource.name] = describe(e)
        }

        return Result(dedupe(collected), status, errors)
    }

    /**
     * Merge rules across sources. Server-side rules with the same name (from Graph and the Exchange
     * Admin API) collapse to one, preferring the richer Graph representation. FAI-derived
     * client/hidden rules are kept distinct (they are bucketed separately and unnamed ones never
     * merge).
     */
    private fun dedupe(rules: List<MailRule>): List<MailRule> {
        val byKey = LinkedHashMap<String, MailRule>()
        var uid = 0
        for (r in rules) {
            val bucket = if (r.origin == RuleOrigin.SERVER) "S" else "F"
            val key = if (!r.name.isNullOrBlank()) {
                "name:$bucket:${r.name.trim().lowercase()}"
            } else {
                "uid:${uid++}"
            }
            val existing = byKey[key]
            byKey[key] = if (existing == null) r else preferred(existing, r)
        }
        return byKey.values.sortedWith(
            compareBy({ it.sequence ?: Int.MAX_VALUE }, { it.name?.lowercase() ?: "~" }),
        )
    }

    private fun preferred(a: MailRule, b: MailRule): MailRule =
        if (sourceScore(b) > sourceScore(a)) b else a

    private fun sourceScore(r: MailRule): Int = when {
        r.provider == "graph_messagerules" -> 3
        r.origin == RuleOrigin.SERVER -> 2
        else -> 1
    }

    private fun describe(e: Exception): String = when (e) {
        is HttpException -> "HTTP ${e.status}: ${e.message}"
        else -> e.message ?: e.javaClass.simpleName
    }
}
