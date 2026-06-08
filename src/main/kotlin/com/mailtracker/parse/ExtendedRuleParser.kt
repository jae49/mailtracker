package com.mailtracker.parse

import com.mailtracker.model.MailRule
import com.mailtracker.model.RuleOrigin
import com.mailtracker.source.RawFaiRule
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Best-effort decoder for the MS-OXORULE extended-rule blobs (`PidTagExtendedRuleMessageActions`
 * / `PidTagExtendedRuleMessageCondition`) read from FAI rule messages.
 *
 * Guarantees **capture** (raw bytes are always retained on the resulting [MailRule]); decoding is
 * partial by design:
 *  - the action blob is walked to list action types and, crucially, to detect `OP_DEFER_ACTION`,
 *    which marks a rule as **client-side** (the server defers it to the Outlook client);
 *  - the condition blob is scanned for readable strings (folder names, addresses, subjects);
 *  - rule state flags yield the enabled bit.
 *
 * A rule with no display name is flagged hidden (a common technique for concealing malicious
 * rules). Anything that fails to parse cleanly sets `partialDecode = true` but never drops data.
 */
object ExtendedRuleParser {

    private const val OP_DEFER_ACTION = 0x05
    private const val ST_ENABLED = 0x01L

    private val actionTypeNames = mapOf(
        0x01 to "OP_MOVE", 0x02 to "OP_COPY", 0x03 to "OP_REPLY", 0x04 to "OP_OOF_REPLY",
        0x05 to "OP_DEFER_ACTION", 0x06 to "OP_BOUNCE", 0x07 to "OP_FORWARD", 0x08 to "OP_DELEGATE",
        0x09 to "OP_TAG", 0x0A to "OP_DELETE", 0x0B to "OP_MARK_AS_READ",
    )

    fun parse(raw: RawFaiRule): MailRule {
        val actionBytes = raw.actionBlobB64?.let { decodeOrNull(it) }
        val conditionBytes = raw.conditionBlobB64?.let { decodeOrNull(it) }

        val actionTypes: List<Int> = actionBytes?.let {
            runCatching { readActionTypes(it) }.getOrElse { emptyList() }
        } ?: emptyList()

        val clientSide = actionTypes.contains(OP_DEFER_ACTION)
        val conditionStrings = conditionBytes?.let { extractStrings(it) } ?: emptyList()

        val enabled = raw.state?.let { (it and ST_ENABLED) != 0L }
        val isHidden = raw.name.isNullOrBlank()
        val origin = when {
            clientSide -> RuleOrigin.CLIENT
            isHidden -> RuleOrigin.HIDDEN
            else -> RuleOrigin.CLIENT
        }

        val actionsJson = buildJsonObject {
            put("clientSide", clientSide)
            put("decoded", "partial")
            putJsonArray("actionTypes") {
                actionTypes.forEach { add(actionTypeNames[it] ?: "0x%02X".format(it)) }
            }
        }
        val conditionsJson = buildJsonObject {
            put("decoded", "partial")
            putJsonArray("strings") { conditionStrings.forEach { add(it) } }
        }

        // Retain the richer raw blob (condition preferred) so nothing is lost on partial decode.
        val rawBlobB64 = raw.conditionBlobB64 ?: raw.actionBlobB64

        return MailRule(
            origin = origin,
            externalId = null,
            name = raw.name,
            sequence = null,
            enabled = enabled,
            isHidden = isHidden,
            provider = raw.provider ?: "extended_rule",
            conditions = conditionsJson,
            actions = actionsJson,
            rawBlobBase64 = rawBlobB64,
            partialDecode = true, // raw bytes retained; full action/condition decode is best-effort
        )
    }

    private fun decodeOrNull(b64: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(b64.trim()) }.getOrNull()

    /**
     * Walk an ExtendedRuleMessageActions blob far enough to list the action types.
     * Layout (little-endian): NamedPropertyInformation, RuleVersion(4), NoOfActions(2),
     * then per action: ActionLength(4) [bytes after this field], ActionType(1), ...skip rest.
     */
    private fun readActionTypes(bytes: ByteArray): List<Int> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        skipNamedPropertyInformation(buf)
        buf.int // RuleVersion
        val count = buf.short.toInt() and 0xFFFF
        val types = mutableListOf<Int>()
        repeat(count) {
            val actionLength = buf.int // bytes following this field
            val type = buf.get().toInt() and 0xFF
            types += type
            // Advance to the next action block (we've consumed the 1-byte ActionType already).
            buf.position(buf.position() + (actionLength - 1))
        }
        return types
    }

    private fun skipNamedPropertyInformation(buf: ByteBuffer) {
        val noOfNamedProps = buf.short.toInt() and 0xFFFF
        if (noOfNamedProps == 0) return
        buf.position(buf.position() + noOfNamedProps * 2) // PropId array
        val namedPropertiesSize = buf.int
        buf.position(buf.position() + namedPropertiesSize) // NamedProperties
    }

    /** Pull out printable UTF-16LE and ASCII runs (length >= 3) as decode hints. */
    private fun extractStrings(bytes: ByteArray, minLen: Int = 3): List<String> {
        val found = LinkedHashSet<String>()

        // UTF-16LE runs: printable char followed by 0x00.
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < bytes.size) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt() and 0xFF
            if (hi == 0 && lo in 0x20..0x7E) {
                sb.append(lo.toChar()); i += 2
            } else {
                if (sb.length >= minLen) found += sb.toString()
                sb.setLength(0); i += 1
            }
        }
        if (sb.length >= minLen) found += sb.toString()

        // ASCII runs.
        sb.setLength(0)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) sb.append(c.toChar()) else {
                if (sb.length >= minLen) found += sb.toString()
                sb.setLength(0)
            }
        }
        if (sb.length >= minLen) found += sb.toString()

        return found.toList()
    }
}
