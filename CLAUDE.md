# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`mailtracker` is a Kotlin/JVM CLI that audits Office 365 / Exchange Online **mailbox rules** per user and tracks how they drift over time (a malicious-inbox-rule detection use case). It captures server-side rules *and* client-only/hidden rules, stores timestamped scans in a local SQLite DB (one DB per tenant), and diffs scans. See `README.md` for the tenant/Entra setup and permission details.

## Build, test, run

There is **no Gradle wrapper** — use the system `gradle` (9.5+). The build targets **JDK 25**; the toolchain is pinned to `/home/crow/opt/jdk25` in `gradle.properties` (foojay auto-download is disabled), so a JDK 25 must exist at that path or `org.gradle.java.installations.paths` must be repointed.

```bash
gradle build            # compile + run all unit tests
gradle test             # tests only (JUnit 5 / kotlin.test)
gradle installDist      # build/install/mailtracker/bin/mailtracker (preferred way to run)
gradle run --args="--db acme.db scan jdoe@acme.com"   # run via Gradle

# single test class / method:
gradle test --tests "com.mailtracker.parse.ExtendedRuleParserTest"
gradle test --tests "com.mailtracker.report.DiffTest.someMethod"
```

Note: `application` sets `--enable-native-access=ALL-UNNAMED` to silence JDK 25 FFM "restricted method" warnings from sqlite-jdbc / JNA — keep this when adding JVM args.

## Architecture

The flow is **CLI → Runtime (composition root) → MailboxScanner → many sources → unified MailRule → SQLite → Diff/Render**. The pieces that require reading several files together:

**Composition root — `cli/Runtime.kt`.** `Runtime.buildScanner(registration)` is where all wiring happens: it creates a `TokenProvider` and assembles the `RuleSource` list (`GraphMessageRuleSource`, `ExchangeAdminRuleSource`) plus the single `FaiBlobSource`, *selecting the FAI transport from `registration.faiTransport`*. `Main.kt` wires clikt subcommands and shares `AppConfig` (holds `--db` path) through the clikt context object; each subcommand opens the DB, calls `Runtime.requireRegistration`, then `buildScanner`.

**Multi-source merge — `source/MailboxScanner.kt`.** Runs every `RuleSource` and the one `FaiBlobSource`, recording each source's success/failure in `sourceStatus` so a partial outage (e.g. EWS blocked) is *visible in the stored scan* rather than silently dropping rules. Then `dedupe()` merges: server-side rules are bucketed separately from FAI rules, same-named server rules collapse (preferring the richer Graph representation via `sourceScore`), and unnamed FAI rules never merge.

**The pluggable FAI transport is the central design constraint.** EWS is blocked in Exchange Online from **2026-10-01**, so the client-rule blob fetch is abstracted behind `source/RuleSource.kt`'s `FaiBlobSource` interface, producing transport-neutral `RawFaiRule` records. Two implementations: `EwsFaiSource` (today) and `GraphUserConfigFaiSource` (the supported replacement). When touching FAI fetch logic, keep both implementations producing identical `RawFaiRule` output — the parser downstream is transport-agnostic.

**MS-OXORULE decode — `parse/ExtendedRuleParser.kt`.** Turns a `RawFaiRule` into a `MailRule`. Decoding is **best-effort by design and always retains the raw blob** — every FAI-parsed rule currently sets `partialDecode = true`. Two important behaviors: the `OP_DEFER_ACTION` (0x05) marker in the action blob classifies a rule as client-side (`RuleOrigin.CLIENT`); a rule with no display name is flagged `isHidden`. Do not "fix" partial decoding by dropping the raw bytes — capture is the guarantee, full decode is not.

**Unified model — `model/MailRule.kt`.** One `MailRule` type across all sources. Conditions/actions/exceptions are opaque `JsonElement`s, deliberately *not* modelled field-by-field, so whatever Graph / Exchange / the parser produced round-trips faithfully. `MailRule.identity` (provider `externalId`, falling back to `origin+name`) is used in two places: cross-scan matching in `Diff` and de-duplication in `MailboxScanner`.

**Persistence — `db/`.** One SQLite file per tenant. `Database.open()` enables `PRAGMA foreign_keys = ON` (SQLite enforces FKs per-connection) and runs the idempotent `CREATE TABLE IF NOT EXISTS` migration, then exposes a DAO per table. Schema: single-row `registration` · `users` · `scans` (timestamped, `source_status_json`) · `rules`. Deletes cascade user → scans → rules. JSON columns are serialized via `model.CompactJson`; the raw FAI blob is stored as a `BLOB` (base64-decoded on write in `RuleDao`, re-encoded on read).

**Diff/report — `report/`.** `Diff.compute(before, after)` matches rules by `identity` across the two most recent scans and emits added/removed/modified with per-field changes (JSON columns compared via canonicalized `CompactJson`). `compare` and `list` both support `--json` (kotlinx.serialization data classes in `report/Diff.kt`).

**Auth — `auth/`.** App-only client-credentials with a **certificate** credential via MSAL4J (`TokenProvider`), one token per Microsoft resource (graph / ews / exchange_admin `.default` scopes in `Registration`). The certificate **private key is never persisted** — `registration` stores only the keystore path + SHA-1 thumbprint; the keystore password comes from `$MAILTRACKER_KEYSTORE_PASS` or an interactive prompt at scan time.

**HTTP — `source/Http.kt`.** Thin wrapper over the JDK `HttpClient`; non-2xx throws `HttpException` (carries status + body), which `MailboxScanner` records per-source. `EwsFaiSource` parses SOAP XML with DOCTYPE declarations disabled (XXE hardening) — preserve that when editing XML parsing.

## Conventions

- Adding a server-side rule source: implement `RuleSource`, give it a stable `name` (recorded in `source_status_json`), map into `MailRule` retaining unmodelled data under `conditions`, and add it to the list in `Runtime.buildScanner`.
- Adding a FAI transport: implement `FaiBlobSource`, add a `FaiTransport` enum value, and branch in `Runtime.buildScanner` — do not change `ExtendedRuleParser`, which consumes `RawFaiRule` only.
- Schema changes go in `Database.migrate` as additive `IF NOT EXISTS` / `ALTER`-style steps (there is no migration framework or versioning).
