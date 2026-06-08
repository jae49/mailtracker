package com.mailtracker.model

import kotlinx.serialization.json.Json

/** Shared JSON configuration used for DB columns and the `--json` CLI output. */
val MailtrackerJson: Json = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** Compact variant for canonical-ish storage / equality of rule sub-documents. */
val CompactJson: Json = Json {
    prettyPrint = false
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = false
}
