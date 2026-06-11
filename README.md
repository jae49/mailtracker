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
| Server-side (cross-check) | Exchange Admin API `Get-InboxRule` | Corroborates/enriches Graph. Needs `Exchange.ManageAsApp` + an Entra directory role. |
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

This is a one-time setup performed by a tenant administrator — a member of the **Global
Administrator** or **Privileged Role Administrator** role, since the steps grant admin consent and
assign a directory role. Everything below is doable from **Linux and the web portals alone**: no
Windows and no Azure subscription are required, just an Entra / Office 365 tenant.

### Why the permission list is heterogeneous

mailtracker reads rules from three sources, and each touches a *different* Microsoft 365 access
plane — which is why the grants don't all come from one place:

| Source | Plane | Grant it needs |
|--------|-------|----------------|
| Graph `messageRules` (primary, GA) | Graph data-plane | `MailboxSettings.Read` (application) |
| Graph `userConfiguration` FAI (future, beta) | Graph data-plane | `MailboxConfigItem.Read` (application) |
| EWS FAI / hidden-rule blob (today) | EWS data-plane | `full_access_as_app` (application) |
| Exchange `Get-InboxRule` (cross-check) | Exchange management-plane | `Exchange.ManageAsApp` **+ an Entra directory role** |

The last row is the one that trips people up: running an Exchange *cmdlet* app-only is **not** a
Graph permission and **not** a custom Exchange RBAC management role. It needs two separate things —
the `Exchange.ManageAsApp` permission to reach the management endpoint at all, and an **Entra
directory role** assigned to the app that says what it may do once inside (see step 5).

### 1. Register the application

**Entra admin center (`entra.microsoft.com`) → Identity → Applications → App registrations → New
registration.** Choose single-tenant ("My organization only") for a per-tenant install. From the
**Overview** page record:

- **Application (client) ID** — the app identity used everywhere.
- **Directory (tenant) ID**.
- The tenant's `*.onmicrosoft.com` domain — this is the `-Organization` value for app-only sign-in.
  It is *not* necessarily your vanity domain. List it explicitly:

  ```powershell
  Get-AcceptedDomain | Where-Object { $_.DomainName -like "*.onmicrosoft.com" }
  ```

### 2. Upload the certificate

Generate the keypair as in [Creating the certificate](#creating-the-certificate) above, then under
**Certificates & secrets → Certificates** upload the **public** key (`mailtracker.crt`/`.cer`). Keep
the matching PKCS#12 (`.pfx`) private key on the machine running mailtracker (`chmod 600`, never
committed).

### 3. Grant API permissions

**API permissions → Add a permission**, all as **Application permissions**:

- Microsoft Graph: `MailboxSettings.Read` — primary server-side rule reads.
- Microsoft Graph: `MailboxConfigItem.Read` — future Graph FAI transport (beta; confirm the exact
  name in the portal, beta names drift). Optional until that transport is GA.
- Office 365 Exchange Online: `Exchange.ManageAsApp` — lets the `Get-InboxRule` cross-check
  authenticate to the Exchange management endpoint. The preview Admin API may list it as
  `Exchange.ManageAsAppV2` — grant whichever your portal shows.
- Office 365 Exchange Online: `full_access_as_app` — EWS FAI transport that reads hidden/client-only
  rules **today**. Broad and high-value; it is temporary — drop it once you switch
  `fai_transport=graph`.

Then click **Grant admin consent** for the tenant. Nothing works until consent is granted.

### 4. (Recommended) Scope EWS access while EWS is in use

`full_access_as_app` grants EWS access to *every* mailbox by default. While the EWS transport is
active, constrain it with an **Application Access Policy** (and/or the org's EWS app allow-list) so
the app can only reach the mailboxes you intend to audit. This becomes moot once EWS retires.

### 5. Assign the Exchange directory role (the cmdlet "room")

This is what actually lets the app run `Get-InboxRule` app-only — and it is the step people get
wrong. Assign an **Entra directory role** in the portal (directory roles are free, no subscription):

1. Entra admin center → **Identity → Roles & admins → Roles & administrators**.
2. Pick the role → **Add assignments** → search for the app by name → add it (service principals
   appear in the assignment picker).
3. Role choice, least privilege first:
   - Try **Exchange Recipient Administrator** and verify it with the smoke test in step 6 — it may
     not include the inbox-rule cmdlets.
   - If the smoke test returns unauthorized, use **Exchange Administrator** (covers any Exchange
     Online PowerShell task). It is broader; accept the trade-off knowingly.

> **Do not** try to grant `Get-InboxRule` via `New-ServicePrincipal` + a custom management role and
> `New-ManagementRoleAssignment -App`. Exchange only allows its curated *service-principal* roles
> (the `Application *` roles, none of which expose `Get-InboxRule`) to be assigned that way, and
> rejects anything else with `please specify a service principal role`. Cmdlet access for an app
> comes from the directory role above, not a management role.

### 6. Verify (app-only, from Linux)

Confirm the app can authenticate and read another user's rules. On Linux, point the module at the
**`.pfx` file** — `-CertificateThumbprint` reads the Windows cert store and does **not** work here.

```powershell
# PowerShell 7 on Linux, ExchangeOnlineManagement module installed
$pfxPass = Read-Host "PFX password" -AsSecureString
Connect-ExchangeOnline `
  -AppId <client-id> `
  -Organization <tenant>.onmicrosoft.com `
  -CertificateFilePath /path/to/mailtracker.pfx `
  -CertificatePassword $pfxPass

Get-InboxRule -Mailbox someone-else@<tenant-domain> -IncludeHidden
```

- **Rules returned** → cert, `Exchange.ManageAsApp`, and the directory role are all correct.
- **Failure on the `Connect` line** → cert mismatch or `Exchange.ManageAsApp` not consented.
- **Unauthorized on `Get-InboxRule`** → role too low; raise Exchange Recipient Administrator →
  Exchange Administrator and retest.

Also exercise the Graph side independently (`MailboxSettings.Read` against
`/users/{id}/mailFolders/inbox/messageRules`) to confirm the primary source. Then run
`mailtracker register` to capture these values, validate every token live, and create the tenant DB.

### Notes & gotchas

- **Object IDs differ.** The App registrations page shows the *registration's* object ID; the
  *service principal* (under Enterprise applications) has a different object ID. This process uses
  the **client ID** throughout, so the distinction only matters if you ever need the SP object ID.
- **Admin sign-in on Linux:** `Connect-ExchangeOnline -Device` (device-code flow). In Azure Cloud
  Shell, recent module versions need `-DisableWAM` instead.
- **Clean up trial-and-error:** if earlier attempts created an Exchange service principal + custom
  RBAC role, remove them (`Remove-ManagementRole "<name>"`, `Remove-ServicePrincipal -Identity
  "<name>"`) — neither is part of this process.
- **Privilege footprint — a conscious choice.** The primary Graph source needs only the narrow
  `MailboxSettings.Read`. The `Get-InboxRule` cross-check pulls in `Exchange.ManageAsApp` plus a
  tenant-wide Exchange admin-family role — the heaviest grant here — and EWS adds
  `full_access_as_app`. If the cross-check's forensic value doesn't justify that footprint, run
  mailtracker on **Graph alone** (primary + FAI transport) and skip step 5 and the Exchange/EWS
  permissions entirely.

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
