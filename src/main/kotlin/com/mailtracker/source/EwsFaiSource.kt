package com.mailtracker.source

import com.mailtracker.auth.Registration
import com.mailtracker.auth.TokenProvider
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads the hidden FAI extended-rule messages (`IPM.ExtendedRule.Message`) from a mailbox's Inbox
 * via EWS `FindItem` (associated traversal), requesting the MAPI extended properties that hold the
 * rule name/provider/state and the condition/action blobs.
 *
 * NOTE: EWS is being retired (blocked from 2026-10-01). This is the "today" transport; the same
 * [RawFaiRule] output is produced by [GraphUserConfigFaiSource] once that API is GA.
 */
class EwsFaiSource(
    private val tokens: TokenProvider,
    private val endpoint: String = "https://outlook.office365.com/EWS/Exchange.asmx",
) : FaiBlobSource {

    override val name = "ews"

    override fun fetchRuleFai(upn: String): List<RawFaiRule> {
        val token = tokens.tokenForResource(Registration.EWS)
        val soap = buildFindItemRequest(upn)
        val response = Http.post(
            url = endpoint,
            bearer = token,
            body = soap,
            contentType = "text/xml; charset=utf-8",
            accept = "text/xml",
            headers = mapOf("X-AnchorMailbox" to upn),
        )
        return parse(response)
    }

    private fun buildFindItemRequest(upn: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                       xmlns:t="$TYPES_NS"
                       xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
          <soap:Header>
            <t:RequestServerVersion Version="Exchange2013_SP1"/>
            <t:ExchangeImpersonation>
              <t:ConnectingSID>
                <t:PrimarySmtpAddress>${xml(upn)}</t:PrimarySmtpAddress>
              </t:ConnectingSID>
            </t:ExchangeImpersonation>
          </soap:Header>
          <soap:Body>
            <m:FindItem Traversal="Associated">
              <m:ItemShape>
                <t:BaseShape>IdOnly</t:BaseShape>
                <t:AdditionalProperties>
                  <t:FieldURI FieldURI="item:ItemClass"/>
                  <t:ExtendedFieldURI PropertyTag="0x65EC" PropertyType="String"/>
                  <t:ExtendedFieldURI PropertyTag="0x65EB" PropertyType="String"/>
                  <t:ExtendedFieldURI PropertyTag="0x65E9" PropertyType="Integer"/>
                  <t:ExtendedFieldURI PropertyTag="0x0E9A" PropertyType="Binary"/>
                  <t:ExtendedFieldURI PropertyTag="0x0E99" PropertyType="Binary"/>
                </t:AdditionalProperties>
              </m:ItemShape>
              <m:ParentFolderIds>
                <t:DistinguishedFolderId Id="inbox"/>
              </m:ParentFolderIds>
            </m:FindItem>
          </soap:Body>
        </soap:Envelope>
    """.trimIndent()

    private fun parse(xml: String): List<RawFaiRule> {
        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Harden against XXE for untrusted-ish SOAP responses.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(xml.byteInputStream())

        val out = mutableListOf<RawFaiRule>()
        val classNodes = doc.getElementsByTagNameNS(TYPES_NS, "ItemClass")
        for (i in 0 until classNodes.length) {
            val classEl = classNodes.item(i) as Element
            if (classEl.textContent?.trim() != EXTENDED_RULE_CLASS) continue
            val item = classEl.parentNode as? Element ?: continue
            val props = extendedProps(item)
            out += RawFaiRule(
                name = props[TAG_RULE_NAME],
                provider = props[TAG_RULE_PROVIDER],
                state = props[TAG_RULE_STATE]?.toLongOrNull(),
                conditionBlobB64 = props[TAG_EXT_CONDITION],
                actionBlobB64 = props[TAG_EXT_ACTIONS],
                messageClass = EXTENDED_RULE_CLASS,
            )
        }
        return out
    }

    /** Map of normalised property tag -> string value for one item element. */
    private fun extendedProps(item: Element): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        val extProps = item.getElementsByTagNameNS(TYPES_NS, "ExtendedProperty")
        for (i in 0 until extProps.length) {
            val ext = extProps.item(i) as Element
            val fieldUri = ext.getElementsByTagNameNS(TYPES_NS, "ExtendedFieldURI").item(0) as? Element ?: continue
            val tag = normaliseTag(fieldUri.getAttribute("PropertyTag")) ?: continue
            val value = ext.getElementsByTagNameNS(TYPES_NS, "Value").item(0)?.textContent ?: continue
            result[tag] = value
        }
        return result
    }

    private fun normaliseTag(raw: String?): Int? {
        val t = raw?.trim()?.ifEmpty { null } ?: return null
        return if (t.startsWith("0x", ignoreCase = true)) t.substring(2).toIntOrNull(16) else t.toIntOrNull()
    }

    private fun xml(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        private const val TYPES_NS = "http://schemas.microsoft.com/exchange/services/2006/types"
        private const val EXTENDED_RULE_CLASS = "IPM.ExtendedRule.Message"
        private const val TAG_RULE_NAME = 0x65EC
        private const val TAG_RULE_PROVIDER = 0x65EB
        private const val TAG_RULE_STATE = 0x65E9
        private const val TAG_EXT_CONDITION = 0x0E9A
        private const val TAG_EXT_ACTIONS = 0x0E99
    }
}
