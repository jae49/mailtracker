package com.mailtracker.parse

import com.mailtracker.model.RuleOrigin
import com.mailtracker.source.RawFaiRule
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtendedRuleParserTest {

    /** Build an ExtendedRuleMessageActions blob matching the parser's assumed layout. */
    private fun actionsBlob(vararg actionTypes: Int): String {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
        fun u32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())

        u16(0)            // NamedPropertyInformation: NoOfNamedProps = 0
        u32(1)            // RuleVersion
        u16(actionTypes.size) // NoOfActions
        for (t in actionTypes) {
            // ActionLength counts bytes after the length field: ActionType(1)+Flavor(4)+Flags(4) = 9
            u32(9)
            out.write(t)          // ActionType
            u32(0)                // ActionFlavor
            u32(0)                // ActionFlags
        }
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private fun conditionBlobWith(text: String): String {
        // Minimal blob carrying a UTF-16LE string the parser should surface.
        val bytes = text.toByteArray(Charsets.UTF_16LE)
        return Base64.getEncoder().encodeToString(bytes)
    }

    @Test
    fun `defer action marks a rule client-side`() {
        val raw = RawFaiRule(
            name = "Move newsletters",
            provider = "RuleOrganizer",
            state = 0x01, // ST_ENABLED
            conditionBlobB64 = conditionBlobWith("Newsletters"),
            actionBlobB64 = actionsBlob(0x01, 0x05), // OP_MOVE + OP_DEFER_ACTION
            messageClass = "IPM.ExtendedRule.Message",
        )
        val rule = ExtendedRuleParser.parse(raw)

        assertEquals(RuleOrigin.CLIENT, rule.origin)
        assertEquals("Move newsletters", rule.name)
        assertEquals(true, rule.enabled)
        assertTrue(rule.partialDecode)
        assertEquals("RuleOrganizer", rule.provider)

        val actions = rule.actions!!.jsonObject
        val actionTypes = actions["actionTypes"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("OP_MOVE", "OP_DEFER_ACTION"), actionTypes)
        assertEquals(true, actions["clientSide"]!!.jsonPrimitive.content.toBooleanStrict())

        val strings = rule.conditions!!.jsonObject["strings"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(strings.contains("Newsletters"))

        // Raw bytes are always retained.
        assertEquals(raw.conditionBlobB64, rule.rawBlobBase64)
    }

    @Test
    fun `unnamed server-only extended rule is flagged hidden`() {
        val raw = RawFaiRule(
            name = null,
            provider = null,
            state = 0x00, // disabled
            conditionBlobB64 = null,
            actionBlobB64 = actionsBlob(0x0A), // OP_DELETE, no defer
            messageClass = "IPM.ExtendedRule.Message",
        )
        val rule = ExtendedRuleParser.parse(raw)

        assertEquals(RuleOrigin.HIDDEN, rule.origin)
        assertTrue(rule.isHidden)
        assertEquals(false, rule.enabled)
        assertFalse(rule.actions!!.jsonObject["clientSide"]!!.jsonPrimitive.content.toBoolean())
    }
}
