# mailtracker

A CLI for auditing Office 365 / Exchange Online **mailbox rules** per user and tracking how they
change over time. It captures both **server-side** inbox rules and **client-only / hidden** rules,
stores timestamped scans in a local SQLite database (one database per tenant), and reports the
differences between scans.

The motivating use case is security/forensic auditing: malicious inbox rules (auto-forward,
auto-delete, hidden client-side rules) are a common account-compromise technique, and tracking
rule *drift* over time surfaces them.

## How it captures rules

| Layer | Source | Notes |
|-------|--------|-------|
| Server-side (structured) | Microsoft Graph `messageRules` | Primary, GA. Permission `MailboxSettings.Read`. |
| Server-side (cross-check) | Exchange Admin API `Get-InboxRule` | Corroborates/enriches Graph. Needs an Exchange RBAC role. |
| Client-only / hidden | FAI rule blob → MS-OXORULE parse | The bytes live only in the mailbox's hidden FAI rule messages. |

The **FAI transport** that fetches the client-rule blob is pluggable:

- **EWS** (`EwsFaiSource`) — works today. ⚠️ EWS is blocked in Exchange Online from **2026-10-01**.
- **Graph userConfiguration** (`GraphUserConfigFaiSource`, permission `MailboxConfigItem.Read`) —
  the supported EWS replacement (beta as of mid-2026). Select it with `fai_transport=graph`
  during registration once it's available for your tenant.

The MS-OXORULE decoder is transport-agnostic and **always retains the raw blob**; field decoding
is best-effort (rules are flagged `partial-decode`), and the `OP_DEFER_ACTION` marker is used to
classify a rule as client-side.

## Requirements

- JDK 25 and Gradle 9.5+ (built/tested against JDK 25.0.3 + Gradle 9.5.1, Kotlin 2.4.0).
- An app registration in Microsoft Entra with a **certificate** credential (app-only auth).

## Build

```bash
gradle build            # compile + run unit tests
gradle installDist      # produces build/install/mailtracker/bin/mailtracker
```

## Creating the certificate

App-only auth uses a **certificate** credential (not a client secret). The certificate is
self-signed — there is no CA. Entra only needs the certificate's **public** key, while mailtracker
needs the **private** key in a PKCS#12 keystore. You generate one keypair and produce both files
from it.

### With OpenSSL (recommended)

```bash
# 1. Private key + self-signed public certificate, valid 2 years (-nodes = key not encrypted).
openssl req -x509 -newkey rsa:2048 -sha256 -days 730 -nodes \
  -keyout mailtracker.key -out mailtracker.crt \
  -subj "/CN=mailtracker"

# 2. Bundle the private key + certificate into a PKCS#12 keystore. The export password you set
#    here is the keystore password mailtracker reads from $MAILTRACKER_KEYSTORE_PASS (or prompts
#    for) at scan time.
openssl pkcs12 -export \
  -inkey mailtracker.key -in mailtracker.crt \
  -name mailtracker -out mailtracker.pfx
```

You now have two files:

- **`mailtracker.crt`** — the **public** certificate you upload in step 2 below (Entra accepts
  `.cer`/`.crt`/`.pem`, Base64-encoded).
- **`mailtracker.pfx`** — the PKCS#12 keystore (private key + certificate) you point
  `mailtracker register` at.

### With keytool (JDK alternative)

```bash
# Generate the keypair + self-signed cert straight into a PKCS#12 keystore.
keytool -genkeypair -alias mailtracker -keyalg RSA -keysize 2048 \
  -validity 730 -storetype PKCS12 -keystore mailtracker.pfx \
  -dname "CN=mailtracker"

# Export the public certificate to upload to Entra.
keytool -exportcert -rfc -alias mailtracker \
  -keystore mailtracker.pfx -file mailtracker.cer
```

### Thumbprint and key hygiene

`mailtracker register` reads the keystore and prints the certificate's SHA-1 thumbprint (it stores
the thumbprint and keystore path — never the key). It should match the thumbprint Entra shows under
**Certificates & secrets** (compare case-insensitively, ignoring the colons OpenSSL prints):

