package com.mailtracker.source

import com.mailtracker.model.MailRule

/** Produces structured server-side rules for a mailbox. */
interface RuleSource {
    /** Stable identifier recorded in the scan's per-source status (e.g. "graph_messagerules"). */
    val name: String
    fun fetch(upn: String): List<MailRule>
}

/**
 * Raw, undecoded extended-rule data read from the mailbox's hidden FAI rule messages. The bytes
 * carry the client-only / hidden rules that the structured [RuleSource]s never surface;
 * [com.mailtracker.parse.ExtendedRuleParser] turns each one into a [MailRule].
 */
data class RawFaiRule(
    val name: String?,
    val provider: String?,
    /** PidTagRuleMessageState (PtypInteger32) raw value, if present. */
    val state: Long?,
    /** PidTagExtendedRuleMessageCondition (PtypBinary), base64. */
    val conditionBlobB64: String?,
    /** PidTagExtendedRuleMessageActions (PtypBinary), base64. */
    val actionBlobB64: String?,
    val messageClass: String?,
)

/** Fetches the raw FAI rule blobs for a mailbox. Implemented by EWS today, Graph userConfig later. */
interface FaiBlobSource {
    val name: String
    fun fetchRuleFai(upn: String): List<RawFaiRule>
}
