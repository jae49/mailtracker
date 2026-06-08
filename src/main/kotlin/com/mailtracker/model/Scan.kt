package com.mailtracker.model

import kotlinx.serialization.Serializable

/** Lightweight scan header (no rules) — used for listings and to locate latest/previous scans. */
@Serializable
data class ScanHeader(
    val id: Long,
    val userUpn: String,
    val scannedAt: String,
    val sourceStatus: Map<String, Boolean> = emptyMap(),
    val notes: String? = null,
    val ruleCount: Int = 0,
)

/** A full scan: header plus every captured rule. */
@Serializable
data class Scan(
    val header: ScanHeader,
    val rules: List<MailRule>,
)