```bash
openssl x509 -in mailtracker.crt -noout -fingerprint -sha1
```

Keep the private key readable only by you (e.g. `chmod 600 mailtracker.pfx mailtracker.key`) and
never commit it — the repo's `.gitignore` already excludes `*.pfx`/`*.p12`/`*.jks`/`*.pem`/`*.key`.

## One-time tenant setup (in the Microsoft admin portals)

1. **Entra admin center → App registrations → New registration.** Note the *Directory (tenant) ID*
   and *Application (client) ID*.
2. **Certificates & secrets →** upload your certificate's **public** key (`.cer` — see
   [Creating the certificate](#creating-the-certificate) above). Keep the matching PKCS#12
   (`.pfx`/`.p12`) private key on the machine running mailtracker.
3. **API permissions →** add **application** permissions and **Grant admin consent**:
   - Microsoft Graph: `MailboxSettings.Read` (server-side rules)
   - Microsoft Graph: `MailboxConfigItem.Read` (future FAI transport)
   - Office 365 Exchange Online: `Exchange.ManageAsApp` (Exchange Admin API, for the
     `Get-InboxRule` cross-check). On the preview Admin API this may appear as
     `Exchange.ManageAsAppV2` — grant whichever your portal lists.
   - Office 365 Exchange Online: `full_access_as_app` (EWS FAI transport, today)
4. Assign an **Exchange RBAC role** to the app's service principal granting `Get-InboxRule`. This is
   a *separate* door from the `Exchange.ManageAsApp` permission in step 3: that permission lets the
   app authenticate to the Exchange management endpoint at all, while the RBAC role controls which
   cmdlets it may run once in. Both are required for the Exchange Admin source — without the role a
   valid token still returns `403 Forbidden`.
5. *(Recommended)* scope mailbox access via RBAC for Applications, and add the app to the EWS
   **AppID allow list** while EWS is in use.

## Usage

```bash
mailtracker [--db <path>] <command>     # default db: ./mailtracker.db
```

| Command | Purpose |
|---------|---------|
| `register` | Guided wizard: capture tenant/client/cert, validate live, create the DB. |
| `scan <user>` | Fetch all rules for a mailbox and store a timestamped snapshot. |
| `compare <user> [--json]` | Show rules changed between the two most recent scans. |
| `list <user> [--json]` | Verbose listing of every rule in the latest scan. |
| `delete <user> [--yes]` | Delete a user and all of its scans (cascades to rules). |

The keystore password is read from `$MAILTRACKER_KEYSTORE_PASS` if set, otherwise prompted.

### Example end-to-end

```bash
mailtracker --db acme.db register
mailtracker --db acme.db scan jdoe@acme.com
# ... time passes / a rule changes ...
mailtracker --db acme.db scan jdoe@acme.com
mailtracker --db acme.db compare jdoe@acme.com
mailtracker --db acme.db list jdoe@acme.com --json
mailtracker --db acme.db delete jdoe@acme.com --yes
```

## Data model (SQLite)

`registration` (single row) · `users` · `scans` (timestamped, with per-source status) · `rules`
(origin server/client/hidden, conditions/actions/exceptions as JSON, raw FAI blob). Deleting a user
cascades to its scans and rules.

## Security notes

The certificate **private key is never stored in the database** — only a reference (keystore path
+ SHA-1 thumbprint). Protect the keystore file with OS permissions; the database stores no secrets.

## Caveats

- EWS retires 2026-10-01 (blocked) / ~2027 (removed); switch `fai_transport` to `graph` when the
  Graph userConfiguration API is GA for your tenant.
- The Graph userConfiguration and Exchange Admin APIs are preview as of mid-2026.
- Client-only rules are a shrinking surface (the *new* Outlook drops support), but existing ones
  persist in classic-Outlook mailboxes and remain a valid audit target.
- MS-OXORULE decoding is best-effort: capture is guaranteed (raw blob retained), full action
  decoding is not.
